(ns chronojob.worker
  (:require [chronojob
             [async :as async]
             [hikaricp :as hikaricp]
             [http :as http]
             [sql :as sql]]
            [clojure.core.async :as a]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [defcomponent :refer [defcomponent]]
            [clojure.tools.logging :as log]
            [clj-time.core :as time]
            [plumbing.core :refer :all]
            [chronojob.metrics :as metrics])
  (:import [io.prometheus.client Summary]))

(defn do-it*
  [comp job]
  (http/request (:http comp) (-> (:job job)
                                 (assoc :not-200-as-error true)
                                 (update-in [:method] keyword)
                                 (update-in-when [:headers] #(map-keys name %)))))

(defn do-it
  [comp job]
  (a/go
    (let [started-at (System/nanoTime)
          observe (fn [status]
                    (-> (:metric comp)
                        (.labels (into-array [status]))
                        (.observe (/ (- (System/nanoTime) started-at) 1000000))))]
      (try
        (async/<? (do-it* comp job))
        (observe "completed")
        (jdbc/update! (:db comp) :jobs {:status :status/completed
                                        :completed_at (time/now)}
                      ["id = ?" (:id job)])
        (catch Exception e
          (if (or (= -1 (:retries job)) (< 0 (:retries job)))
            (do (log/error e "Error in job, will retry later" {:job job})
                (observe "redo")
                (jdbc/update! (:db comp) :jobs {:status :status/redo
                                                :retries (dec (:retries job))
                                                :do_at (time/plus
                                                        (time/now)
                                                        (time/seconds (:retries_timeout job)))}
                              ["id = ?" (:id job)]))
            (do (log/error e "Error in job, mark it as failed" {:job job})
                (observe "failed")
                (jdbc/update! (:db comp) :jobs {:status :status/failed
                                                :completed_at (time/now)}
                              ["id = ?" (:id job)]))))))))

(defn worker-process
  [comp jobs]
  (a/go-loop []
    (when-let [job (a/<! jobs)]
      (try
        (a/<! (do-it comp job))
        (catch Exception e
          (log/error e)))
      (recur))))

(defn take!
  [comp jobs]
  (a/thread
    (try
      (jdbc/with-db-transaction [t (:db comp)]
        (sql/xact-lock t :take-process (int 1))
        (let [data (-> {:select [:*]
                        :from [:jobs]
                        :where [:and
                                [:< :do_at :%now]
                                [:or
                                 ;; how to deal with enums and honeysql better?
                                 [:= :status #sql/raw "'pending'"]
                                 [:= :status #sql/raw "'redo'"]]]
                        :order-by [[:do_at :asc]]
                        :limit 100}
                       (sql/query t))]
          (when (seq data)
            (jdbc/update! t :jobs {:status :status/inprogress
                                   :taked_at (time/now)}
                          [(str "id in (" (str/join "," (map :id data)) ")")])
            (doseq [job data]
              (a/>!! jobs job)))))
      (catch Exception e
        (log/error e "Exception while processing")))))

(defn taker-process
  [comp stopper]
  (let [jobs (a/chan 100)]
    (a/go-loop []
      (a/alt!
        (a/timeout 1000) ([_] (a/<! (take! comp jobs)) (recur))
        stopper ([_] (a/close! jobs))))
    jobs))

(defn start
  [comp parallel-jobs]
  (let [stopper (a/promise-chan)
        jobs (taker-process comp stopper)
        workers (-> (for [_ (range parallel-jobs)]
                      (worker-process comp jobs))
                    (doall))]
    (fn []
      (a/close! stopper)
      (doseq [w workers]
        (a/<!! w)))))

(defcomponent worker [hikaricp/db http/http metrics/registry]
  [config]
  (start [this]
         (let [metric (.. Summary build
                          (name "chronojob_processed_ms")
                          (labelNames (into-array ["status"]))
                          (help "number of processed tasks")
                          (register (:registry (:registry this))))
               this' (assoc this :metric metric)]
           (assoc this
                  :process (start this' (safe-get config :parallel-jobs)))))
  (stop [this]
        ((:process this))
        this))

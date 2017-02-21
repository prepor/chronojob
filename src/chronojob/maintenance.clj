(ns chronojob.maintenance
  (:require [chronojob.hikaricp :as hikaricp]
            [clojure.core.async :as a]
            [clojure.java.jdbc :as jdbc]
            [defcomponent :refer [defcomponent]]
            [plumbing.core :refer :all]
            [clojure.tools.logging :as log]
            [chronojob.sql :as sql]
            [clojure.string :as str]
            [honeysql.core :as honey-sql]))

(defn do-clean
  [comp retention-period]
  (let [cond [(str "completed_at < now() - interval '" retention-period " hours'"
                   " AND status in ('completed', 'failed')")]
        _ (log/infof "Removing jobs with condition '%s'" cond)
        res (-> (jdbc/delete! (:db comp) :jobs cond) (first))]
    (when (pos? res)
      (log/info "Jobs deleted because retention period had expired"
                {:jobs res :retention-period retention-period}))))

(def ten-minutes (* 10 60 1000))

(defn cleaner-process
  [comp retention-period]
  (let [stopper (a/promise-chan)
        worker (a/go-loop []
                 (a/alt!
                   (a/timeout ten-minutes)
                   ([_]
                    (do-clean comp retention-period)
                    (recur))

                   stopper
                   ([_])))]
    (fn [] (a/close! stopper) (a/<!! worker))))

(defn do-rescue
  [comp job-timeout]
  (let [cond (str "now() - interval '" job-timeout " seconds'")]
    (a/thread
      (jdbc/with-db-transaction [t (:db comp)]
        (sql/xact-lock t :rescue-process (int 1))
        (let [data (-> {:select [:*]
                        :from [:jobs]
                        :where [:and
                                [:< :taked_at (honey-sql/raw cond)]
                                ;; how to deal with enums and honeysql better?
                                [:= :status #sql/raw "'inprogress'"]]
                        :limit 100}
                       (sql/query t))]
          (when (seq data)
            (log/warn "Jobs rescued from inprogress state because job timeout"
                      {:jobs data})
            (jdbc/update! t :jobs {:status :status/redo}
                          [(str "id in (" (str/join "," (map :id data)) ")")])))))))

(defn rescue-process
  [comp job-timeout]
  (let [stopper (a/promise-chan)
        worker (a/go-loop []
                 (a/alt!
                   (a/timeout 10000) ([_] (a/<! (do-rescue comp job-timeout)) (recur))
                   stopper ([_])))]
    (fn [] (a/close! stopper) (a/<!! worker))))

(defcomponent cleaner [hikaricp/db]
  [config]
  (start [this]
         (assoc this :process (cleaner-process this (safe-get config :retention-period))))
  (stop [this]
        ((:process this))
        this))

(defcomponent rescue [hikaricp/db]
  [config]
  (start [this]
         (assoc this :process (rescue-process this (safe-get config :job-timeout))))
  (stop [this]
        ((:process this))
        this))

(comment
  (let [db (get reloaded.repl/system chronojob.hikaricp/db)]
    (do-clean {:db db} (* 24 3))))


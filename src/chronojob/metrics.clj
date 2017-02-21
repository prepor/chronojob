(ns chronojob.metrics
  (:require [clojure.core.async :as a]
            [clojure.tools.logging :as log]
            [chronojob.sql :as sql]
            [defcomponent :refer [defcomponent]]
            [chronojob.hikaricp :as hikaricp]
            [clojure.java.jdbc :as jdbc])
  (:import [io.prometheus.client Gauge CollectorRegistry]))

(defn set-gauge!
  [gauge label new-value]
  (.. gauge
      (labels (into-array [label]))
      (set new-value)))

(def default-stats
  {:status/pending 0
   :status/redo 0
   :status/failed 0
   :status/completed 0})

(defn collect
  [db metric]
  (let [stats (->>
               "select status, count(1) from jobs group by status;"
               (jdbc/query db)
               (reduce (fn [r {:keys [status count]}] (assoc r status count)) {})
               (merge default-stats))]
    (doseq [[status counter] stats]
      (set-gauge! metric (name status) (double counter)))))

(defn metrics-process
  [registry db]
  (let [stopper (a/promise-chan)
        metric (.. Gauge build
                   (name "chronojob_status")
                   (labelNames (into-array ["status"]))
                   (help "number of jobs by status")
                   (register (:registry registry)))]
    (a/go-loop []
      (a/alt!
        ;; Every 10s write stats
        (a/timeout 10000) ([_]
                           (try
                             (collect db metric)
                             (catch Exception e (log/error e)))
                           (recur))
        stopper ([_])))
    (fn [] (a/close! stopper))))

(defcomponent registry []
  [_]
  (start [this] (assoc this :registry (CollectorRegistry.)))
  (stop [this] (.clear (:registry this)) this))

(defcomponent metrics [hikaricp/db registry]
  [_]
  (start [this]
         (assoc this :process (metrics-process (:registry this) (:db this))))
  (stop [this]
        ((:process this))
        this))

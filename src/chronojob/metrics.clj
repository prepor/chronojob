(ns chronojob.metrics
  (:require [clojure.core.async :as a]
            [clojure.tools.logging :as log]
            [chronojob.sql :as sql]
            [defcomponent :refer [defcomponent]]
            [chronojob.hikaricp :as hikaricp])
  (:import [io.prometheus.client Gauge CollectorRegistry]))

(defn collect
  [db metric]
  (let [count (-> {:select [:%count.id]
                   :from [:jobs]
                   :where [:or
                           [:= :status #sql/raw "'pending'"]
                           [:= :status #sql/raw "'redo'"]]}
                  (sql/query-one db)
                  :count)]
    (.set metric (double count))))

(defn metrics-process
  [registry db]
  (let [stopper (a/promise-chan)
        metric (.. Gauge build
                   (name "chronojob_pending")
                   (help "number of pending jobs")
                   (register (:registry registry)))]
    (a/go-loop []
      (a/alt!
        (a/timeout 5000) ([_] (try (collect db metric) (catch Exception e (log/error e)))
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

(ns chronojob.core
  (:require [bidi.ring :as bidi-ring]
            [chronojob
             [hikaricp :as hikaricp]
             [maintenance :as maintenance]
             [metrics :as metrics]
             [sql :as sql]
             [web :as web]
             [worker :as worker]]
            [clj-time
             [coerce :as time-coerce]
             [core :as time]]
            [clojure.java.jdbc :as jdbc]
            [defcomponent :as defcomponent :refer [defcomponent]]
            [honeysql
             [core :as honey-sql]
             [types :as honey-types]]
            [org.httpkit.server :as http-server]
            [plumbing.core :refer :all]
            [plumbing.fnk.pfnk :as pfnk]
            [ring.middleware
             [json :as ring-json]
             [keyword-params :as ring-keyword-params]
             [params :as ring-params]]
            [ring.util.response :as response]
            [schema
             [coerce :as scoerce]
             [core :as s]
             [utils :as sutils]]
            [com.stuartsierra.component :as component]
            [clojure.tools.logging :as log])
  (:import io.prometheus.client.exporter.common.TextFormat
           java.io.StringWriter)
  (:gen-class))

(defn -<timestamp [t] (time-coerce/from-long (* (long t) 1000)))

(defn ->date-time [v]
  (cond
    (string? v) (-<timestamp (Integer/valueOf v))
    (integer? v) (-<timestamp v)
    :else v))

(s/defschema DateTime
  (s/pred #(instance? org.joda.time.DateTime %) :is-date-time))

(defn input-coercer
  [schema]
  (cond
    (= schema DateTime) (fn [v] (->date-time v))
    (vector? schema) (fn [v] (if (coll? v) v [v]))))

(defn maybe-coercer
  [schema matchers]
  (if schema
    (scoerce/coercer schema (scoerce/first-matcher matchers))
    identity))

(defn defhandler*
  [handler]
  (let [params-coercer (maybe-coercer (:params (pfnk/input-schema handler))
                                      [input-coercer scoerce/string-coercion-matcher])
        json-coercer (maybe-coercer (:body (pfnk/input-schema handler))
                                    [input-coercer scoerce/json-coercion-matcher])
        path-coercer (maybe-coercer (:path-params (pfnk/input-schema handler))
                                    [scoerce/string-coercion-matcher])]
    (fn [req]
      (let [params-res (params-coercer (:params req))
            json-res (json-coercer (:body req))
            path-res (path-coercer (:path-params req))]
        (if-let [error (or (sutils/error-val params-res)
                           (sutils/error-val json-res)
                           (sutils/error-val path-res))]
          {:status 400 :headers {} :body (pr-str error)}
          (handler (-> req
                       (assoc-when :params params-res
                                   :body json-res
                                   :path-params path-res))))))))

(defmacro defhandler
  [n & body]
  `(let [handler# (fnk ~n ~@body)]
     (def ~n (defhandler* handler#))))

(defnk metrics
  [[:component registry]]
  (let [writer (StringWriter.)]
    (TextFormat/write004 writer (-> (:registry registry) .metricFamilySamples))
    {:status 200
     :headers {"Content-Type" TextFormat/CONTENT_TYPE_004}
     :body (.toString writer)}))

(def http-job-schema
  {:url s/Str
   :method (s/enum :get :post :put :delete :patch :options :head)
   (s/optional-key :body) s/Str
   (s/optional-key :timeout) s/Int
   (s/optional-key :headers) {s/Keyword s/Str}})

(defhandler index
  []
  (response/resource-response "/public/static/index.html"))

(defhandler job
  [[:component db]
   [:body
    job :- http-job-schema
    at :- DateTime
    retries :- (s/cond-pre s/Int (s/eq :inf))
    {retries-timeout :- s/Int 10}
    {tags :- [s/Str] []}]]
  (let [id (-> (jdbc/insert! db :jobs {:job job
                                       :status :status/pending
                                       :retries (if (= :inf retries) -1 retries)
                                       :retries_timeout retries-timeout
                                       :do_at at
                                       :tags tags
                                       :created_at (time/now)})
               (first) :id)]
    (response/response {:id id})))

(defn index-by
  [f coll]
  (reduce #(assoc %1 (f %2) %2) {} coll))

(defhandler dashboard-stats
  [[:component db]
   [:params {tags :- [s/Str] nil}]]
  (let [req (fn [statuses & [hours]]
              (let [statuses' (for [s statuses]
                                (honey-sql/raw (str "'" s "'")))
                    res (-> {:select [:%count.id :status]
                             :from [:jobs]
                             :group-by [:status]
                             :where (-> [:and
                                         [:in :status statuses']]
                                        (?> hours
                                            (conj [:> :completed_at (honey-sql/raw (str "now() - interval '" hours " hours'"))]))
                                        (?> tags
                                            (conj ["@>" :tags (honey-types/array tags)])))}
                            (sql/query db))]
                (for-map [s statuses]
                         s (or
                            (some #(when (= s (name (:status %))) (:count %)) res)
                            0))))]
    (response/response
     {:total (req #{"pending" "redo"})
      :last-hour (req #{"completed" "failed"} 1)
      :last-24-hours (req #{"completed" "failed"} 24)})))

(defhandler dashboard-jobs
  [[:component db]
   [:params
    {tags :- [s/Str] nil}
    {status :- (s/enum :pending :redo :failed :completed) nil}
    {search :- s/Str nil}]]
  (let [where (-> [:and]
                  (?> search
                      (conj [:like #sql/raw "job::text" (str "%" search "%")]))
                  (?> status
                      (conj [:= :status (honey-sql/raw (str "'" (name status) "'"))]))
                  (?> tags
                      (conj ["@>" :tags (honey-types/array tags)])))
        res (-> {:select [:id :created_at :do_at :status :tags :job]
                 :from [:jobs]
                 :order-by [[:created_at :desc]]
                 :limit 50}
                (assoc-when :where (when (< 1 (count where)) where))
                (sql/query db))]
    (response/response
     {:rows (map #(-> %
                      (update-in [:created_at] time-coerce/to-string)
                      (update-in [:do_at] time-coerce/to-string)
                      (update-in [:status] name))
                 res)})))

(defhandler dashboard-tags
  [[:component db]]
  (let [res (-> {:select [[:%distinct.%unnest.tags :tag]] :from [:jobs]
                 ;; workaround. postgres can't do this query efficiently even
                 ;; with GIN-index
                 :limit 1000}
                (sql/query db)
                (->> (map :tag)))]
    (response/response
     {:tags res})))

(def routes
  [""
   [["/" {:get index}]
    ["/metrics" {:get metrics}]
    ["/api"
     [["/job" {:post job}]]]
    ["/dashboard"
     [["/stats" {:get dashboard-stats}]
      ["/jobs" {:get dashboard-jobs}]
      ["/tags" {:get dashboard-tags}]]]
    ["/static" (web/resources {:prefix "/public/static"})]]])

(defcomponent app [hikaricp/db metrics/registry])

(defn make-handler
  [app]
  (let [h (bidi-ring/make-handler routes)]
    (fn [req]
      (h (assoc req :component app)))))

(defcomponent server [app]
  [config]
  (start
   [this]
   (let [handler (-> (make-handler (:app this))
                     (ring-keyword-params/wrap-keyword-params)
                     (ring-json/wrap-json-body {:keywords? true})
                     (ring-json/wrap-json-response)
                     (ring-params/wrap-params))]
     (assoc this :server (http-server/run-server handler
                                                 {:port (:port config)}))))
  (stop
   [this]
   ((:server this))
   this))

(defn system
  [file-config]
  (let [components [server worker/worker maintenance/cleaner maintenance/rescue
                    metrics/metrics]]
    (defcomponent/system components
      {:file-config file-config})))

(defn at-shutdown
  [f]
  (-> (Runtime/getRuntime)
      (.addShutdownHook (Thread. (bound-fn []
                                   (log/info "Shutdown!")
                                   (f))))))

(defn -main
  [config-path]
  (let [s (system config-path)]
    (component/start s)
    (info/log "System started")
    (at-shutdown #(component/stop s))
    (while true
      (Thread/sleep 1000))))

(ns chronojob.sql
  (:require [cheshire.core :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [honeysql
             [core :as honey]
             [format :as honey-format]
             [helpers :as honey-helpers]])
  (:import java.sql.PreparedStatement
           org.postgresql.util.PGobject))

;; {:identifiers :clojurian} в опциях переводит поля :site_id в :site-id
(defn query
  ([q jdbc] (query q jdbc {}))
  ([q jdbc options]
   (let [sql (honey/format q :quoting :ansi)]
     (log/debug "Query" sql)
     (jdbc/query jdbc sql :identifiers (case (:identifiers options)
                                         :clojurian (comp #(str/replace % #"_" "-") str/lower-case)
                                         str/lower-case)))))

(defn query-one
  ([q jdbc] (query-one q jdbc {}))
  ([q jdbc options]
   (-> q
       (honey-helpers/limit 1)
       (query jdbc options)
       first)))

(def lock-ids {:take-process 1
               :rescue-process 2})

(defn xact-lock
  [conn scope i]
  (jdbc/query conn ["SELECT pg_advisory_xact_lock(?, ?)" (int (lock-ids scope)) i]))

(defn- to-pg-json [data json-type]
  (doto (PGobject.)
    (.setType (name json-type))
    (.setValue (json/generate-string data))))

(defmulti map->parameter (fn [_ t] t))

(defmethod map->parameter :json
  [m _]
  (to-pg-json m :json))

(defmethod map->parameter :jsonb
  [m _]
  (to-pg-json m :jsonb))

(defmethod map->parameter :default
  [m _]
  m)

(defn keyword->parameter
  [v statement i]
  (.setObject statement i
              (if-let [n (namespace v)]
                (doto (org.postgresql.util.PGobject.)
                  (.setType n)
                  (.setValue (name v)))
                v)))

(extend-protocol jdbc/ISQLParameter
  clojure.lang.IPersistentVector
  (set-parameter [v ^PreparedStatement s ^long i]
    (let [conn (.getConnection s)
          meta (.getParameterMetaData s)
          type-name (.getParameterTypeName meta i)
          elem-type (cond
                      (= (first type-name) \_)
                      (apply str (rest type-name))

                      (str/ends-with? type-name "[]")
                      (apply str (take (- (count type-name) 2) type-name))

                      :else nil)]
      (if elem-type
        (.setObject s i (.createArrayOf conn elem-type (to-array v)))
        (.setObject s i v))))
  clojure.lang.IPersistentMap
  (set-parameter [m ^PreparedStatement s ^long i]
    (let [meta (.getParameterMetaData s)]
      (if-let [type-name (keyword (.getParameterTypeName meta i))]
        (.setObject s i (map->parameter m type-name))
        (.setObject s i m))))
  clojure.lang.Keyword
  (set-parameter [v ^PreparedStatement s ^long i]
    (keyword->parameter v s i)))

(defn read-pg-array
  "Arrays are of form {1,2,3}"
  [s]
  (when-not (empty? s)
    (when-let [[_ content] (re-matches #"^\{(.+)\}$" s)]
      (if-not (empty? content)
        (clojure.string/split content #"\s*,\s*")
        []))))

(defmulti read-pgobject
  "Convert returned PGobject to Clojure value."
  #(keyword (when % (.getType ^org.postgresql.util.PGobject %))))

(defmethod read-pgobject :json
  [^org.postgresql.util.PGobject x]
  (when-let [val (.getValue x)]
    (json/parse-string val true)))

(defmethod read-pgobject :jsonb
  [^org.postgresql.util.PGobject x]
  (when-let [val (.getValue x)]
    (json/parse-string val true)))

(defmethod read-pgobject :default
  [^org.postgresql.util.PGobject x]
  (.getValue x))

(defmethod read-pgobject :anyarray
  [^org.postgresql.util.PGobject x]
  (when-let [val (.getValue x)]
    (vec (read-pg-array val))))

(defmethod honey-format/fn-handler "->" [_ a b]
  (str "(" (honey-format/to-sql a) " -> " (honey-format/to-sql b) ")"))

(extend-protocol jdbc/IResultSetReadColumn
  java.sql.Array
  (result-set-read-column [val _ _]
    (into [] (.getArray val)))
  ;; PGobjects have their own multimethod
  org.postgresql.util.PGobject
  (result-set-read-column [val _ _]
    (read-pgobject val))
  String
  (result-set-read-column [val rsmeta i]
    (let [type (.getColumnTypeName rsmeta i)]
      (case type
        ("varchar" "text" "char") val
        (keyword type val)))))

(defn unique-violation?
  [e]
  (= "23505" (.getSQLState e)))

(defmacro with-retry-on-unique
  [& body]
  `(loop []
     (let [res# (try
                  ~@body
                  (catch java.sql.SQLException e#
                    (if (unique-violation? e#)
                      ::retry
                      (throw e#))))]
       (if (= ::retry res#)
         (recur)
         res#))))

(defmacro with-savepoint-retry-on-unique
  [savepoint db & body]
  `(loop []
     (let [res# (try
                  (jdbc/execute! ~db [(str "SAVEPOINT " ~savepoint)])
                  (let [v# ~@body]
                    (jdbc/execute! ~db [(str "RELEASE SAVEPOINT " ~savepoint)])
                    v#)
                  (catch java.sql.SQLException e#
                    (if (unique-violation? e#)
                      (do (jdbc/execute! ~db [(str "ROLLBACK TO " ~savepoint)])
                          ::retry)
                      (throw e#))))]
       (if (= ::retry res#)
         (recur)
         res#))))

(defmethod honey-format/fn-handler "@>"
  [_ a b]
  (str (honey-format/to-sql a) " @> " (honey-format/to-sql b)))

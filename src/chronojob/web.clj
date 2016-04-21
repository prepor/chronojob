(ns chronojob.web
  (:require [bidi.bidi :as bidi]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.util.response :refer [resource-response]]))

;; resources broken in current bidi:
;; https://github.com/juxt/bidi/commit/41ad376c3a26eeed4f6b53724a29ef6fbb95caed

;; Use this to map to paths (e.g. /static) that are expected to resolve
;; to a Java resource, and should fail-fast otherwise (returning a 404).
(defrecord Resources [options]
  bidi/Matched
  (resolve-handler [this m]
    (let [path (bidi/url-decode (:remainder m))]
      (when (not-empty path)
        (assoc (dissoc m :remainder)
               :handler
               (-> (fn [req]
                     (if-let [res (resource-response (str (:prefix options) path))]
                       res
                       {:status 404}))
                   (wrap-content-type options))))))
  (unresolve-handler [this m]
    (when (= this (:handler m)) "")))

(defn resources [options]
  (->Resources options))

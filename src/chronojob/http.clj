(ns chronojob.http
  (:require [cheshire.core :as json]
            [clojure.core.async :as a]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [defcomponent :refer [defcomponent]]
            [org.httpkit.client :as http])
  (:refer-clojure :exclude [get]))

(defprotocol Http
  (request [this params]))

(defcomponent http []
  [_]
  Http
  (request [_ params]
           (log/debug "Http request" params)
           (let [ch (a/promise-chan)]
      (http/request params
                    (fn [{:keys [status error body] :as res}]
                      (if error
                        (do (log/warn "Http request error" params status error body)
                            (a/put! ch error))
                        (if (and (:not-200-as-error params) (not (<= 200 status 300)))
                          (do (log/warn "Http request error" params status body)
                              (a/put! ch (ex-info "Not 200" {:type ::not-200 :status status :body body
                                                             :request params})))
                          (do (log/trace "Http request response" params status body)
                              (a/put! ch res))))))
      ch)))

(ns chronojob.async
  (:require [clojure.core.async :as a]))

(defn throwable?
  [v]
  (instance? Throwable v))

(defn safe-res
  [res]
  (if (throwable? res)
    (throw res)
    res))

(defmacro <? [ch]
  `(when-let [sexp-res# ~ch]
     (safe-res (a/<! sexp-res#))))

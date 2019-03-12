(ns user
  (:require [reloaded.repl :refer [system init start stop go reset reset-all]]
            [chronojob.core :as chronojob]))

(reloaded.repl/set-init! #(chronojob/system "config/dynamic.clj"))

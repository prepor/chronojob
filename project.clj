(defproject chronojob "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 ;; need here latest cheshire for logstash-logback-encoder
                 [cheshire/cheshire "5.9.0"]
                 ; java.lang.ClassNotFoundException: javax.xml.bind.DatatypeConverter
                 ; в Java 11
                 [javax.xml.bind/jaxb-api "2.3.0"]
                 [org.clojure/tools.reader "1.0.0-alpha1"]
                 [http-kit "2.1.18"]
                 [prismatic/schema "1.1.0"]
                 [prismatic/plumbing "0.5.3"]
                 [commons-codec "1.10"]
                 [ring/ring-core "1.4.0"]
                 [com.grammarly/omniconf "0.2.2"]
                 [defcomponent "0.1.6"]
                 [honeysql "0.6.3"]
                 [org.postgresql/postgresql "9.4.1208.jre7"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.clojure/java.jdbc "0.5.0"]
                 [com.zaxxer/HikariCP "2.4.5"]
                 [bidi "2.0.6"]
                 [ring/ring-json "0.4.0"]
                 [org.clojure/core.async "0.2.374"
                  :exclusions [org.clojure/tools.reader]]
                 [clj-time "0.11.0"]

                 [io.prometheus/simpleclient "0.0.14"]
                 [io.prometheus/simpleclient_common "0.0.14"]

                 [org.slf4j/slf4j-api "1.7.16"]
                 [ch.qos.logback/logback-classic "1.1.2"]
                 [org.slf4j/log4j-over-slf4j "1.7.7"]
                 [org.slf4j/jul-to-slf4j "1.7.7"]
                 [org.slf4j/jcl-over-slf4j "1.7.7"]
                 [net.kencochrane.raven/raven-logback "6.0.0"]
                 [net.logstash.logback/logstash-logback-encoder "6.1"]

                 [cljs-http "0.1.39"]
                 [org.clojure/clojurescript "1.7.228"]
                 [rum "0.8.0"]]
  :jvm-opts ["-Duser.timezone=GMT"
             "-Dsun.net.inetaddr.ttl=60"
             "-XX:InitialRAMPercentage=80.0"
             "-XX:MaxRAMPercentage=80.0"]
  :clean-targets ^{:protect false} ["resources/public/js/" "target"]
  :plugins [[lein-figwheel "0.5.2"]
            [lein-cljsbuild "1.1.3" :exclusions [[org.clojure/clojure]]]]
  :cljsbuild {:builds [{:id "dev"
                        :source-paths ["src/"]
                        :figwheel {:on-jsload "chronojob.ui/reload-hook" }
                        :compiler {:main "chronojob.ui"
                                   :asset-path "/static/js/out"
                                   :output-to "resources/public/static/js/main.js"
                                   :output-dir "resources/public/static/js/out" } }
                       {:id "prod"
                        :source-paths ["src/"]
                        :compiler {:main "chronojob.ui"
                                   :optimizations :advanced
                                   :asset-path "/static/js/out"
                                   :pretty-print false
                                   :output-to "resources/public/static/js/main.js"} }]}
  :figwheel {:css-dirs ["resources/public/static/css"]}
  :main chronojob.core
  :profiles {:dev {:dependencies [[reloaded.repl "0.2.1"]]
                   :source-paths ["dev"]}
             :uberjar {:aot :all}})

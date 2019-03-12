(->
  (load-file "config/dynamic.clj")
  (assoc :port 7000
         :db {:uri "jdbc:postgresql://chronojob-db/chronojob"
              :username "chronojob"
              :password "chronojob"}))
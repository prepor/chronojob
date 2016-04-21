{:port 7000
 :db {:uri "jdbc:postgresql://localhost/chronojob"
      :username "chronojob"
      :password "chronojob"}
 :parallel-jobs 10
 :retention-period (* 14 24)                   ; hours
 :job-timeout (* 30 60)}                       ; seconds

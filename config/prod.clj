{:port 7000
 :db {:uri (str "jdbc:postgresql://" (System/getenv "POSTGRES_HOST") "/" (System/getenv "POSTGRES_NAME"))
      :username (System/getenv "POSTGRES_USER")
      :password (System/getenv "POSTGRES_PASSWORD")}
 :parallel-jobs 10
 :retention-period (* (or (System/getenv "RETENTION_DAYS") 3) 24)   ; hours
 :job-timeout (* 30 60)}                                     ; seconds

(require '[flock.staff.config.dev :as dev])

(->
  (load-file "config/test_docker_compose.clj")

  ;; example of mocking main-db on local environment
  (update-in [:chronjob :uri] dev/proxy)
  )
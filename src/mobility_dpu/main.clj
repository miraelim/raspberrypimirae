(ns mobility-dpu.main
  (:gen-class)
  (:require [taoensso.timbre :as timbre]
            [taoensso.timbre.appenders.core :as appenders]
            [mobility-dpu.temporal :refer [dt-parser]]
            [mobility-dpu.shims-sync :as shims]
            [mobility-dpu.summary :as summary]
            [mobility-dpu.home-location :as home]
            [clj-time.core :as t]
            [schema.core :as s]
            [clj-time.coerce :as c])
  (:use [mobility-dpu.protocols]
        [mobility-dpu.android :only [->AndroidUserDatasource]]
        [mobility-dpu.ios :only [->iOSUserDatasource]]
        [mobility-dpu.moves-sync :only [->MovesUserDatasource]]
        [mobility-dpu.database :only [mongodb]]
        [mobility-dpu.config :only [config]]
        [aprint.core]))

;; config logger
(timbre/refer-timbre)


;; tracking the last update time
(def user-source->last-update (atom {}))

(defn sync-shims [db users]
  (doseq [user users]
    (try
      (doseq [dp (shims/get-datapoints user (:sync-tasks @config))]

        (info "Save data for " user " "
              (get-in dp [:header :acquisition_provenance :source_name]) " "
              (get-in dp [:header :creation_date_time]))
        (save db dp)

        )

      (catch Exception e (error e)))
    ; sleep to avoid polling service provider too fast
    ; FIXME use a more efficient throttle function?
    (Thread/sleep 5000)
    )
  )

(defn sync-one-user [user data-source purge-raw? db]
  (try
    (let [start-time (t/now)
          provided-home-loc (home/provided-home-location user db)]
      (if provided-home-loc

        (let [datapoints (summary/get-datapoints
                           data-source
                           provided-home-loc
                           purge-raw?
                           )]

          (if (seq datapoints)
            (let [dates (->>
                          datapoints
                          (map (comp :date :body))
                          (distinct)
                          (sort)
                          )]
              (doseq [datapoint datapoints]
                (p :save (save db (s/validate MobilityDataPoint datapoint)))
                )
              (info (str "User " user " provided home location:" provided-home-loc)))
              (info "Save data for " user
                    (source-name data-source)
                    (first dates)
                    "-"
                    (last dates)
                    "Elapsed time"
                    (str (t/in-seconds (t/interval start-time (t/now))) "s")
                    )
              (if purge-raw?
                (let [remove-until (t/minus (c/to-local-date (last dates)) (t/days 1))]
                  (info "Purge raw data for " user (source-name data-source)
                        "up to " remove-until)
                  (purge-raw-trace data-source remove-until)
                  )
                )
              :success
              )
            )
          )
      )
    (catch Exception e (error e)))
  )


(defn sync-data-sources [db data-sources users]
  (doseq [; run dpu for specifc users (if args are set) or all the users in the db
          user users
          ; functions to generate datapoints from different sources: Android, iOS, and Moves App
          source-fn data-sources]
    (let [source (source-fn user)
          last-raw-data-update-time (last-update source)
          last-process-time (get @user-source->last-update  [user (source-name source)])
          purge-data? (purge-raw-data? db user)
          ]
      ; only compute new data points if there are new raw data that have been uploaded
      (if
        (or (nil? last-raw-data-update-time)
            (nil? last-process-time)
            (t/after? last-raw-data-update-time last-process-time)
            )
        (when (sync-one-user user source purge-data? db)
          ; store the last update time
          (swap! user-source->last-update assoc [user (source-name source)] last-raw-data-update-time))
        )
      )
    ))


(defn -main
  "The application's main function"
  [& args]

  (timbre/merge-config!
    {:appenders {:spit (appenders/spit-appender {:fname (:log-file @config)})}})

  (info "Run with config:" @config)
  (let [db (loop []
             (if-let [db (try (mongodb)
                          (catch Exception _
                            (info "Waiting for mongodb" (@config :mongodb-uri))
                            (Thread/sleep 1000)
                            nil
                            ))]
               db
               (recur)
               ))
        get-users #(or (seq args) (users db))]
    ; Create a new thread to sync other shims sync tasks
    (future
      (loop []
        (try
          (sync-shims db (get-users))
          ; sleep to avoid deplete the API quota
          (Thread/sleep (* 1000 60 15))

          (catch Exception e
            (Thread/sleep 1000)
            (warn e)
            ))
        (recur))
      )
    ; Create a new thread to sync Moves
    (future
      (loop []
        (try
          (sync-data-sources
            db
            [#(->MovesUserDatasource %)]
            (get-users))
          (catch Exception e
            (Thread/sleep 1000)
            (warn e)
            ))
        (recur)
        )
      )
    ; sync Android and iOS mobility
    (loop []
      (try
        (sync-data-sources
          db
          [#(->AndroidUserDatasource % db) #(->iOSUserDatasource % db)]
          (get-users))
        (catch Exception e
          (Thread/sleep 1000)
          (warn e)
          ))
      (recur)
      )
  )
  )




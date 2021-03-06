(ns mobility-dpu.database
  (:require [monger.core :as mg]
            [monger.query :as mq]
            [monger.collection :as mc]

            [mobility-dpu.temporal]
            [schema.core :as s]
            [monger.conversion :refer :all]
            [clj-time.core :as t]
            [taoensso.nippy :as nippy]
            [clj-time.coerce :as c]
            [taoensso.timbre :as timbre]
            [mobility-dpu.summary :as summary]
            [clojure.java.jdbc :as sql]
            [honeysql.core :as h]
            [honeysql.helpers :refer :all]
            )
  (:use [mobility-dpu.config]
        [mobility-dpu.protocols])
  (:import (clojure.lang IPersistentMap IPersistentCollection)
           (org.joda.time ReadableInstant ReadablePartial DateTimeZone)

           ))




(timbre/refer-timbre)

;; convert datetime to string
(defmulti time->str class)
(defmethod time->str IPersistentMap [m]
  (reduce (fn [m [k v]]
            (assoc m (time->str k) (time->str v))
            ) {} m))
(defmethod time->str String [m] m)
(defmethod time->str IPersistentCollection [m]
  (map time->str m))
(defmethod time->str ReadableInstant [m] (str m))
(defmethod time->str ReadablePartial [m] (str m))
(defmethod time->str DateTimeZone [m] (str m))
(defmethod time->str :default [m] m)


(def data-coll "dataPoint")
(def episode-cache-coll "mobilityEpisodes")
(def offload-coll "mobilityOffload")


(def db-spec (delay {:connection-uri (@config :admin-dashboard-jdbc-uri)}))

(defn mongodb
  "Create a MongoDB-backed DatabaseProtocol"
  ([] (let [{:keys [conn db]} (mg/connect-via-uri (@config :mongodb-uri))
            coll data-coll]
        (mongodb db coll)
        ))
  ([db coll]
    (reify DatabaseProtocol
      (query [_ ns name user]
        (let [rows (mq/with-collection db coll
                                       (mq/find {"header.schema_id.name"      name
                                                 "header.schema_id.namespace" ns
                                                 "header.user_id"                     user})
                                       (mq/keywordize-fields true)
                                       (mq/fields ["body" "header.creation_date_time"])
                                       (mq/sort {"header.creation_date_time" 1})

                                       (mq/batch-size 10000)
                                       )]
          (for [row rows]
            (->DataPointRecord
              (:body row)
              (mobility-dpu.temporal/dt-parser
                (get-in row [:header :creation_date_time])))
            ))
        )
      (maintain [_]

        )
      (remove-until [_ ns name user date]
        (mc/remove
          db coll
          {"header.schema_id.name"      name
           "header.schema_id.namespace" ns
           :user_id                     user
           "header.creation_date_time"  {:$lt (str (t/plus date (t/days 1)))}
           })
        )
      (last-time [_ ns name user]
        (let [row (->> (mq/with-collection
                         db coll
                         (mq/find {"header.schema_id.name"      name
                                   "header.schema_id.namespace" ns
                                   :user_id                     user})
                         (mq/keywordize-fields true)
                         (mq/sort {"header.creation_date_time_epoch_milli" -1})
                         (mq/limit 1))
                       (first)
                       )]
          (if row (mobility-dpu.temporal/dt-parser
                    (get-in row [:header :creation_date_time])))
          )
        )
      (save [_ data]
        (mc/save db coll (time->str (s/validate DataPoint data)))
        )
      (users [_] (mc/distinct db "endUser" "_id" {}))

      (offload-data [_ ns name user until]
        (let [query {"header.schema_id.name"                 name
                     "header.schema_id.namespace"            ns
                     :user_id                                user
                     "header.creation_date_time_epoch_milli" {:$lte (c/to-long until)}}]
          (doseq [row (mc/find-maps db coll query)]
            (mc/save db offload-coll row)
            )
          (mc/remove db coll query)
          )

        )

      (cache-episode [_ user device episodes]
        (mc/update
          db episode-cache-coll {:user user :device device} {:$set {:valid false}} {:multi true})
        ; crash point a
        (if (seq episodes)
          (mc/insert-batch
            db episode-cache-coll
            (for [epi episodes]
              {:user user
               :device device
               :valid true
               :episode-binary (nippy/freeze epi)})))
        ; crash point b
        (mc/remove
          db episode-cache-coll{:user user :device device :valid false})

        )
      (get-episode-cache [_ user device]
        (let [; whether crashed at crash point b
              crashed? (= 2 (count
                              (mc/distinct
                                db episode-cache-coll
                                :valid {:device device :user user})))

              valid-cache (mc/find-maps db episode-cache-coll
                                      (cond->
                                        {:device device :user user}
                                        crashed?
                                        (assoc :valid true )))


              ]
          (if crashed?
            (warn "It seems that the db crashed BEFORE removing the all episode cache. Using new cache only"
                  (cond->
                    {:device device :user user}
                    crashed?
                    (assoc :valid true ))))
          (->> (map :episode-binary valid-cache)
               (map nippy/thaw)
               (sort-by :start)
               )
          )
        )
      (remove-gps? [_ user]
        (comment
          (->
            (sql/query
              @db-spec
              ["
              SELECT COUNT(*)
              FROM users
              INNER JOIN study_participants ON study_participants.user_id = users.id
              INNER JOIN studies ON study_participants.study_id = studies.id
              WHERE username = ? AND remove_gps = true;" user])
            (first)
            :count
            (> 0)
            ))
        nil
        )
      ))
  )

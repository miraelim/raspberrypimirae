(ns mobility-dpu.database
  (:require [monger.core :as mg]
            [monger.query :as mq]
            [monger.collection :as mc]

            [mobility-dpu.temporal]
            [schema.core :as s]
            [monger.conversion :refer :all])
  (:use [mobility-dpu.config]
        [mobility-dpu.protocols])
  (:import (org.joda.time.base AbstractInstant AbstractPartial)
           (clojure.lang IPersistentMap IPersistentCollection)
           (org.joda.time ReadableInstant ReadablePartial DateTimeZone)))


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



(def db-connection
  (delay (-> (mg/get-db (mg/connect (:mongodb @config)) (:dbname @config)))))

(defn mongodb
  "Create a MongoDB-backed DatabaseProtocol"
  []
  (let [db @db-connection
        coll "dataPoint"]
    (reify DatabaseProtocol
      (query [_ ns name user]
        (let [rows (mq/with-collection db coll
                                       (mq/find {"header.schema_id.name"      name
                                                 "header.schema_id.namespace" ns
                                                 :user_id                     user})
                                       (mq/keywordize-fields true)
                                       (mq/fields ["body" "header.creation_date_time"])
                                       (mq/sort {"header.creation_date_time_epoch_milli" 1})

                                       (mq/batch-size 10000)
                                       )]
          (for [row rows]
            (->DataPointRecord
              (:body row)
              (mobility-dpu.temporal/dt-parser
                (get-in row [:header :creation_date_time])))
            ))
        )
      (last-time [_ ns name user]
        (let [row (->> (mq/with-collection db coll
                                           (mq/find {"header.schema_id.name"      name
                                                     "header.schema_id.namespace" ns
                                                     :user_id                     user})
                                           (mq/keywordize-fields true)
                                           (mq/sort {"header.creation_date_time_epoch_milli" -1})
                                           (mq/limit 1)
                                           )
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
      ))
  )

(ns
  mobility-dpu.protocols
  (:require [clj-time.core :as t]
            [schema.core :as s]
            [clj-time.coerce :as c])
  (:import (org.joda.time LocalDate DateTimeZone)))

(def State (s/enum :in_vehicle
                   :on_bicycle
                   :on_foot
                   :still
                   :unknown))

(def Latitude s/Num)
(def Longitude s/Num)
(def Accuracy s/Num)

(def Location
  {:latitude Latitude
   :longitude Longitude
   s/Any s/Any})

(defprotocol ActivitySampleProtocol
  (prob-sample-given-state [this state]
    "return P(Hidden State=s | this sample),  namely, given observing this sample, what is the probability of hidden state being s")
  )

(defprotocol TimestampedProtocol
  (timestamp [this]))

(s/defrecord LocationSample [timestamp :- (s/protocol t/DateTimeProtocol)
                             latitude :- Latitude
                             longitude :- Longitude
                             accuracy :- (s/maybe Accuracy)]
  TimestampedProtocol
  (timestamp [_] "The time when the location smaple is colleted" timestamp)
  )

(s/defrecord StepSample[start :- (s/protocol t/DateTimeProtocol)
                        end :- (s/protocol t/DateTimeProtocol)
                        step-count :- (s/pred #(>= % 0))]
  TimestampedProtocol
  (timestamp [_] "The time when the step smaple is colleted" start)
  )


(s/defrecord TraceData [activity-trace :- [(s/protocol ActivitySampleProtocol)]
                        location-trace :- [LocationSample]
                        step-trace :- [StepSample]])




(def Cluster
  (assoc Location
    :first-time (s/protocol t/DateTimeProtocol)
    :last-time (s/protocol t/DateTimeProtocol)
    :total-time-in-minutes s/Num
    :number-days s/Int
    :hourly-distribution [[(s/one s/Int "hour") (s/one s/Num "number of hours")]]
    ))



(def EpisodeSchema
  {:inferred-state State
   :start (s/protocol t/DateTimeProtocol)
   :end (s/protocol t/DateTimeProtocol)
   :trace-data TraceData
   (s/optional-key :cluster) Cluster
   (s/optional-key :home?) s/Bool
   ; distance in KM
   (s/optional-key :distance) s/Num
   (s/optional-key :calories) s/Num
   ; distance in seconds
   (s/optional-key :duration) s/Num

   (s/optional-key :raw-data) s/Any
   })




(def DayEpisodeGroup
  {:date     LocalDate
   :zone     DateTimeZone
   :episodes [EpisodeSchema]})

(def DataPoint
  {:header
            {:acquisition_provenance         {:modality #"SENSED", :source_name s/Str}
             :creation_date_time_epoch_milli s/Int,
             :creation_date_time             (s/pred #(c/from-string %) "valid datetime")
             :schema_id                      {:version   {:minor s/Int, :major s/Int},
                                              :name      s/Str
                                              :namespace s/Str}
             :id                             s/Str}
   :user_id s/Str,
   :_class  #"org.openmhealth.dsu.domain.DataPoint",
   :_id     s/Str
   :body    s/Any
   })

(def SummaryDataPoint
  (assoc DataPoint
    :body
    {:longest-trek-in-km                 s/Num,
     :active_time_in_seconds             s/Num,
     :walking_distance_in_km             s/Num,
     (s/optional-key :leave_home_time)   (s/pred #(c/from-string %) "valid datetime"),
     (s/optional-key :return_home_time)  (s/pred #(c/from-string %) "valid datetime"),
     (s/optional-key :time_not_at_home_in_seconds) s/Num
     :date                               #"\d{4}-\d\d-\d\d",
     :device                             s/Str
     :max_gait_speed_in_meter_per_second (s/maybe s/Num),
     :gait_speed                         {:gait_speed (s/maybe s/Num), :quantile s/Num, :n_meters s/Num}
     :geodiameter_in_km                  s/Num,
     :steps                              (s/maybe s/Num),
     :coverage                           s/Num,
     :episodes                           [s/Any]})
  )
(def SegmentDataPoint
  (assoc DataPoint
    :body
    {:date     #"\d{4}-\d\d-\d\d"
     :device   s/Str
     :episodes [s/Any]
     })
  )

(def MobilityDataPoint
  (s/conditional
    #(let [schema-name (get-in % [:header :schema_id :name])]
      (= schema-name "mobility-daily-summary")
      )
    SummaryDataPoint
    #(let [schema-name (get-in % [:header :schema_id :name])]
      (= schema-name "mobility-daily-segments")
      )
    SegmentDataPoint
    ))








(defrecord DataPointRecord [body timestamp]
  TimestampedProtocol
  (timestamp [_] timestamp)
  )








(s/defrecord Episode [inferred-state :- State
                      start :- (s/protocol t/DateTimeProtocol)
                      end :- (s/protocol t/DateTimeProtocol)
                      trace-data :- TraceData])




(defmulti trim (fn [main _ _]
                 (class main)
                 ))
(defmethod trim TraceData
  [data
   new-start
   new-end]
  (let [interval (t/interval new-start new-end)
        within #(or (t/within? interval (timestamp %)) (= new-end (timestamp %)))]
    (assoc data
      :activity-trace (filter within (:activity-trace data))
      :step-trace (filter within (:step-trace data))
      :location-trace (filter within (:location-trace data)))
    )
  )
(defmethod trim Episode
  [episode   new-start   new-end]
  (assoc episode
    :trace-data (trim (:trace-data episode) new-start   new-end)
    :start new-start
    :end new-end
    )
  )

(defmulti merge-two (fn [a b]
                 (assert (= (class a) (class b)) )
                  (class a)
                 ))
(defmethod merge-two TraceData
  [a b]
  (->TraceData
    (sort-by timestamp (apply concat (map :activity-trace [a b])))
    (sort-by timestamp (apply concat (map :location-trace [a b])))
    (sort-by timestamp (apply concat (map :step-trace [a b])))
    )
  )
(defmethod merge-two Episode
  [a b]
  (assoc (merge a b)
    :trace-data (merge-two (:trace-data a) (:trace-data b))
    :start (if (t/before? (:start a) (:start b))
             (:start a)
             (:start b)
             )
    :end (if (t/after? (:end a) (:end b))
           (:end a)
           (:end b)
           )
    )
  )



(defprotocol DatabaseProtocol
  (query [this schema-namespace schema-name user]
    "query data point of specific schema and user")
  (last-time [this schema-namespace schema-name user]
    "query data point of specific schema and user")
  (save [this data]
    "Save the data point. Replace the existing data point with the same id.")
  (users [this]
    "Return distinct users")
  )


(defprotocol UserDataSourceProtocol
  (user [this] "Return the user name")
  (source-name [this] "Return the name of the data source")
  (step-supported? [this] "If the data source support the steps count")
  (extract-episodes [this])
  (last-update [this] "Return the last update time")
  )




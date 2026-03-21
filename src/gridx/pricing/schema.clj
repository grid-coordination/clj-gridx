(ns gridx.pricing.schema
  "Malli schemas for coerced GridX pricing entities.

  These describe the Clojure-native shape produced by `gridx.pricing/curves`:
  namespaced keywords, BigDecimals, Instants, OffsetDateTimes, and tick intervals.

  Component names and price types cover both PG&E and SCE vocabularies."
  (:import [java.time OffsetDateTime]))

(def Component
  [:map
   [:gridx.component/name :keyword]
   [:gridx.component/price decimal?]
   [:gridx.component/type :keyword]])

(def Interval
  [:map
   [:gridx.interval/period [:map
                            [:tick/beginning inst?]
                            [:tick/end inst?]]]
   [:gridx.interval/price decimal?]
   [:gridx.interval/status [:enum :gridx.status/final
                            :gridx.status/preliminary]]
   [:gridx.interval/components [:vector Component]]])

(def Curve
  [:map
   [:gridx.curve/name :string]
   [:gridx.curve/market :keyword]
   [:gridx.curve/interval-minutes [:enum 15 60]]
   [:gridx.curve/currency :keyword]
   [:gridx.curve/unit :keyword]
   [:gridx.curve/start [:fn (fn [x] (instance? OffsetDateTime x))]]
   [:gridx.curve/end [:fn (fn [x] (instance? OffsetDateTime x))]]
   [:gridx.curve/period [:map [:tick/beginning inst?] [:tick/end inst?]]]
   [:gridx.curve/record-count :int]
   [:gridx.curve/intervals [:vector Interval]]])

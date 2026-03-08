(ns gridx.pricing
  "Higher-level functions for working with GridX pricing data.
  Handles response parsing, price component extraction, and date helpers."
  (:require [gridx.client :as client]
            [tick.core :as t]
            [malli.core :as m]))

;; ---------------------------------------------------------------------------
;; Malli schemas for response validation
;; ---------------------------------------------------------------------------

(def PriceComponent
  [:map
   [:component [:enum "cld" "mec" "mgcc"]]
   [:intervalPrice :string]
   [:priceType [:enum "generation" "distribution"]]])

(def PriceDetail
  [:map
   [:startIntervalTimeStamp :string]
   [:intervalPrice :string]
   [:priceStatus :string]
   [:priceComponents [:vector PriceComponent]]])

(def PriceHeader
  [:map
   [:priceCurveName :string]
   [:marketName :string]
   [:intervalLengthInMinutes [:enum 15 60]]
   [:settlementCurrency :string]
   [:settlementUnit :string]
   [:startTime :string]
   [:endTime :string]
   [:recordCount :int]])

(def PriceCurve
  [:map
   [:priceHeader PriceHeader]
   [:priceDetails [:vector PriceDetail]]])

(def PricingResponseMeta
  [:map
   [:code :int]
   [:requestURL [:maybe :string]]
   [:requestBody :string]
   [:response :string]])

(def PricingResponse
  [:map
   [:meta PricingResponseMeta]
   [:data [:vector PriceCurve]]])

;; ---------------------------------------------------------------------------
;; Date formatting
;; ---------------------------------------------------------------------------

(defn ->gridx-date
  "Convert a tick date (or ISO string) to GridX YYYYMMDD format."
  [d]
  (t/format (t/formatter "yyyyMMdd") (t/date d)))

;; ---------------------------------------------------------------------------
;; Response helpers
;; ---------------------------------------------------------------------------

(defn success?
  "True if the API response indicates success."
  [response]
  (and (= 200 (:status response))
       (= 200 (get-in response [:body :meta :code]))))

(defn price-curves
  "Extract the vector of price curves from a successful response."
  [response]
  (get-in response [:body :data]))

(defn validate-response
  "Validate a parsed response body against the Malli schema.
  Returns nil on success, or an explanation map on failure."
  [body]
  (m/explain PricingResponse body))

(defn total-price
  "Parse the total interval price from a price detail as a BigDecimal."
  [price-detail]
  (BigDecimal. ^String (:intervalPrice price-detail)))

(defn component-prices
  "Extract price components from a price detail as a map of keyword to BigDecimal.
  Example: {:mec 0.052564M :mgcc 0.000000M :cld 0.000482M}"
  [price-detail]
  (->> (:priceComponents price-detail)
       (reduce (fn [acc {:keys [component intervalPrice]}]
                 (assoc acc (keyword component) (BigDecimal. ^String intervalPrice)))
               {})))

(ns gridx.pricing
  "Higher-level functions for working with GridX pricing data.

  Two layers:
  - Raw: camelCase keys, string values — direct from the API JSON.
  - Coerced: namespaced keywords, BigDecimals, OffsetDateTimes — Clojure-friendly.

  The coerced layer preserves raw data as metadata via :gridx/raw."
  (:require [tick.core :as t]
            [tick.alpha.interval :as t.i]
            [malli.core :as m])
  (:import [java.time Duration Instant OffsetDateTime]
           [java.time.format DateTimeFormatter]))

;; ---------------------------------------------------------------------------
;; Raw API schemas (camelCase, strings — mirrors JSON)
;; ---------------------------------------------------------------------------

(def RawPriceComponent
  [:map
   [:component [:enum "cld" "mec" "mgcc"]]
   [:intervalPrice :string]
   [:priceType [:enum "generation" "distribution"]]])

(def RawPriceDetail
  [:map
   [:startIntervalTimeStamp :string]
   [:intervalPrice :string]
   [:priceStatus :string]
   [:priceComponents [:vector RawPriceComponent]]])

(def RawPriceHeader
  [:map
   [:priceCurveName :string]
   [:marketName :string]
   [:intervalLengthInMinutes [:enum 15 60]]
   [:settlementCurrency :string]
   [:settlementUnit :string]
   [:startTime :string]
   [:endTime :string]
   [:recordCount :int]])

(def RawPriceCurve
  [:map
   [:priceHeader RawPriceHeader]
   [:priceDetails [:vector RawPriceDetail]]])

(def RawResponseMeta
  [:map
   [:code :int]
   [:requestURL [:maybe :string]]
   [:requestBody :string]
   [:response :string]])

(def RawPricingResponse
  [:map
   [:meta RawResponseMeta]
   [:data [:vector RawPriceCurve]]])

;; ---------------------------------------------------------------------------
;; Coerced entity schemas (namespaced keywords, native types)
;; ---------------------------------------------------------------------------

(def Component
  [:map
   [:gridx.component/name [:enum :gridx.component/cld
                           :gridx.component/mec
                           :gridx.component/mgcc]]
   [:gridx.component/price decimal?]
   [:gridx.component/type [:enum :gridx.price-type/generation
                           :gridx.price-type/distribution]]])

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

;; ---------------------------------------------------------------------------
;; Parsing helpers
;; ---------------------------------------------------------------------------

(def ^:private ^DateTimeFormatter gridx-timestamp-formatter
  (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ssZ"))

(defn- parse-offset-datetime
  "Parse a GridX timestamp string (e.g. '2026-03-08T00:00:00-0800')
  into an OffsetDateTime, preserving the original offset."
  ^OffsetDateTime [^String s]
  (OffsetDateTime/parse s gridx-timestamp-formatter))

(defn- parse-instant
  "Parse a GridX timestamp string into a UTC Instant.
  The offset is used for conversion then discarded."
  ^Instant [^String s]
  (.toInstant (parse-offset-datetime s)))

(defn- parse-decimal
  "Parse a decimal string into a BigDecimal."
  ^BigDecimal [^String s]
  (BigDecimal. s))

(defn- ->keyword-lower
  "Convert a string to a lowercase keyword in the given namespace."
  [ns s]
  (keyword ns (.toLowerCase ^String s)))

;; ---------------------------------------------------------------------------
;; Date formatting (for API requests)
;; ---------------------------------------------------------------------------

(defn ->gridx-date
  "Convert a tick date (or ISO string) to GridX YYYYMMDD format.

  Examples:
    (->gridx-date (t/date \"2026-03-08\")) ;=> \"20260308\"
    (->gridx-date (t/today))               ;=> \"20260308\""
  [d]
  (t/format (t/formatter "yyyyMMdd") (t/date d)))

;; ---------------------------------------------------------------------------
;; Raw response helpers
;; ---------------------------------------------------------------------------

(defn success?
  "True if the API response indicates success (HTTP 200 and meta code 200).
  Use this to check before extracting curves."
  [response]
  (and (= 200 (:status response))
       (= 200 (get-in response [:body :meta :code]))))

(defn raw-curves
  "Extract the vector of raw price curves from a successful API response.
  Returns the data as-is from the JSON: camelCase keys, string values.
  See `curves` for the coerced version."
  [response]
  (get-in response [:body :data]))

(defn validate-raw
  "Validate a parsed response body against the raw Malli schema.
  Returns nil on success, or a Malli explanation map on failure.
  Operates on the :body of the HTTP response, not the full response."
  [body]
  (m/explain RawPricingResponse body))

;; ---------------------------------------------------------------------------
;; Coercion: raw → Clojure entities
;; ---------------------------------------------------------------------------

(defn ->component
  "Coerce a raw price component map into a namespaced Component.

  Transforms:
    \"cld\"        → :gridx.component/cld
    \"0.000351\"   → 0.000351M
    \"generation\" → :gridx.price-type/generation

  Attaches the original raw map as :gridx/raw metadata."
  [raw]
  (-> {:gridx.component/name  (keyword "gridx.component" (:component raw))
       :gridx.component/price (parse-decimal (:intervalPrice raw))
       :gridx.component/type  (->keyword-lower "gridx.price-type" (:priceType raw))}
      (with-meta {:gridx/raw raw})))

(defn ->interval
  "Coerce a raw price detail map into a namespaced Interval.

  `duration` is a java.time.Duration for the interval length (from the
  curve header's intervalLengthInMinutes). Used to compute the interval's
  end time from its start, producing a tick interval as :gridx.interval/period.

  Timestamps are converted to UTC Instants. Prices become BigDecimals.
  Status strings become namespaced keywords (e.g. :gridx.status/final).
  Attaches the original raw map as :gridx/raw metadata."
  [^Duration duration raw]
  (let [components (mapv ->component (:priceComponents raw))
        start (parse-instant (:startIntervalTimeStamp raw))
        end (.plus start duration)]
    (-> {:gridx.interval/period     (t.i/new-interval start end)
         :gridx.interval/price      (parse-decimal (:intervalPrice raw))
         :gridx.interval/status     (->keyword-lower "gridx.status" (:priceStatus raw))
         :gridx.interval/components components}
        (with-meta {:gridx/raw raw}))))

(defn ->curve
  "Coerce a raw price curve map into a namespaced Curve.

  The curve header's start/end times are preserved as OffsetDateTimes
  (market-local context) in :gridx.curve/start and :gridx.curve/end.
  A tick interval in UTC is also provided as :gridx.curve/period for
  interval algebra operations.

  Note: the API reports end time as 23:59:59 (inclusive), while tick
  intervals are half-open [start, end). This means the curve period
  ends 1 second before the last interval's end. This is faithful to
  the API; we do not adjust it.

  Attaches the original raw map as :gridx/raw metadata."
  [raw]
  (let [header (:priceHeader raw)
        duration (Duration/ofMinutes (:intervalLengthInMinutes header))
        start-odt (parse-offset-datetime (:startTime header))
        end-odt   (parse-offset-datetime (:endTime header))]
    (-> {:gridx.curve/name             (:priceCurveName header)
         :gridx.curve/market           (->keyword-lower "gridx.market" (:marketName header))
         :gridx.curve/interval-minutes (:intervalLengthInMinutes header)
         :gridx.curve/currency         (keyword (:settlementCurrency header))
         :gridx.curve/unit             (keyword (:settlementUnit header))
         :gridx.curve/start            start-odt
         :gridx.curve/end              end-odt
         :gridx.curve/period           (t.i/new-interval (.toInstant start-odt)
                                                         (.toInstant end-odt))
         :gridx.curve/record-count     (:recordCount header)
         :gridx.curve/intervals        (mapv (partial ->interval duration) (:priceDetails raw))}
        (with-meta {:gridx/raw raw}))))

(defn curves
  "Extract and coerce price curves from a successful API response.

  This is the main entry point for the coerced layer. Returns a vector
  of Curve maps with namespaced keywords, native types (BigDecimal,
  Instant, OffsetDateTime), and tick intervals. Each entity at every
  level carries :gridx/raw metadata with the original API data.

  See `raw-curves` for the uncoerced version."
  [response]
  (mapv ->curve (raw-curves response)))

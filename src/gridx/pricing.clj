(ns gridx.pricing
  "Higher-level functions for working with GridX pricing data.

  Two layers:
  - Raw: camelCase keys, string values — direct from the API JSON.
  - Coerced: namespaced keywords, BigDecimals, ZonedDateTimes — Clojure-friendly.

  The coerced layer preserves raw data as metadata via :gridx/raw.

  Time handling: every coerced timestamp is a `ZonedDateTime` in the
  zone configured on the client (per-instance via `:zone`). The parser
  reads the API's offset, then `.atZoneSameInstant`s into the configured
  zone — so wall-clock times stay consistent with what the API meant
  while gaining DST-aware behavior when arithmetic crosses transitions.

  Schemas are in separate namespaces for consumer use:
  - `gridx.pricing.schema`     — coerced entity schemas (Component, Interval, Curve)
  - `gridx.pricing.schema.raw` — raw API response schemas"
  (:require [tick.core :as t]
            [malli.core :as m]
            [gridx.pricing.schema.raw :as schema.raw])
  (:import [java.time Duration OffsetDateTime ZoneId ZonedDateTime]
           [java.time.format DateTimeFormatter]))

;; ---------------------------------------------------------------------------
;; Parsing helpers
;; ---------------------------------------------------------------------------

(def ^:private ^DateTimeFormatter gridx-timestamp-formatter
  (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ssZ"))

(defn- parse-zoned-datetime
  "Parse a GridX timestamp string (e.g. '2026-03-08T00:00:00-0800') into a
  `ZonedDateTime` in `zone`. The offset embedded in the string fixes the
  instant; `.atZoneSameInstant` re-expresses that instant in `zone`,
  yielding a value that knows the zone's DST rules."
  ^ZonedDateTime [^String s ^ZoneId zone]
  (-> (OffsetDateTime/parse s gridx-timestamp-formatter)
      (.atZoneSameInstant zone)))

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
  (m/explain schema.raw/PricingResponse body))

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
  end time from its start. `zone` is the `ZoneId` to express timestamps in.

  The entity map carries :tick/beginning and :tick/end directly (as
  ZonedDateTimes), making it a tick interval usable with Allen's interval
  algebra (t/relation, t/contains?, etc.) without unwrapping.

  Prices become BigDecimals. Status strings become namespaced keywords
  (e.g. :gridx.status/final). Attaches the original raw map as :gridx/raw
  metadata."
  [^Duration duration ^ZoneId zone raw]
  (let [components (mapv ->component (:priceComponents raw))
        start (parse-zoned-datetime (:startIntervalTimeStamp raw) zone)
        end (.plus start duration)]
    (-> {:tick/beginning            start
         :tick/end                  end
         :gridx.interval/price      (parse-decimal (:intervalPrice raw))
         :gridx.interval/status     (->keyword-lower "gridx.status" (:priceStatus raw))
         :gridx.interval/components components}
        (with-meta {:gridx/raw raw}))))

(defn ->curve
  "Coerce a raw price curve map into a namespaced Curve.

  `zone` is the `ZoneId` to express timestamps in. The curve header's
  start/end times are parsed using the API's offset and re-expressed in
  `zone` as ZonedDateTimes — preserving the wall-clock time the API meant
  while gaining DST-aware behavior.

  The entity map carries :gridx.curve/start, :gridx.curve/end,
  :tick/beginning, and :tick/end as ZonedDateTimes (the tick keys make
  the curve usable with Allen's interval algebra directly).

  Note: the API reports end time as 23:59:59 (inclusive), while tick
  intervals are half-open [start, end). This means :tick/end is 1 second
  before the last interval's computed end. This is faithful to the API;
  we do not adjust it.

  Attaches the original raw map as :gridx/raw metadata."
  [^ZoneId zone raw]
  (let [header (:priceHeader raw)
        duration (Duration/ofMinutes (:intervalLengthInMinutes header))
        start (parse-zoned-datetime (:startTime header) zone)
        end   (parse-zoned-datetime (:endTime header) zone)]
    (-> {:gridx.curve/name             (:priceCurveName header)
         :gridx.curve/market           (->keyword-lower "gridx.market" (:marketName header))
         :gridx.curve/interval-minutes (:intervalLengthInMinutes header)
         :gridx.curve/currency         (keyword (:settlementCurrency header))
         :gridx.curve/unit             (keyword (:settlementUnit header))
         :gridx.curve/start            start
         :gridx.curve/end              end
         :tick/beginning               start
         :tick/end                     end
         :gridx.curve/record-count     (:recordCount header)
         :gridx.curve/intervals        (mapv (partial ->interval duration zone)
                                             (:priceDetails raw))}
        (with-meta {:gridx/raw raw}))))

(defn curves
  "Extract and coerce price curves from a successful API response.

  This is the main entry point for the coerced layer. Returns a vector
  of Curve maps with namespaced keywords, native types (BigDecimal,
  ZonedDateTime), and tick intervals. Each entity at every level carries
  :gridx/raw metadata with the original API data.

  The 1-arity form reads the zone from `(:gridx/zone response)`, which
  `gridx.client/get-pricing` attaches automatically. The 2-arity form
  takes an explicit `ZoneId`, useful when coercing a hand-built response
  (e.g. in tests) or when overriding the client's configured zone.

  See `raw-curves` for the uncoerced version."
  ([response]
   (let [zone (:gridx/zone response)]
     (when-not zone
       (throw (ex-info "Response is missing :gridx/zone — pass zone explicitly via (curves response zone), or fetch through a client created with :zone"
                       {:response-keys (keys response)})))
     (curves response zone)))
  ([response zone]
   (let [zone-id (if (instance? ZoneId zone) zone (ZoneId/of (str zone)))]
     (mapv (partial ->curve zone-id) (raw-curves response)))))

# clj-gridx

A Clojure client library for the [GridX Pricing API](https://pe-api.gridx.com), providing access to marginal cost pricing data for California utilities.

## Features

- **Spec-driven HTTP client** built on [Martian](https://github.com/oliyh/martian) with the OpenAPI spec (from `gridx-api-specs`) as the single source of truth
- **Two-layer data model**: raw API responses (camelCase, strings) and coerced Clojure entities (namespaced keywords, BigDecimals, Instants)
- **tick intervals** for time periods, enabling [Allen's interval algebra](https://en.wikipedia.org/wiki/Allen%27s_interval_algebra) out of the box
- **Metadata preservation**: every coerced entity carries the original API data as `:gridx/raw` metadata
- **Malli schemas** for both raw and coerced data layers

## Installation

Add to your `deps.edn`:

```clojure
;; TODO: publish coordinates once deployed
{:deps {com.example/clj-gridx {:mvn/version "0.1.0"}}}
```

## Prerequisites

The OpenAPI spec is not bundled in this repo. It is symlinked from the sibling `gridx-api-specs` repository:

```
resources/gridx-pricing-spec -> ../../gridx-api-specs/apis/pricing
```

You must clone [`gridx-api-specs`](https://github.com/grid-coordination/gridx-api-specs) alongside this repo:

```bash
cd repo/
git clone git@github.com:grid-coordination/gridx-api-specs.git
```

Expected layout:

```
repo/
  clj-gridx/           # this repo
  gridx-api-specs/     # OpenAPI specs
    apis/
      pricing/
        openapi.yaml
```

## Quick Start

```clojure
(require '[gridx.client :as client]
         '[gridx.pricing :as pricing])

;; Create a client (defaults to stage API)
(def c (client/create-client))

;; Or target production
(def c (client/create-client {:url client/production-url}))

;; Fetch pricing data
(def resp (client/get-pricing c
            {:utility "PGE"
             :market "DAM"
             :program "CalFUSE"
             :startdate "20260308"
             :enddate "20260308"
             :ratename "EELEC"
             :representativeCircuitId "013532223"}))

;; Check success
(pricing/success? resp)
;=> true

;; Get coerced Clojure entities
(def curves (pricing/curves resp))
```

## Data Model

The library provides two views of the API data:

### Raw Layer

Direct from the JSON — camelCase keys, string values. Useful for debugging or when you need the exact API representation.

```clojure
(pricing/raw-curves resp)
;=> [{:priceHeader {:priceCurveName "PGE-CalFUSE-EELEC-SECONDARY"
;                    :marketName "CAISO-DAM"
;                    :startTime "2026-03-08T00:00:00-0800"
;                    ...}
;     :priceDetails [{:startIntervalTimeStamp "2026-03-08T00:00:00-0800"
;                     :intervalPrice "0.032176"
;                     :priceStatus "Final"
;                     :priceComponents [{:component "cld"
;                                        :intervalPrice "0.000351"
;                                        :priceType "distribution"} ...]}
;                    ...]}]
```

### Coerced Layer

Idiomatic Clojure — namespaced keywords, native types, tick intervals.

```clojure
(first (pricing/curves resp))
;=> #:gridx.curve{:name "PGE-CalFUSE-EELEC-SECONDARY"
;                  :market :gridx.market/caiso-dam
;                  :interval-minutes 60
;                  :currency :USD
;                  :unit :kWh
;                  :start #time/offset-date-time "2026-03-08T00:00-08:00"
;                  :end #time/offset-date-time "2026-03-08T23:59:59-07:00"
;                  :period #:tick{:beginning #time/instant "2026-03-08T08:00:00Z"
;                                 :end #time/instant "2026-03-09T06:59:59Z"}
;                  :record-count 23
;                  :intervals [...]}
```

#### Curve

| Key | Type | Description |
|-----|------|-------------|
| `:gridx.curve/name` | `String` | Price curve name (e.g. `"PGE-CalFUSE-EELEC-SECONDARY"`) |
| `:gridx.curve/market` | `Keyword` | Market identifier (e.g. `:gridx.market/caiso-dam`) |
| `:gridx.curve/interval-minutes` | `int` | Interval length: 15 or 60 |
| `:gridx.curve/currency` | `Keyword` | Settlement currency (e.g. `:USD`) |
| `:gridx.curve/unit` | `Keyword` | Settlement unit (e.g. `:kWh`) |
| `:gridx.curve/start` | `OffsetDateTime` | Curve start in market-local time |
| `:gridx.curve/end` | `OffsetDateTime` | Curve end in market-local time |
| `:gridx.curve/period` | tick interval | Curve span as UTC `{:tick/beginning :tick/end}` |
| `:gridx.curve/record-count` | `int` | Number of intervals |
| `:gridx.curve/intervals` | `vector` | Vector of Interval maps |

#### Interval

| Key | Type | Description |
|-----|------|-------------|
| `:gridx.interval/period` | tick interval | Time period as `{:tick/beginning :tick/end}` (UTC Instants) |
| `:gridx.interval/price` | `BigDecimal` | Total interval price in currency/unit |
| `:gridx.interval/status` | `Keyword` | `:gridx.status/final` or `:gridx.status/preliminary` |
| `:gridx.interval/components` | `vector` | Vector of Component maps |

#### Component

| Key | Type | Description |
|-----|------|-------------|
| `:gridx.component/name` | `Keyword` | `:gridx.component/cld`, `:gridx.component/mec`, or `:gridx.component/mgcc` |
| `:gridx.component/price` | `BigDecimal` | Component price |
| `:gridx.component/type` | `Keyword` | `:gridx.price-type/generation` or `:gridx.price-type/distribution` |

### Type Coercion Summary

| Raw (API) | Coerced (Clojure) | Example |
|-----------|-------------------|---------|
| Timestamp string | `java.time.Instant` (UTC) | `"2026-03-08T00:00:00-0800"` → `#time/instant "2026-03-08T08:00:00Z"` |
| Timestamp string (curve bounds) | `java.time.OffsetDateTime` | `"2026-03-08T00:00:00-0800"` → `#time/offset-date-time "2026-03-08T00:00-08:00"` |
| Decimal string | `BigDecimal` | `"0.032176"` → `0.032176M` |
| Enum string | Namespaced keyword | `"Final"` → `:gridx.status/final` |

## Time Handling

Time representation is chosen by semantics:

- **Interval timestamps** use `Instant` (UTC) — these are point-in-time price observations that must be globally unambiguous
- **Curve start/end** use `OffsetDateTime` — these represent calendar boundaries in the market's local time. The offset conveys market context (e.g., `-08:00` PST vs `-07:00` PDT)

The library **never assumes a timezone**. Offsets cannot be converted to zone IDs without external knowledge (`-08:00` could be US/Pacific, US/Alaska, etc.). If you know the zone, convert explicitly:

```clojure
(.atZoneSameInstant (:gridx.curve/start curve)
                    (java.time.ZoneId/of "America/Los_Angeles"))
```

## Tick Intervals

Price intervals are represented as [tick](https://github.com/juxt/tick) intervals, enabling Allen's interval algebra:

```clojure
(require '[tick.core :as t]
         '[tick.alpha.interval :as t.i])

(let [intervals (:gridx.curve/intervals (first curves))
      i1 (:gridx.interval/period (nth intervals 0))
      i2 (:gridx.interval/period (nth intervals 1))
      i3 (:gridx.interval/period (nth intervals 2))]

  (t.i/meets? i1 i2)      ;=> true  (contiguous: i1 end = i2 start)
  (t.i/precedes? i1 i3)   ;=> true  (i1 comes before i3 with gap)
  (t.i/overlaps? i1 i2)   ;=> false (no overlap)
  (t.i/relation i1 i2)    ;=> :meets

  ;; Access interval boundaries
  (t/beginning i1)         ;=> #time/instant "2026-03-08T08:00:00Z"
  (t/end i1))              ;=> #time/instant "2026-03-08T09:00:00Z"
```

> **Note on curve period vs intervals:** The GridX API reports curve end time as `23:59:59` (inclusive convention), while tick intervals are half-open `[start, end)`. This means the curve's `:gridx.curve/period` ends 1 second before the last interval's computed end time. The library preserves the API's value faithfully and does not adjust for this difference.

## Metadata

Every coerced entity preserves the original API data as metadata, accessible via `:gridx/raw`:

```clojure
;; Get a coerced interval
(def interval (-> curves first :gridx.curve/intervals first))

interval
;=> #:gridx.interval{:period #:tick{:beginning #time/instant "2026-03-08T08:00:00Z"
;                                    :end #time/instant "2026-03-08T09:00:00Z"}
;                     :price 0.032176M
;                     :status :gridx.status/final
;                     :components [#:gridx.component{:name :gridx.component/cld
;                                                    :price 0.000351M
;                                                    :type :gridx.price-type/distribution}
;                                  ...]}

;; Access the original API data
(-> interval meta :gridx/raw)
;=> {:startIntervalTimeStamp "2026-03-08T00:00:00-0800"
;    :intervalPrice "0.032176"
;    :priceStatus "Final"
;    :priceComponents [{:component "cld"
;                       :intervalPrice "0.000351"
;                       :priceType "distribution"} ...]}
```

This works at every level — curves, intervals, and components all carry their raw data.

## Validation

Malli schemas are provided for both layers. Validate a raw API response body:

```clojure
(pricing/validate-raw (:body resp))
;=> nil  (success)

;; On validation failure, returns a Malli explanation map
(pricing/validate-raw {:bad "data"})
;=> {:errors [...], :schema [...], ...}
```

## API Reference

### `gridx.client`

| Function | Description |
|----------|-------------|
| `create-client` | Create an API client. Options: `:url`, `:spec-path` |
| `get-pricing` | Fetch pricing data. Returns raw HTTP response |
| `routes` | List available API route names |
| `stage-url` | Stage API base URL |
| `production-url` | Production API base URL |

### `gridx.pricing`

| Function | Description |
|----------|-------------|
| `success?` | Check if an API response indicates success |
| `raw-curves` | Extract raw (uncoerced) curves from response |
| `curves` | Extract and coerce curves into Clojure entities |
| `validate-raw` | Validate response body against raw Malli schema |
| `->gridx-date` | Convert a date to GridX YYYYMMDD format |
| `->component` | Coerce a raw component map |
| `->interval` | Coerce a raw price detail map (requires duration) |
| `->curve` | Coerce a raw price curve map |

## REPL Session Example

A complete REPL session demonstrating the full workflow:

```clojure
;; Setup
(require '[gridx.client :as client]
         '[gridx.pricing :as pricing]
         '[tick.core :as t]
         '[tick.alpha.interval :as t.i])

(def c (client/create-client))

;; Fetch today's EELEC pricing
(def resp (client/get-pricing c
            {:utility "PGE"
             :market "DAM"
             :program "CalFUSE"
             :startdate (pricing/->gridx-date (t/today))
             :enddate   (pricing/->gridx-date (t/today))
             :ratename "EELEC"
             :representativeCircuitId "013532223"}))

(pricing/success? resp)
;=> true

;; Coerce to Clojure entities
(def curves (pricing/curves resp))
(def curve (first curves))

;; Inspect the curve header
(dissoc curve :gridx.curve/intervals)
;=> #:gridx.curve{:name "PGE-CalFUSE-EELEC-SECONDARY"
;                  :market :gridx.market/caiso-dam
;                  :interval-minutes 60
;                  :currency :USD
;                  :unit :kWh
;                  :start #time/offset-date-time "2026-03-08T00:00-08:00"
;                  :end #time/offset-date-time "2026-03-08T23:59:59-07:00"
;                  :period #:tick{:beginning #time/instant "2026-03-08T08:00:00Z"
;                                 :end #time/instant "2026-03-09T06:59:59Z"}
;                  :record-count 23}

;; Look at a specific interval
(def intervals (:gridx.curve/intervals curve))
(nth intervals 9)
;=> #:gridx.interval{:period #:tick{:beginning #time/instant "2026-03-08T17:00:00Z"
;                                    :end #time/instant "2026-03-08T18:00:00Z"}
;                     :price -0.014427M
;                     :status :gridx.status/final
;                     :components
;                     [#:gridx.component{:name :gridx.component/cld
;                                        :price 0.000679M
;                                        :type :gridx.price-type/distribution}
;                      #:gridx.component{:name :gridx.component/mec
;                                        :price -0.015106M
;                                        :type :gridx.price-type/generation}
;                      #:gridx.component{:name :gridx.component/mgcc
;                                        :price 0.000000M
;                                        :type :gridx.price-type/generation}]}

;; Find negative price hours (solar oversupply!)
(->> intervals
     (filter #(neg? (:gridx.interval/price %)))
     (mapv (fn [i]
             {:begin (t/beginning (:gridx.interval/period i))
              :price (:gridx.interval/price i)})))
;=> [{:begin #time/instant "2026-03-08T16:00:00Z", :price -0.011987M}
;    {:begin #time/instant "2026-03-08T17:00:00Z", :price -0.014427M}
;    ...]

;; Interval algebra
(t.i/meets? (:gridx.interval/period (nth intervals 0))
            (:gridx.interval/period (nth intervals 1)))
;=> true

;; Access raw API data from any coerced entity
(-> (nth intervals 0) meta :gridx/raw :startIntervalTimeStamp)
;=> "2026-03-08T00:00:00-0800"
```

## Development

### Start nREPL

```bash
clojure -M:nrepl
# nREPL running on port 7888
```

### Dev helpers

The `dev/user.clj` namespace provides REPL convenience functions:

```clojure
(start!)                                                       ; init client (stage)
(start! {:url client/production-url})                           ; init client (prod)
(fetch-pricing "EELEC" "013532223" "20260308" "20260308")       ; quick fetch
```

### Run tests

```bash
clojure -M:test -m kaocha.runner
```

## License

Copyright (c) 2026. All rights reserved.

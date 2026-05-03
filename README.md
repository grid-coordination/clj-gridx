# clj-gridx

[![Clojars Project](https://img.shields.io/clojars/v/energy.grid-coordination/clj-gridx.svg)](https://clojars.org/energy.grid-coordination/clj-gridx)
[![md-docs](https://img.shields.io/badge/md--docs-included-green)](https://github.com/dcj/codox-md)
[![build-provenance](https://img.shields.io/badge/build--provenance-included-blue)](https://github.com/dcj/build-provenance)

A Clojure client library for the [GridX Pricing API](https://pe-api.gridx.com), providing access to marginal cost pricing data for California utilities (PG&E and SCE). Built on a [non-official OpenAPI spec](https://github.com/grid-coordination/gridx-api-specs#disclaimer) derived from GridX's public developer docs.

## Features

- **Multi-utility support**: PG&E and SCE via parallel `gridx.pge.client` and `gridx.sce.client` namespaces
- **Spec-driven HTTP client** built on [Martian](https://github.com/oliyh/martian) with bundled OpenAPI specs as the single source of truth
- **Two-layer data model**: raw API responses (camelCase, strings) and coerced Clojure entities (namespaced keywords, BigDecimals, ZonedDateTimes)
- **Per-instance `:zone`** — every coerced timestamp is a `ZonedDateTime` in the configured `ZoneId`, with DST-aware behavior across spring-forward / fall-back days
- **tick intervals** for time periods, enabling [Allen's interval algebra](https://en.wikipedia.org/wiki/Allen%27s_interval_algebra) out of the box
- **Metadata preservation**: every coerced entity carries the original API data as `:gridx/raw` metadata
- **Malli schemas** for both raw and coerced data layers

## Installation

Add to your `deps.edn`:

```clojure
{:deps {energy.grid-coordination/clj-gridx {:mvn/version "0.4.1"}}}
```

## Quick Start

### PG&E

```clojure
(require '[gridx.pge.client :as pge]
         '[gridx.pricing :as pricing])

;; Create a PG&E client (defaults to stage API + America/Los_Angeles zone)
(def c (pge/create-client))

;; Or target production / override the zone
(def c (pge/create-client {:url  pge/production-url
                           :zone "America/Los_Angeles"}))

;; Fetch pricing data — utility/market/program are filled in automatically
(def resp (pge/get-pricing c
            {:startdate "20260308"
             :enddate "20260308"
             :ratename "EELEC"
             :representativeCircuitId "013532223"}))

(pricing/success? resp)  ;=> true
(pricing/curves resp)    ;=> vector of coerced Curve maps (ZonedDateTimes in client zone)
```

### SCE

```clojure
(require '[gridx.sce.client :as sce]
         '[gridx.pricing :as pricing])

;; Create an SCE client (defaults to stage API + America/Los_Angeles zone)
(def c (sce/create-client))

;; Fetch pricing data
(def resp (sce/get-pricing c
            {:startdate "20250701"
             :enddate "20250701"
             :ratename "TOU-EV-9S"
             :representativeCircuitId "System"}))

(pricing/success? resp)  ;=> true
(pricing/curves resp)    ;=> vector of coerced Curve maps (ZonedDateTimes in client zone)
```

### Shared Client

The utility-specific namespaces wrap the shared `gridx.client` namespace, which can also be used directly:

```clojure
(require '[gridx.client :as client])

(def c (client/create-client {:url       "https://pge-pe-api.gridx.com/stage/v1"
                              :spec-path "gridx-pricing-spec/pge/openapi.yaml"
                              :zone      "America/Los_Angeles"}))

(client/get-pricing c {:utility "PGE" :market "DAM" :program "CalFUSE" ...})
```

`:zone` is required by the shared `create-client` (the per-utility wrappers default it to `America/Los_Angeles` for you). It accepts a `java.time.ZoneId` or a zone-id string, and it flows from the client onto each response (as `:gridx/zone`) where the coercion layer reads it.

## Utility Differences

The coercion layer (`gridx.pricing`) is shared — both utilities produce the same Clojure entity shape. The differences are in the API parameters and price component vocabulary:

| Aspect | PG&E | SCE |
|--------|------|-----|
| Client namespace | `gridx.pge.client` | `gridx.sce.client` |
| Circuit parameter | `:representativeCircuitId` (9-digit feeder ID; see [circuit lookup](#pge-circuit-id-lookup)) | `:representativeCircuitId` (substation name, e.g. `"System"`) |
| Rate names | AG-A1, B6, EELEC, EV2AS, ... | TOU-GS-1, TOU-EV-9S, TOU-PRIME, ... |
| Components per interval | 3 (cld, mec, mgcc) | 8 (abank, bbank, circuitpricecurve, mec, nbc, ppf, ramp, transmissionpricecurve) |
| Price types | generation, distribution | generation, distribution, nonbypassablecharge, transmission |
| CCA support | Yes (optional `:cca` param) | No |
| Data available from | 2024-06-01 | 2025-07-01 |

## PG&E Circuit ID Lookup

PG&E's `representativeCircuitId` is a 9-digit distribution feeder identifier. PG&E presents customers with a dropdown of these opaque numbers with no indication of what or where they are. The `gridx.pge.circuits` namespace maps all 98 known circuit IDs to their substation locations.

```clojure
(require '[gridx.pge.circuits :as circuits])

;; Find circuit IDs by substation name (case-insensitive)
(circuits/find-circuits "mountain view")
;=> (["082031112" {:region "South Bay and Central Coast"
;                   :division "De Anza"
;                   :substation "MOUNTAIN VIEW"
;                   :feeder "1112"
;                   :in-gridx-enum? true}])

;; Look up a specific circuit
(circuits/circuit-location "013532223")
;=> {:region "Bay Area", :division "Diablo", :substation "LAKEWOOD", ...}

;; Browse by region
(keys (circuits/circuits-by-region))
;=> ("Bay Area" "Central Valley" "North Coast" ...)

;; Only circuits confirmed in the GridX API (59 of 98)
(count (circuits/gridx-circuits))  ;=> 59
```

Data derived from the [PG&E 2022 Grid Needs Assessment](https://docs.cpuc.ca.gov/PublishedDocs/Efile/G000/M496/K629/496629893.PDF) (CPUC filing 496629893, Appendix D) and the [Priicer community cross-reference](https://forum.priicer.com/t/pg-e-dynamic-pilot-california/33).

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

Idiomatic Clojure — namespaced keywords, native types, tick intervals. The same shape for both PG&E and SCE. All timestamps are `ZonedDateTime` values in the client's configured `:zone`.

```clojure
(first (pricing/curves resp))
;=> #:gridx.curve{:name "PGE-CalFUSE-EELEC-SECONDARY"
;                  :market :gridx.market/caiso-dam
;                  :interval-minutes 60
;                  :currency :USD
;                  :unit :kWh
;                  :start #time/zoned-date-time "2026-03-08T00:00-08:00[America/Los_Angeles]"
;                  :end   #time/zoned-date-time "2026-03-08T23:59:59-07:00[America/Los_Angeles]"
;                  :period #:tick{:beginning #time/zoned-date-time "2026-03-08T00:00-08:00[America/Los_Angeles]"
;                                 :end       #time/zoned-date-time "2026-03-08T23:59:59-07:00[America/Los_Angeles]"}
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
| `:gridx.curve/start` | `ZonedDateTime` | Curve start in the configured `:zone` |
| `:gridx.curve/end` | `ZonedDateTime` | Curve end in the configured `:zone` |
| `:tick/beginning` | `ZonedDateTime` | Curve start (tick interval key, same value as `:gridx.curve/start`) |
| `:tick/end` | `ZonedDateTime` | Curve end (tick interval key, same value as `:gridx.curve/end`) |
| `:gridx.curve/record-count` | `int` | Number of intervals |
| `:gridx.curve/intervals` | `vector` | Vector of Interval maps |

#### Interval

| Key | Type | Description |
|-----|------|-------------|
| `:tick/beginning` | `ZonedDateTime` | Interval start in the configured `:zone` |
| `:tick/end` | `ZonedDateTime` | Interval end in the configured `:zone` (start + interval length) |
| `:gridx.interval/price` | `BigDecimal` | Total interval price in currency/unit |
| `:gridx.interval/status` | `Keyword` | `:gridx.status/final` or `:gridx.status/preliminary` |
| `:gridx.interval/components` | `vector` | Vector of Component maps |

#### Component

| Key | Type | Description |
|-----|------|-------------|
| `:gridx.component/name` | `Keyword` | e.g. `:gridx.component/cld`, `:gridx.component/mec`, `:gridx.component/abank` |
| `:gridx.component/price` | `BigDecimal` | Component price |
| `:gridx.component/type` | `Keyword` | e.g. `:gridx.price-type/generation`, `:gridx.price-type/distribution`, `:gridx.price-type/transmission` |

**PG&E components:** cld (distribution), mec (generation), mgcc (generation)

**SCE components:** abank (distribution), bbank (distribution), circuitpricecurve (distribution), mec (generation), nbc (nonbypassablecharge), ppf (generation), ramp (generation), transmissionpricecurve (transmission)

### Type Coercion Summary

| Raw (API) | Coerced (Clojure) | Example (zone = America/Los_Angeles) |
|-----------|-------------------|---------|
| Timestamp string | `java.time.ZonedDateTime` in client `:zone` | `"2026-03-08T00:00:00-0800"` → `#time/zoned-date-time "2026-03-08T00:00-08:00[America/Los_Angeles]"` |
| Decimal string | `BigDecimal` | `"0.032176"` → `0.032176M` |
| Enum string | Namespaced keyword | `"Final"` → `:gridx.status/final` |

## Time Handling

Every coerced timestamp is a `java.time.ZonedDateTime` in the `:zone` configured on the client. The parser reads the API's offset (e.g. `-0800`), uses it to fix the underlying instant, and then `.atZoneSameInstant`s into the configured zone — so the wall-clock time the API meant is preserved while the value gains the zone's DST rules for any subsequent arithmetic.

```clojure
;; Default for the per-utility wrappers:
(pge/create-client)                                ;; :zone defaults to America/Los_Angeles
(sce/create-client {:zone "America/Los_Angeles"})  ;; explicit, same default

;; The shared client requires :zone:
(client/create-client {:url "..." :spec-path "..." :zone "UTC"})
```

The zone flows from the client onto each response (as `:gridx/zone`) where `pricing/curves` reads it. You can also pass a zone explicitly to bypass that flow — useful for re-coercing a hand-built response or for tests:

```clojure
(pricing/curves resp)                          ;; uses (:gridx/zone resp)
(pricing/curves resp "America/Los_Angeles")    ;; explicit override
```

### Daylight Saving Time

Because the coerced values are `ZonedDateTime` in a real zone, DST transitions are handled correctly:

- On **spring-forward** day, the wall clock skips from 02:00 PST to 03:00 PDT. An interval at 01:00 PST keeps the `-08:00` offset; the next interval at 03:00 PDT comes back with `-07:00`. Adding a 1-hour `Duration` to the 01:00 PST `ZonedDateTime` produces a value that is the same instant as 03:00 PDT — i.e. it crosses the gap correctly.
- On **fall-back** day, the wall clock repeats the 01:00 hour. The API serializes each repeated hour with its own offset (`-07:00` for the first pass, `-08:00` for the second), and the parser preserves that distinction.

Verified against the live PG&E stage API for hourly intervals:

| Transition day | Direction | Hourly intervals returned | Offsets present |
|---|---|---|---|
| 2026-03-08 | Spring-forward (PST → PDT) | 23 | `-08:00`, `-07:00` |
| 2025-11-02 | Fall-back (PDT → PST) | 25 | `-07:00`, `-08:00` |

This library does not synthesize or drop intervals; it coerces what the API returns. (See `pge-live-dst-spring-forward-test` and `pge-live-dst-fall-back-test` in the integration test suite for the running assertions.)

### Zone misconfiguration

If you create a client with a `:zone` whose offset doesn't match what the API serializes for a given timestamp, the parser throws `ExceptionInfo` early with the wire string, wire offset, configured zone, and the offset that zone would produce — so a misconfigured `:zone` (e.g. `Asia/Tokyo` configured against a Pacific-serving API) surfaces immediately rather than silently re-zoning the wall clock.

## Tick Intervals

Both Curve and Interval entities carry `:tick/beginning` and `:tick/end` directly, making them [tick](https://github.com/juxt/tick) intervals usable with Allen's interval algebra:

```clojure
(require '[tick.core :as t])

(let [intervals (:gridx.curve/intervals (first curves))
      i1 (nth intervals 0)
      i2 (nth intervals 1)
      i3 (nth intervals 2)]

  (t/relation i1 i2)      ;=> :meets
  (t/relation i1 i3)      ;=> :precedes

  ;; Access interval boundaries directly
  (:tick/beginning i1)     ;=> #time/zoned-date-time "2026-03-08T00:00-08:00[America/Los_Angeles]"
  (:tick/end i1))          ;=> #time/zoned-date-time "2026-03-08T01:00-08:00[America/Los_Angeles]"
```

> **Note on curve tick/end:** The GridX API reports curve end time as `23:59:59` (inclusive convention), while tick intervals are half-open `[start, end)`. This means the curve's `:tick/end` is 1 second before the last interval's computed end time. The library preserves the API's value faithfully and does not adjust for this difference.

## Metadata

Every coerced entity preserves the original API data as metadata, accessible via `:gridx/raw`:

```clojure
;; Get a coerced interval
(def interval (-> curves first :gridx.curve/intervals first))

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

## Schemas

Malli schemas are published in dedicated namespaces so consumers can use them for validation, generative testing, or documentation without pulling in coercion machinery.

### `gridx.pricing.schema` — Coerced entities (the public contract)

```clojure
(require '[gridx.pricing.schema :as schema]
         '[malli.core :as m])

;; Validate a coerced curve
(m/validate schema/Curve (first curves))
;=> true

;; Available schemas: Component, Interval, Curve
```

### `gridx.pricing.schema.raw` — Raw API shapes

Most consumers won't need these. They mirror the JSON exactly and are primarily useful for boundary validation.

```clojure
(require '[gridx.pricing.schema.raw :as schema.raw])

;; Validate a raw API response body
(pricing/validate-raw (:body resp))
;=> nil  (success — returns nil on valid, Malli explanation map on failure)

;; Available schemas: PriceComponent, PriceDetail, PriceHeader,
;;                    PriceCurve, ResponseMeta, PricingResponse
```

## API Reference

### `gridx.pge.client` — PG&E

| Function | Description |
|----------|-------------|
| `create-client` | Create a PG&E client. Options: `:url` (default: stage), `:spec-path` |
| `get-pricing` | Fetch PG&E pricing. Fills in utility/market/program. Params: `:startdate`, `:enddate`, `:ratename`, `:representativeCircuitId`, `:cca` (optional) |
| `stage-url` | PG&E stage API base URL |
| `production-url` | PG&E production API base URL |

### `gridx.sce.client` — SCE

| Function | Description |
|----------|-------------|
| `create-client` | Create an SCE client. Options: `:url` (default: stage), `:spec-path` |
| `get-pricing` | Fetch SCE pricing. Fills in utility/market/program. Params: `:startdate`, `:enddate`, `:ratename`, `:representativeCircuitId` |
| `stage-url` | SCE stage API base URL |
| `production-url` | SCE production API base URL |

### `gridx.pge.circuits` — Circuit ID Lookup

| Function | Description |
|----------|-------------|
| `circuit-locations` | Map of all 98 circuit IDs to location info |
| `circuit-location` | Look up location for a circuit ID |
| `find-circuits` | Search by substation name (case-insensitive substring) |
| `circuits-by-region` | Group circuits by PG&E distribution planning region |
| `gridx-circuits` | Return only circuits confirmed in the GridX API |

### `gridx.client` — Shared

| Function | Description |
|----------|-------------|
| `create-client` | Create a client with explicit `:url` and `:spec-path` (both required) |
| `get-pricing` | Fetch pricing data with explicit params. Returns raw HTTP response |
| `routes` | List available API route names |

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

### `gridx.pricing.schema`

Malli schemas for the coerced Clojure entities — the public contract for consumers.

| Schema | Description |
|--------|-------------|
| `Component` | Price component with BigDecimal price and type keyword |
| `Interval` | Price interval with tick period, price, status, and components |
| `Curve` | Complete price curve with header fields and vector of intervals |

### `gridx.pricing.schema.raw`

Malli schemas mirroring the raw JSON API shape. Primarily for boundary validation.

| Schema | Description |
|--------|-------------|
| `PriceComponent` | Raw component (`component`, `intervalPrice`, `priceType`) |
| `PriceDetail` | Raw interval detail with timestamp, price, status, components |
| `PriceHeader` | Raw curve metadata (name, market, times, record count) |
| `PriceCurve` | Raw curve (header + details vector) |
| `ResponseMeta` | HTTP response metadata (code, URLs, body) |
| `PricingResponse` | Top-level API response (meta + data vector) |

## REPL Session Example

A complete REPL session demonstrating the full workflow:

```clojure
;; Setup
(require '[gridx.pge.client :as pge]
         '[gridx.sce.client :as sce]
         '[gridx.pricing :as pricing]
         '[gridx.pricing.schema :as schema]
         '[tick.core :as t]
         '[tick.alpha.interval :as t.i]
         '[malli.core :as m])

;; -- PG&E --
(def pc (pge/create-client))

(def pge-resp (pge/get-pricing pc
                {:startdate (pricing/->gridx-date (t/today))
                 :enddate   (pricing/->gridx-date (t/today))
                 :ratename "EELEC"
                 :representativeCircuitId "013532223"}))

(pricing/success? pge-resp)  ;=> true
(def pge-curves (pricing/curves pge-resp))
(m/validate schema/Curve (first pge-curves))  ;=> true

;; -- SCE --
(def sc (sce/create-client))

(def sce-resp (sce/get-pricing sc
                {:startdate "20250701"
                 :enddate "20250701"
                 :ratename "TOU-EV-9S"
                 :representativeCircuitId "System"}))

(pricing/success? sce-resp)  ;=> true
(def sce-curves (pricing/curves sce-resp))

;; SCE has 8 components per interval
(-> sce-curves first :gridx.curve/intervals first :gridx.interval/components count)
;=> 8

;; Find negative price hours (solar oversupply!)
(->> (:gridx.curve/intervals (first pge-curves))
     (filter #(neg? (:gridx.interval/price %)))
     (mapv (fn [i]
             {:begin (:tick/beginning i)
              :price (:gridx.interval/price i)})))

;; Interval algebra — entities are tick intervals directly
(let [intervals (:gridx.curve/intervals (first pge-curves))]
  (t/relation (nth intervals 0) (nth intervals 1)))
;=> :meets

;; Access raw API data from any coerced entity
(-> (first pge-curves) meta :gridx/raw :priceHeader :priceCurveName)
;=> "PGE-CalFUSE-EELEC-SECONDARY"
```

## Development

### Start nREPL

```bash
clojure -M:nrepl
# nREPL server started on port XXXXX on host localhost
# Port is written to .nrepl-port for automatic discovery
```

### Dev helpers

The `dev/user.clj` namespace provides REPL convenience functions:

```clojure
(start!)                                                           ; init both clients
(start-pge!)                                                       ; init PG&E client only
(start-sce!)                                                       ; init SCE client only
(fetch-pge-pricing "EELEC" "013532223" "20260308" "20260308")      ; PG&E quick fetch
(fetch-sce-pricing "TOU-EV-9S" "System" "20250701" "20250701")    ; SCE quick fetch
```

### Run tests

```bash
# Unit tests (offline, uses bundled sample JSON)
clojure -M:test -m kaocha.runner

# Integration tests (requires network access to pe-api.gridx.com)
clojure -M:test-integration
```

Unit tests validate schema conformance and coercion logic against sample response files. Integration tests hit the live stage API and verify response structure, component names/counts, type coercion, and metadata preservation for both PG&E and SCE — without asserting specific price values.

## Contributing

Issues, Discussions, and pull requests are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) for the workflow (and the dev commands: tests, integration tests, lint, nREPL). In short:

- **Questions, API/design discussion, GridX behavior gaps** → [Discussions](https://github.com/grid-coordination/clj-gridx/discussions)
- **Confirmed bugs, coercion/schema fixes, doc errors** → [Issues](https://github.com/grid-coordination/clj-gridx/issues)
- **Patches** → pull requests; please open a Discussion or Issue first for non-trivial changes (new utility support, new endpoints, new schema fields, new coercion behavior)

## License

[MIT License](LICENSE) — Copyright (c) 2026 Clark Communications Corporation

# Clojure API Entity Pattern

A pattern for wrapping external APIs in idiomatic Clojure, developed for clj-gridx and applicable to any API client library (e.g., clj-oa3, clj-caiso).

## Overview

Two layers over the raw HTTP response, connected by coercion functions that preserve the original data via metadata.

```
HTTP JSON ──► Raw EDN (camelCase, strings) ──► Coerced Entities (namespaced, typed)
                                                    ▲
                                                    │ metadata: {:ns/raw <original>}
```

## Layer 1: Raw

Preserve the API response as-is. The JSON-to-EDN conversion (via Cheshire, data.json, Hato, etc.) gives you maps with camelCase string keys/values.

```clojure
;; What the API returns (after JSON parse)
{:startIntervalTimeStamp "2026-03-08T00:00:00-0800"
 :intervalPrice "0.032176"
 :priceStatus "Final"
 :priceComponents [{:component "cld" :intervalPrice "0.000351" ...} ...]}
```

### Raw Malli schemas

Define schemas that mirror the JSON shape exactly. Use these for **boundary validation** — confirming the API returned what you expected.

```clojure
(def RawPriceDetail
  [:map
   [:startIntervalTimeStamp :string]
   [:intervalPrice :string]
   [:priceStatus :string]
   [:priceComponents [:vector RawPriceComponent]]])
```

### Raw accessor functions

```clojure
(defn raw-curves [response] (get-in response [:body :data]))
(defn validate-raw [body] (m/explain RawPricingResponse body))
(defn success? [response] ...)
```

## Layer 2: Coerced Entities

Transform raw data into idiomatic Clojure values:

| Raw type | Coerced type | Example |
|----------|--------------|---------|
| String timestamp | `java.time.Instant` (UTC) | `"2026-03-08T00:00:00-0800"` → `2026-03-08T08:00:00Z` |
| Time period | tick interval (`:tick/beginning`, `:tick/end`) | start + duration → `{:tick/beginning instant :tick/end instant}` |
| String decimal | `BigDecimal` | `"0.032176"` → `0.032176M` |
| String enum | Namespaced keyword | `"Final"` → `:gridx.status/final` |
| camelCase key | Namespaced keyword | `:intervalPrice` → `:gridx.interval/price` |

### Namespaced keywords

Use a consistent namespace hierarchy:

```clojure
:gridx.interval/timestamp      ; entity.field
:gridx.component/mec           ; entity.enum-value
:gridx.status/final            ; domain.enum-value
:gridx.price-type/generation   ; domain.enum-value
```

### Coerced Malli schemas

```clojure
(def Interval
  [:map
   [:gridx.interval/period [:map [:tick/beginning inst?] [:tick/end inst?]]]
   [:gridx.interval/price decimal?]
   [:gridx.interval/status [:enum :gridx.status/final :gridx.status/preliminary]]
   [:gridx.interval/components [:vector Component]]])
```

### Computed values

Add derived values as regular keys during coercion when they provide genuinely new information. Prefer this over protocols or lazy computation for cheap, always-useful derivations.

**Caveat:** Don't add a "computed" value that duplicates something already in the raw data. If the API already provides a total, don't re-sum the components — that's a validation concern, not a data field.

```clojure
;; Good: genuinely derived value not in the raw data
(defn ->daily-summary [intervals]
  {:daily/peak-price (apply max (map :interval/price intervals))
   :daily/min-price  (apply min (map :interval/price intervals))})

;; Bad: re-computing something the API already provides
;; :interval/component-total when :interval/price already exists
```

## Metadata Convention

Every coerced entity attaches the original raw data as metadata:

```clojure
(-> coerced-map
    (with-meta {:gridx/raw raw}))
```

This enables:
- **Debugging**: always see what the API actually returned
- **Round-tripping**: reconstruct the raw form if needed
- **Tool integration**: Portal, Morse, Reveal can display raw alongside coerced

```clojure
;; Access raw data from any coerced entity
(-> interval meta :gridx/raw :startIntervalTimeStamp)
;=> "2026-03-08T00:00:00-0800"
```

## Time Handling

Choose the type based on what the timestamp **means**, not just what the API sends:

### `java.time.Instant` (UTC) — for point-in-time events

Use for timestamps that represent "when something happened/applies globally": interval timestamps, trade times, meter readings.

```
API string "-0800"  →  OffsetDateTime (parse)  →  Instant (store)
```

No offsets, no zones, no ambiguity. Consumer provides `ZoneId` for display: `(.atZone instant zone-id)`.

### `java.time.OffsetDateTime` — for calendar boundaries

Use for timestamps that represent "a date/time in the market's local context": curve start/end times, trading day boundaries, delivery periods.

```
API string "-0800"  →  OffsetDateTime (store as-is)
```

Preserves the offset from the API, which conveys market-local meaning (e.g., midnight Pacific). An offset like `-08:00` cannot be converted to a `ZonedDateTime` without assuming a zone — multiple zones share the same offset. Don't assume; let the consumer assert the zone if they know it:

```clojure
(.atZoneSameInstant offset-dt (ZoneId/of "America/Los_Angeles"))
```

### `tick.alpha.interval` — for time periods

Use for data that represents a span of time (price intervals, delivery periods). Tick intervals are plain maps (`{:tick/beginning instant :tick/end instant}`) and enable Allen's interval algebra: `meets?`, `precedes?`, `overlaps?`, `concur`, etc.

```clojure
(require '[tick.alpha.interval :as t.i])

;; Each interval carries its own period — don't assume uniform lengths
(defn ->interval [^Duration duration raw]
  (let [start (parse-instant (:startTime raw))
        end   (.plus start duration)]
    {:my.interval/period (t.i/new-interval start end) ...}))

;; Interval algebra works immediately
(t.i/meets? (:period i1) (:period i2))    ;=> true (contiguous)
(t.i/precedes? (:period i1) (:period i3)) ;=> true (gap between)
```

### General rules

- **Never assume a timezone** in library code — the API gives offsets, not zones
- Raw timestamp strings always available via `:ns/raw` metadata
- Watch for non-standard offset formats (e.g., `-0800` vs `-08:00`); use a custom `DateTimeFormatter` as needed
- The library must work globally, not just for one market's timezone

## When to Use Protocols

### Plain functions (default)

For operations on known data shapes, use regular functions:

```clojure
(defn peak-price [curve]
  (apply max (map :gridx.interval/price (:gridx.curve/intervals curve))))
```

### `:extend-via-metadata` protocols (when polymorphism is needed)

Use when **multiple data sources** must support the **same operations** with **different implementations**:

```clojure
(defprotocol PriceSource
  :extend-via-metadata true
  (price-at [this timestamp])
  (available-range [this]))
```

Each source's coercion attaches its own implementation:

```clojure
(defn ->gridx-curve [raw]
  (-> {:source/type :gridx ...}
      (with-meta {`price-at (fn [this ts] ...)
                  `available-range (fn [this] ...)})))

(defn ->caiso-curve [raw]
  (-> {:source/type :caiso ...}
      (with-meta {`price-at (fn [this ts] ...)    ; different impl
                  `available-range (fn [this] ...)})))
```

**Don't reach for this prematurely.** Start with plain functions; refactor to protocols when you have two concrete sources that need polymorphism.

## datafy/nav

Consider `clojure.datafy/nav` when:
- The API response is **opaque** (not already pure data)
- There are **lazy relationships** to navigate (foreign keys, pagination, related endpoints)
- You're building **interactive exploration** tooling

For pure JSON→EDN responses with no lazy navigation, the metadata convention above is sufficient. Revisit when adding related endpoints (e.g., navigating from a circuit ID to circuit details).

## deft

[deft](https://github.com/sstraust/deft) provides `defrecord`-like constructors backed by plain maps + Malli schemas + multimethod dispatch. Consider when entities need **behavioral polymorphism** (protocol implementations, multimethod dispatch). Skip for pure data entities.

## Namespace Organization

Put schemas in their own namespaces, separate from coercion logic. Consumers can depend on schemas for validation, generative testing, or documentation without pulling in the transformation machinery.

```
mylib.pricing              ;; coercion functions, API helpers
mylib.pricing.schema       ;; coerced entity schemas (the public contract)
mylib.pricing.schema.raw   ;; raw API schemas (boundary validation)
```

Most consumers only need the coerced schemas. The raw schemas are an implementation detail — useful at the boundary but rarely needed by downstream code.

## Checklist for Applying This Pattern

1. **Define raw Malli schemas** mirroring the JSON shape (in `schema.raw` namespace)
2. **Write coercion functions** (`->entity`) that:
   - Convert strings to native types (Instant, BigDecimal, keywords)
   - Use namespaced keywords
   - Compute derived values
   - Attach `{:ns/raw original}` metadata
3. **Define coerced Malli schemas** for the nice entities (in `schema` namespace)
4. **Provide both layers**: `raw-things` for the raw data, `things` for coerced
5. **Keep raw accessor functions** (success?, validate-raw) — the raw layer never goes away
6. **Use Instant for time** — parse offsets/zones, store UTC
7. **Start with plain functions** for computed values and operations
8. **Add protocols** only when polymorphism is concretely needed

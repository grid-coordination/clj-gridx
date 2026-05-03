(ns gridx.sce.client
  "SCE-specific GridX Pricing API client.

  Wraps `gridx.client` with SCE defaults: utility, market, program,
  API URLs, and zone (`America/Los_Angeles`). The OpenAPI spec at
  `gridx-pricing-spec/sce/openapi.yaml` defines the available rate
  schedules and circuit names.

  Required params for `get-pricing`:
    :startdate             - \"YYYYMMDD\" (earliest 20250701)
    :enddate               - \"YYYYMMDD\" (max ~2 weeks span)
    :ratename              - SCE rate schedule code (e.g. \"TOU-EV-9S\")
    :representativeCircuitId - substation name (e.g. \"System\", \"Alamitos\")"
  (:require [gridx.client :as client])
  (:import [java.time ZoneId]))

(def default-spec-path "gridx-pricing-spec/sce/openapi.yaml")

(def stage-url "https://pe-api.gridx.com/stage/v1")
(def production-url "https://pe-api.gridx.com/v1")

(def default-zone
  "SCE serves California exclusively; default to `America/Los_Angeles`."
  (ZoneId/of "America/Los_Angeles"))

(defn create-client
  "Create an SCE GridX API client.

  Options:
    :url       - API base URL (default: stage)
    :spec-path - classpath path to OpenAPI YAML (default: bundled SCE spec)
    :zone      - ZoneId or zone-id string (default: America/Los_Angeles)"
  ([] (create-client {}))
  ([{:keys [url spec-path zone]
     :or   {url       stage-url
            spec-path default-spec-path
            zone      default-zone}}]
   (client/create-client {:url url :spec-path spec-path :zone zone})))

(defn get-pricing
  "Fetch SCE pricing data. Fills in utility/market/program defaults.

  Required params:
    :startdate               - \"YYYYMMDD\"
    :enddate                 - \"YYYYMMDD\"
    :ratename                - rate schedule code
    :representativeCircuitId - substation name (e.g. \"System\")"
  [client params]
  (client/get-pricing
   client
   (merge {:utility "SCE" :market "DAM" :program "CalFUSE"}
          params)))

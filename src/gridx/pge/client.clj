(ns gridx.pge.client
  "PG&E-specific GridX Pricing API client.

  Wraps `gridx.client` with PG&E defaults: utility, market, program,
  API URLs, and zone (`America/Los_Angeles`). The OpenAPI spec at
  `gridx-pricing-spec/pge/openapi.yaml` defines the available rate
  schedules and circuit IDs.

  Required params for `get-pricing`:
    :startdate               - \"YYYYMMDD\" (earliest 20240601)
    :enddate                 - \"YYYYMMDD\" (max ~2 weeks span)
    :ratename                - PG&E rate schedule code (e.g. \"EELEC\")
    :representativeCircuitId - 9-digit feeder ID (see gridx.pge.circuits
                               for location lookup)

  Optional params:
    :cca                     - CCA code (e.g. \"AVA\", \"PCE\")

  Use `gridx.pge.circuits/find-circuits` to look up circuit IDs by
  substation name, e.g. (find-circuits \"mountain view\")."
  (:require [gridx.client :as client])
  (:import [java.time ZoneId]))

(def default-spec-path "gridx-pricing-spec/pge/openapi.yaml")

(def stage-url "https://pge-pe-api.gridx.com/stage/v1")
(def production-url "https://pe-api.gridx.com/v1")

(def default-zone
  "PG&E serves California exclusively; default to `America/Los_Angeles`."
  (ZoneId/of "America/Los_Angeles"))

(defn create-client
  "Create a PG&E GridX API client.

  Options:
    :url       - API base URL (default: stage)
    :spec-path - classpath path to OpenAPI YAML (default: bundled PGE spec)
    :zone      - ZoneId or zone-id string (default: America/Los_Angeles)"
  ([] (create-client {}))
  ([{:keys [url spec-path zone]
     :or   {url       stage-url
            spec-path default-spec-path
            zone      default-zone}}]
   (client/create-client {:url url :spec-path spec-path :zone zone})))

(defn get-pricing
  "Fetch PG&E pricing data. Fills in utility/market/program defaults.

  Required params:
    :startdate               - \"YYYYMMDD\"
    :enddate                 - \"YYYYMMDD\"
    :ratename                - rate schedule code
    :representativeCircuitId - 9-digit feeder ID (see gridx.pge.circuits)

  Optional params:
    :cca                     - CCA code"
  [client params]
  (client/get-pricing
   client
   (merge {:utility "PGE" :market "DAM" :program "CalFUSE"}
          params)))

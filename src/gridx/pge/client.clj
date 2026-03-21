(ns gridx.pge.client
  "PG&E-specific GridX Pricing API client.

  Wraps `gridx.client` with PG&E defaults: utility, market, program,
  and API URLs. The OpenAPI spec at `gridx-pricing-spec/pge/openapi.yaml`
  defines the available rate schedules and circuit IDs.

  Required params for `get-pricing`:
    :startdate               - \"YYYYMMDD\" (earliest 20240601)
    :enddate                 - \"YYYYMMDD\" (max ~2 weeks span)
    :ratename                - PG&E rate schedule code (e.g. \"EELEC\")
    :representativeCircuitId - 9-digit string with leading zeros

  Optional params:
    :cca                     - CCA code (e.g. \"AVA\", \"PCE\")"
  (:require [gridx.client :as client]))

(def default-spec-path "gridx-pricing-spec/pge/openapi.yaml")

(def stage-url "https://pge-pe-api.gridx.com/stage/v1")
(def production-url "https://pe-api.gridx.com/v1")

(defn create-client
  "Create a PG&E GridX API client.

  Options:
    :url       - API base URL (default: stage)
    :spec-path - classpath path to OpenAPI YAML (default: bundled PGE spec)"
  ([] (create-client {}))
  ([{:keys [url spec-path]
     :or   {url       stage-url
            spec-path default-spec-path}}]
   (client/create-client {:url url :spec-path spec-path})))

(defn get-pricing
  "Fetch PG&E pricing data. Fills in utility/market/program defaults.

  Required params:
    :startdate               - \"YYYYMMDD\"
    :enddate                 - \"YYYYMMDD\"
    :ratename                - rate schedule code
    :representativeCircuitId - 9-digit circuit ID

  Optional params:
    :cca                     - CCA code"
  [client params]
  (client/get-pricing
   client
   (merge {:utility "PGE" :market "DAM" :program "CalFUSE"}
          params)))

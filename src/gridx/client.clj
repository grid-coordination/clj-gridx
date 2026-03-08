(ns gridx.client
  "GridX Pricing API client.

  Spec-driven HTTP client built on Martian. The OpenAPI spec bundled in
  resources/gridx-pricing-spec/openapi.yaml is the single source of truth
  for endpoint definitions, parameter validation, and response schemas."
  (:require [martian.core :as martian]
            [martian.hato :as martian-hato]
            [clojure.tools.logging :as log]))

;; ---------------------------------------------------------------------------
;; Client creation
;; ---------------------------------------------------------------------------

(def default-spec-path "gridx-pricing-spec/openapi.yaml")

(def stage-url "https://pge-pe-api.gridx.com/stage/v1")
(def production-url "https://pe-api.gridx.com/v1")

(defn create-client
  "Create a GridX API client from the bundled OpenAPI spec.

  Options:
    :url       - API base URL (default: stage)
    :spec-path - path to OpenAPI YAML on classpath (default: bundled spec)"
  ([] (create-client {}))
  ([{:keys [url spec-path]
     :or   {url       stage-url
            spec-path default-spec-path}}]
   (log/info "Creating GridX client" {:url url})
   (-> (martian-hato/bootstrap-openapi
        spec-path
        {:server-url url
         :interceptors (concat
                        [{:name  ::turn-off-exception-throwing
                          :enter (fn [ctx]
                                   (assoc-in ctx [:request :throw-exceptions?] false))}]
                        martian-hato/default-interceptors)})
       (assoc :api-root url))))

;; ---------------------------------------------------------------------------
;; API operations
;; ---------------------------------------------------------------------------

(defn get-pricing
  "Fetch pricing data from the GridX API.

  Required params:
    :utility                 - \"PGE\"
    :market                  - \"DAM\"
    :program                 - \"CalFUSE\"
    :startdate               - \"YYYYMMDD\" (earliest 20240601)
    :enddate                 - \"YYYYMMDD\" (max ~2 weeks span)
    :ratename                - rate schedule code (e.g. \"EELEC\")
    :representativeCircuitId - 9-digit string with leading zeros

  Optional params:
    :cca                     - CCA code (e.g. \"AVA\", \"PCE\")

  Returns the raw HTTP response map {:status :body :headers}."
  [client params]
  (martian/response-for client :get-pricing params))

;; ---------------------------------------------------------------------------
;; Convenience
;; ---------------------------------------------------------------------------

(defn routes
  "List all available route names for the client."
  [client]
  (->> client :handlers (mapv :route-name)))

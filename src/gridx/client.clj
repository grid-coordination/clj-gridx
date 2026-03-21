(ns gridx.client
  "GridX Pricing API client — shared infrastructure.

  Spec-driven HTTP client built on Martian. The OpenAPI spec bundled in
  resources/ is the single source of truth for endpoint definitions,
  parameter validation, and response schemas.

  For utility-specific clients with sensible defaults, see:
  - `gridx.pge.client` — PG&E
  - `gridx.sce.client` — SCE"
  (:require [martian.core :as martian]
            [martian.hato :as martian-hato]
            [clojure.tools.logging :as log]))

;; ---------------------------------------------------------------------------
;; Client creation
;; ---------------------------------------------------------------------------

(defn create-client
  "Create a GridX API client from an OpenAPI spec on the classpath.

  Options:
    :url       - API base URL (required)
    :spec-path - path to OpenAPI YAML on classpath (required)"
  [{:keys [url spec-path]}]
  {:pre [url spec-path]}
  (log/info "Creating GridX client" {:url url})
  (-> (martian-hato/bootstrap-openapi
       spec-path
       {:server-url url
        :interceptors (concat
                       [{:name  ::turn-off-exception-throwing
                         :enter (fn [ctx]
                                  (assoc-in ctx [:request :throw-exceptions?] false))}]
                       martian-hato/default-interceptors)})
      (assoc :api-root url)))

;; ---------------------------------------------------------------------------
;; API operations
;; ---------------------------------------------------------------------------

(defn get-pricing
  "Fetch pricing data from the GridX API.

  `params` is a map of query parameters matching the OpenAPI spec for
  the utility's getPricing endpoint. See `gridx.pge.client` or
  `gridx.sce.client` for utility-specific wrappers with defaults.

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

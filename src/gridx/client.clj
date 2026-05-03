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
            [clojure.tools.logging :as log])
  (:import [java.time ZoneId]))

;; ---------------------------------------------------------------------------
;; Client creation
;; ---------------------------------------------------------------------------

(defn- ->zone-id
  "Coerce a `ZoneId` or a zone-id string (e.g. \"America/Los_Angeles\") to a
  `ZoneId`. Throws on any other type."
  ^ZoneId [zone]
  (cond
    (instance? ZoneId zone) zone
    (string? zone)          (ZoneId/of zone)
    :else                   (throw (ex-info "Invalid :zone — expected ZoneId or zone-id string"
                                            {:zone zone :type (type zone)}))))

(defn create-client
  "Create a GridX API client from an OpenAPI spec on the classpath.

  Options:
    :url       - API base URL (required)
    :spec-path - path to OpenAPI YAML on classpath (required)
    :zone      - ZoneId (or zone-id string) used by the coercion layer to
                 produce ZonedDateTime values for curve/interval timestamps
                 (required)

  The configured zone flows through `get-pricing` onto each response as
  `:gridx/zone`, where `gridx.pricing/curves` reads it during coercion."
  [{:keys [url spec-path zone]}]
  {:pre [url spec-path zone]}
  (let [zone-id (->zone-id zone)]
    (log/info "Creating GridX client" {:url url :zone (str zone-id)})
    (-> (martian-hato/bootstrap-openapi
         spec-path
         {:server-url url
          :interceptors (concat
                         [{:name  ::turn-off-exception-throwing
                           :enter (fn [ctx]
                                    (assoc-in ctx [:request :throw-exceptions?] false))}]
                         martian-hato/default-interceptors)})
        (assoc :api-root url
               :zone     zone-id))))

;; ---------------------------------------------------------------------------
;; API operations
;; ---------------------------------------------------------------------------

(defn get-pricing
  "Fetch pricing data from the GridX API.

  `params` is a map of query parameters matching the OpenAPI spec for
  the utility's getPricing endpoint. See `gridx.pge.client` or
  `gridx.sce.client` for utility-specific wrappers with defaults.

  Returns the raw HTTP response map {:status :body :headers} augmented
  with `:gridx/zone` (the client's configured ZoneId), which the
  coercion layer reads when producing ZonedDateTime values."
  [client params]
  (-> (martian/response-for client :get-pricing params)
      (assoc :gridx/zone (:zone client))))

;; ---------------------------------------------------------------------------
;; Convenience
;; ---------------------------------------------------------------------------

(defn routes
  "List all available route names for the client."
  [client]
  (->> client :handlers (mapv :route-name)))

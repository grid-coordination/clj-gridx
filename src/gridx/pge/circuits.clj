(ns gridx.pge.circuits
  "PG&E circuit ID to location mapping.

  Each 9-digit representative circuit ID encodes a PG&E distribution feeder:
  region, division, substation, and feeder number.

  Data derived from:
  - PG&E 2022 Grid Needs Assessment (CPUC filing 496629893, Appendix D)
  - Priicer community cross-reference
    (https://forum.priicer.com/t/pg-e-dynamic-pilot-california/33)"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def circuit-locations
  "Map of 9-digit circuit ID string to location info.
  Each value is a map with :region, :division, :substation, :feeder,
  and :in-gridx-enum? keys."
  (-> (io/resource "data/pge-circuit-locations.edn")
      slurp
      edn/read-string))

(defn circuit-location
  "Look up location info for a circuit ID. Returns nil if not found.

  (circuit-location \"013532223\")
  ;=> {:region \"Bay Area\", :division \"Diablo\", :substation \"LAKEWOOD\",
  ;    :feeder \"2223\", :in-gridx-enum? true}"
  [circuit-id]
  (get circuit-locations circuit-id))

(defn find-circuits
  "Search circuits by substation name (case-insensitive substring match).
  Returns a sequence of [circuit-id location-info] pairs.

  (find-circuits \"woodland\")
  ;=> ([\"062031101\" {:region \"North Valley and Sierra\", ...}] ...)

  (find-circuits \"mountain view\")
  ;=> ([\"082031112\" {:region \"South Bay and Central Coast\", ...}])"
  [query]
  (let [q (str/lower-case query)]
    (->> circuit-locations
         (filter (fn [[_ loc]]
                   (str/includes? (str/lower-case (:substation loc)) q)))
         (sort-by first))))

(defn circuits-by-region
  "Return circuits grouped by region.

  (keys (circuits-by-region))
  ;=> (\"Bay Area\" \"Central Valley\" \"North Coast\" ...)"
  []
  (group-by (comp :region val) circuit-locations))

(defn gridx-circuits
  "Return only circuits confirmed available in the GridX API."
  []
  (into {} (filter (comp :in-gridx-enum? val) circuit-locations)))

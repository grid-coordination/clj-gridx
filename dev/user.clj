(ns user
  "REPL development utilities."
  (:require [gridx.client :as client]
            [gridx.pge.client :as pge]
            [gridx.pge.circuits :as circuits]
            [gridx.sce.client :as sce]
            [gridx.pricing :as pricing]))

(defonce pge-client (atom nil))
(defonce sce-client (atom nil))

(defn start-pge!
  "Initialize a PG&E GridX client for REPL use."
  ([] (start-pge! {}))
  ([opts]
   (reset! pge-client (pge/create-client opts))
   :pge-started))

(defn start-sce!
  "Initialize an SCE GridX client for REPL use."
  ([] (start-sce! {}))
  ([opts]
   (reset! sce-client (sce/create-client opts))
   :sce-started))

(defn start!
  "Initialize both PG&E and SCE clients."
  []
  (start-pge!)
  (start-sce!)
  :started)

(defn fetch-pge-pricing
  "Quick REPL helper to fetch PG&E pricing data.
  Example: (fetch-pge-pricing \"EELEC\" \"013532223\" \"20250301\" \"20250301\")"
  [ratename circuit-id startdate enddate]
  (pge/get-pricing
   @pge-client
   {:startdate startdate
    :enddate enddate
    :ratename ratename
    :representativeCircuitId circuit-id}))

(defn fetch-sce-pricing
  "Quick REPL helper to fetch SCE pricing data.
  Example: (fetch-sce-pricing \"TOU-EV-9S\" \"System\" \"20250701\" \"20250701\")"
  [ratename circuit startdate enddate]
  (sce/get-pricing
   @sce-client
   {:startdate startdate
    :enddate enddate
    :ratename ratename
    :representativeCircuitId circuit}))

(comment
  ;; Start clients
  (start!)

  ;; -- PG&E --
  (def pge-resp (fetch-pge-pricing "EELEC" "013532223" "20250301" "20250301"))
  (pricing/success? pge-resp)
  (pricing/raw-curves pge-resp)
  (pricing/curves pge-resp)
  (-> pge-resp pricing/curves first :gridx.curve/intervals first)

  ;; -- SCE --
  (def sce-resp (fetch-sce-pricing "TOU-EV-9S" "System" "20250701" "20250701"))
  (pricing/success? sce-resp)
  (pricing/raw-curves sce-resp)
  (pricing/curves sce-resp)
  (-> sce-resp pricing/curves first :gridx.curve/intervals first)

  ;; Access raw data via metadata
  (-> pge-resp pricing/curves first meta :gridx/raw)
  (-> sce-resp pricing/curves first meta :gridx/raw)

  ;; -- Circuit lookup --
  ;; Find circuit IDs by substation name
  (circuits/find-circuits "mountain view")
  ;=> (["082031112" {:region "South Bay and Central Coast", ...}])

  (circuits/find-circuits "woodland")
  ;=> (["062031101" {...}] ["062031102" {...}] ...)

  ;; Look up a specific circuit
  (circuits/circuit-location "013532223")
  ;=> {:region "Bay Area", :division "Diablo", :substation "LAKEWOOD", ...}

  ;; Browse by region
  (keys (circuits/circuits-by-region))

  ;; Only circuits confirmed in GridX API
  (count (circuits/gridx-circuits))  ;=> 59
  )

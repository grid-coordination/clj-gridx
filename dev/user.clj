(ns user
  "REPL development utilities."
  (:require [gridx.client :as client]
            [gridx.pricing :as pricing]))

(defonce gridx-client (atom nil))

(defn start!
  "Initialize a GridX client for REPL use."
  ([] (start! {}))
  ([opts]
   (reset! gridx-client (client/create-client opts))
   :started))

(defn fetch-pricing
  "Quick REPL helper to fetch pricing data.
  Example: (fetch-pricing \"EELEC\" \"013532223\" \"20250301\" \"20250301\")"
  [ratename circuit-id startdate enddate]
  (client/get-pricing
   @gridx-client
   {:utility "PGE"
    :market "DAM"
    :program "CalFUSE"
    :startdate startdate
    :enddate enddate
    :ratename ratename
    :representativeCircuitId circuit-id}))

(comment
  ;; Start the client
  (start!)

  ;; Fetch EELEC pricing for March 1, 2025
  (def resp (fetch-pricing "EELEC" "013532223" "20250301" "20250301"))

  ;; Check success
  (pricing/success? resp)

  ;; Extract curves
  (pricing/price-curves resp)

  ;; Look at first interval's components
  (-> resp pricing/price-curves first :priceDetails first pricing/component-prices))

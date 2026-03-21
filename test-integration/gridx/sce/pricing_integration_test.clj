(ns gridx.sce.pricing-integration-test
  "Integration tests for SCE pricing against the live stage API.

  These tests require network access to pe-api.gridx.com.
  Run with: clojure -M:test-integration"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [gridx.sce.client :as sce]
            [gridx.client :as client]
            [gridx.pricing :as pricing]
            [gridx.pricing.schema :as schema]
            [malli.core :as m]
            [tick.core :as t]))

(def ^:dynamic *client* nil)

(def sce-component-names
  #{:gridx.component/abank
    :gridx.component/bbank
    :gridx.component/circuitpricecurve
    :gridx.component/mec
    :gridx.component/nbc
    :gridx.component/ppf
    :gridx.component/ramp
    :gridx.component/transmissionpricecurve})

(def sce-raw-component-names
  #{"abank" "bbank" "circuitpricecurve" "mec" "nbc" "ppf" "ramp" "transmissionpricecurve"})

(defn client-fixture [f]
  (binding [*client* (sce/create-client)]
    (f)))

(use-fixtures :once client-fixture)

(deftest sce-live-pricing-test
  (testing "SCE live API returns successful response"
    (let [resp (sce/get-pricing *client*
                                {:startdate "20250701"
                                 :enddate "20250701"
                                 :ratename "TOU-EV-9S"
                                 :representativeCircuitId "System"})]
      (is (pricing/success? resp))

      (testing "response body validates against raw schema"
        (is (nil? (pricing/validate-raw (:body resp)))))

      (testing "meta contains expected fields"
        (let [meta-data (get-in resp [:body :meta])]
          (is (= 200 (:code meta-data)))
          (is (string? (:requestURL meta-data)))
          (is (string? (:requestBody meta-data)))
          (is (string? (:response meta-data)))))

      (testing "raw curves have expected structure"
        (let [curves (pricing/raw-curves resp)]
          (is (pos? (count curves)))
          (let [curve (first curves)
                header (:priceHeader curve)]
            (is (string? (:priceCurveName header)))
            (is (= "CAISO-DAM" (:marketName header)))
            (is (contains? #{15 60} (:intervalLengthInMinutes header)))
            (is (= "USD" (:settlementCurrency header)))
            (is (= "kWh" (:settlementUnit header)))
            (is (pos? (:recordCount header)))
            (is (= (:recordCount header) (count (:priceDetails curve))))

            (testing "each interval has exactly 8 SCE components"
              (doseq [detail (:priceDetails curve)]
                (is (= 8 (count (:priceComponents detail))))
                (is (= sce-raw-component-names
                       (set (map :component (:priceComponents detail))))))))))

      (testing "coerced curves validate against schema"
        (let [curves (pricing/curves resp)]
          (is (pos? (count curves)))
          (doseq [curve curves]
            (is (m/validate schema/Curve curve))
            (is (= :gridx.market/caiso-dam (:gridx.curve/market curve)))
            (is (= :USD (:gridx.curve/currency curve)))
            (is (= :kWh (:gridx.curve/unit curve)))

            (testing "intervals have expected SCE component names"
              (doseq [interval (:gridx.curve/intervals curve)]
                (is (= sce-component-names
                       (set (map :gridx.component/name
                                 (:gridx.interval/components interval)))))

                (testing "all prices are BigDecimals"
                  (is (decimal? (:gridx.interval/price interval)))
                  (doseq [comp (:gridx.interval/components interval)]
                    (is (decimal? (:gridx.component/price comp)))))))

            (testing "metadata preserved on coerced entities"
              (is (map? (-> curve meta :gridx/raw)))
              (is (map? (-> curve :gridx.curve/intervals first meta :gridx/raw))))))))))

(deftest sce-live-different-circuit-test
  (testing "SCE live API works with a named circuit substation"
    (let [resp (sce/get-pricing *client*
                                {:startdate "20250701"
                                 :enddate "20250701"
                                 :ratename "TOU-GS-1"
                                 :representativeCircuitId "Alamitos"})]
      (is (pricing/success? resp))
      (is (pos? (count (pricing/curves resp)))))))

(deftest sce-live-multiday-test
  (testing "SCE live API returns multiple curves for date range"
    (let [resp (sce/get-pricing *client*
                                {:startdate "20250701"
                                 :enddate "20250703"
                                 :ratename "TOU-EV-9S"
                                 :representativeCircuitId "System"})]
      (is (pricing/success? resp))
      (let [curves (pricing/curves resp)]
        (is (<= 2 (count curves)))))))

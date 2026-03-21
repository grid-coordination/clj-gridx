(ns gridx.pge.pricing-integration-test
  "Integration tests for PG&E pricing against the live stage API.

  These tests require network access to pe-api.gridx.com.
  Run with: clojure -M:test-integration"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [gridx.pge.client :as pge]
            [gridx.client :as client]
            [gridx.pricing :as pricing]
            [gridx.pricing.schema :as schema]
            [malli.core :as m]
            [tick.core :as t]))

(def ^:dynamic *client* nil)

(defn client-fixture [f]
  (binding [*client* (pge/create-client)]
    (f)))

(use-fixtures :once client-fixture)

(deftest pge-live-pricing-test
  (testing "PG&E live API returns successful response"
    (let [today (pricing/->gridx-date (t/today))
          resp (pge/get-pricing *client*
                                {:startdate today
                                 :enddate today
                                 :ratename "EELEC"
                                 :representativeCircuitId "013532223"})]
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

            (testing "each interval has exactly 3 PG&E components"
              (doseq [detail (:priceDetails curve)]
                (is (= 3 (count (:priceComponents detail))))
                (is (= #{"cld" "mec" "mgcc"}
                       (set (map :component (:priceComponents detail))))))))))

      (testing "coerced curves validate against schema"
        (let [curves (pricing/curves resp)]
          (is (pos? (count curves)))
          (doseq [curve curves]
            (is (m/validate schema/Curve curve))
            (is (= :gridx.market/caiso-dam (:gridx.curve/market curve)))
            (is (= :USD (:gridx.curve/currency curve)))
            (is (= :kWh (:gridx.curve/unit curve)))

            (testing "intervals have expected component names"
              (doseq [interval (:gridx.curve/intervals curve)]
                (is (= #{:gridx.component/cld
                         :gridx.component/mec
                         :gridx.component/mgcc}
                       (set (map :gridx.component/name
                                 (:gridx.interval/components interval)))))

                (testing "all prices are BigDecimals"
                  (is (decimal? (:gridx.interval/price interval)))
                  (doseq [comp (:gridx.interval/components interval)]
                    (is (decimal? (:gridx.component/price comp)))))))

            (testing "metadata preserved on coerced entities"
              (is (map? (-> curve meta :gridx/raw)))
              (is (map? (-> curve :gridx.curve/intervals first meta :gridx/raw))))))))))

(deftest pge-live-cca-test
  (testing "PG&E live API works with CCA parameter"
    (let [today (pricing/->gridx-date (t/today))
          resp (pge/get-pricing *client*
                                {:startdate today
                                 :enddate today
                                 :ratename "EELEC"
                                 :representativeCircuitId "013532223"
                                 :cca "PCE"})]
      (is (pricing/success? resp))
      (is (pos? (count (pricing/curves resp)))))))

(deftest pge-live-multiday-test
  (testing "PG&E live API returns multiple curves for date range"
    (let [end (t/today)
          start (t/<< end (t/of-days 2))
          resp (pge/get-pricing *client*
                                {:startdate (pricing/->gridx-date start)
                                 :enddate (pricing/->gridx-date end)
                                 :ratename "EELEC"
                                 :representativeCircuitId "013532223"})]
      (is (pricing/success? resp))
      (let [curves (pricing/curves resp)]
        (is (<= 2 (count curves)))))))

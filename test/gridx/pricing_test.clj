(ns gridx.pricing-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [gridx.pricing :as pricing]
            [gridx.pricing.schema :as schema]
            [malli.core :as m]))

(def pge-sample-body
  (-> (io/resource "gridx-pricing-spec/pge/examples/pge-pricing-response-sample.json")
      slurp
      (json/read-str :key-fn keyword)))

(def sce-sample-body
  (-> (io/resource "gridx-pricing-spec/sce/examples/sce-pricing-response-sample.json")
      slurp
      (json/read-str :key-fn keyword)))

;; -- PG&E tests -------------------------------------------------------------

(deftest pge-raw-schema-validation-test
  (testing "PG&E sample response validates against raw Malli schema"
    (is (nil? (pricing/validate-raw pge-sample-body)))))

(deftest pge-raw-extraction-test
  (testing "Extract raw price curves from PG&E sample"
    (let [curves (:data pge-sample-body)]
      (is (= 1 (count curves)))
      (is (= "PGE-CalFUSE-EELEC-SECONDARY"
             (get-in (first curves) [:priceHeader :priceCurveName]))))))

(deftest pge-coercion-test
  (testing "PG&E sample coerces to valid Curve schema"
    (let [response {:status 200 :body pge-sample-body}
          curves (pricing/curves response)]
      (is (= 1 (count curves)))
      (is (= :gridx.market/caiso-dam (:gridx.curve/market (first curves))))
      (is (every? #(m/validate schema/Curve %) curves)))))

;; -- SCE tests ---------------------------------------------------------------

(deftest sce-raw-schema-validation-test
  (testing "SCE sample response validates against raw Malli schema"
    (is (nil? (pricing/validate-raw sce-sample-body)))))

(deftest sce-raw-extraction-test
  (testing "Extract raw price curves from SCE sample"
    (let [curves (:data sce-sample-body)]
      (is (= 1 (count curves)))
      (is (= "SCE-CalFUSE-TOU-EV-9S-SECONDARY"
             (get-in (first curves) [:priceHeader :priceCurveName]))))))

(deftest sce-coercion-test
  (testing "SCE sample coerces to valid Curve schema"
    (let [response {:status 200 :body sce-sample-body}
          curves (pricing/curves response)]
      (is (= 1 (count curves)))
      (is (= :gridx.market/caiso-dam (:gridx.curve/market (first curves))))
      (is (every? #(m/validate schema/Curve %) curves))
      ;; SCE has 8 components per interval
      (is (= 8 (count (:gridx.interval/components
                       (first (:gridx.curve/intervals (first curves))))))))))

;; -- Shared tests ------------------------------------------------------------

(deftest date-format-test
  (testing "Date formatting for GridX API"
    (is (= "20250301" (pricing/->gridx-date "2025-03-01")))))

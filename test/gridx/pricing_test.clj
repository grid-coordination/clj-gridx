(ns gridx.pricing-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [gridx.pricing :as pricing]
            [gridx.pricing.schema :as schema]
            [gridx.pricing.schema.raw :as schema.raw]
            [malli.core :as m]))

(def sample-body
  (-> (io/resource "gridx-pricing-spec/examples/pge-pricing-response-sample.json")
      slurp
      (json/read-str :key-fn keyword)))

(deftest raw-schema-validation-test
  (testing "Sample response body validates against raw Malli schema"
    (is (nil? (pricing/validate-raw sample-body)))))

(deftest raw-extraction-test
  (testing "Extract raw price curves from sample"
    (let [curves (:data sample-body)]
      (is (= 1 (count curves)))
      (is (= "PGE-CalFUSE-EELEC-SECONDARY"
             (get-in (first curves) [:priceHeader :priceCurveName]))))))

(deftest date-format-test
  (testing "Date formatting for GridX API"
    (is (= "20250301" (pricing/->gridx-date "2025-03-01")))))

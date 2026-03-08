(ns gridx.pricing-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [gridx.pricing :as pricing]
            [malli.core :as m]))

(def sample-response
  (-> (io/resource "gridx-pricing-spec/examples/pge-pricing-response-sample.json")
      slurp
      (json/read-str :key-fn keyword)))

(deftest schema-validation-test
  (testing "Sample response validates against Malli schema"
    (is (nil? (pricing/validate-response sample-response)))))

(deftest price-extraction-test
  (testing "Extract price curves from sample"
    (let [curves (get sample-response :data)]
      (is (= 1 (count curves)))
      (is (= "PGE-CalFUSE-EELEC-SECONDARY"
             (get-in (first curves) [:priceHeader :priceCurveName])))))

  (testing "Component price extraction"
    (let [detail (-> sample-response :data first :priceDetails first)
          components (pricing/component-prices detail)]
      (is (= #{:mec :mgcc :cld} (set (keys components))))
      (is (every? #(instance? BigDecimal %) (vals components)))))

  (testing "Total price parsing"
    (let [detail (-> sample-response :data first :priceDetails first)
          total  (pricing/total-price detail)]
      (is (instance? BigDecimal total))
      (is (pos? total)))))

(deftest date-format-test
  (testing "Date formatting for GridX API"
    (is (= "20250301" (pricing/->gridx-date "2025-03-01")))))

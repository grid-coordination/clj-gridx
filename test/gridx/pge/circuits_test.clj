(ns gridx.pge.circuits-test
  (:require [clojure.test :refer [deftest is testing]]
            [gridx.pge.circuits :as circuits]))

(deftest circuit-locations-loaded-test
  (testing "EDN data loads and contains expected number of circuits"
    (is (= 98 (count circuits/circuit-locations)))
    (is (every? string? (keys circuits/circuit-locations)))))

(deftest circuit-location-test
  (testing "known circuit returns location info"
    (let [loc (circuits/circuit-location "013532223")]
      (is (some? loc))
      (is (= "Bay Area" (:region loc)))
      (is (= "Diablo" (:division loc)))
      (is (= "LAKEWOOD" (:substation loc)))
      (is (= "2223" (:feeder loc)))
      (is (true? (:in-gridx-enum? loc)))))

  (testing "unknown circuit returns nil"
    (is (nil? (circuits/circuit-location "999999999")))))

(deftest find-circuits-test
  (testing "case-insensitive substring match"
    (let [results (circuits/find-circuits "mountain view")]
      (is (= 1 (count results)))
      (is (= "082031112" (ffirst results)))))

  (testing "multiple matches for shared substation name"
    (let [results (circuits/find-circuits "woodland")]
      (is (= 8 (count results)))
      (is (every? #(= "WOODLAND" (:substation (second %))) results))))

  (testing "no match returns empty"
    (is (empty? (circuits/find-circuits "nonexistent substation")))))

(deftest circuits-by-region-test
  (testing "groups by region with expected keys"
    (let [grouped (circuits/circuits-by-region)]
      (is (contains? grouped "Bay Area"))
      (is (contains? grouped "Central Valley"))
      (is (contains? grouped "North Coast"))
      (is (contains? grouped "South Bay and Central Coast"))
      (is (contains? grouped "North Valley and Sierra")))))

(deftest gridx-circuits-test
  (testing "returns only circuits with :in-gridx-enum? true"
    (let [gx (circuits/gridx-circuits)]
      (is (= 59 (count gx)))
      (is (every? (comp true? :in-gridx-enum? val) gx)))))

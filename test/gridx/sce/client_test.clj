(ns gridx.sce.client-test
  (:require [clojure.test :refer [deftest is testing]]
            [gridx.client :as client]
            [gridx.sce.client :as sce])
  (:import [java.time ZoneId]))

(deftest create-client-test
  (testing "SCE client creation with defaults"
    (let [c (sce/create-client)]
      (is (some? c))
      (is (= sce/stage-url (:api-root c)))
      (is (= (ZoneId/of "America/Los_Angeles") (:zone c)))
      (is (some #{:get-pricing} (client/routes c))))))

(deftest create-client-zone-override-test
  (testing "SCE client accepts a :zone override"
    (let [c (sce/create-client {:zone "UTC"})]
      (is (= (ZoneId/of "UTC") (:zone c))))))

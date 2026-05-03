(ns gridx.pge.client-test
  (:require [clojure.test :refer [deftest is testing]]
            [gridx.client :as client]
            [gridx.pge.client :as pge])
  (:import [java.time ZoneId]))

(deftest create-client-test
  (testing "PG&E client creation with defaults"
    (let [c (pge/create-client)]
      (is (some? c))
      (is (= pge/stage-url (:api-root c)))
      (is (= (ZoneId/of "America/Los_Angeles") (:zone c)))
      (is (some #{:get-pricing} (client/routes c))))))

(deftest create-client-production-test
  (testing "PG&E client creation with production URL"
    (let [c (pge/create-client {:url pge/production-url})]
      (is (= pge/production-url (:api-root c))))))

(deftest create-client-zone-override-test
  (testing "PG&E client accepts a :zone override (ZoneId or string)"
    (let [c1 (pge/create-client {:zone "UTC"})
          c2 (pge/create-client {:zone (ZoneId/of "America/New_York")})]
      (is (= (ZoneId/of "UTC") (:zone c1)))
      (is (= (ZoneId/of "America/New_York") (:zone c2))))))

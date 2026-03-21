(ns gridx.pge.client-test
  (:require [clojure.test :refer [deftest is testing]]
            [gridx.client :as client]
            [gridx.pge.client :as pge]))

(deftest create-client-test
  (testing "PG&E client creation with defaults"
    (let [c (pge/create-client)]
      (is (some? c))
      (is (= pge/stage-url (:api-root c)))
      (is (some #{:get-pricing} (client/routes c))))))

(deftest create-client-production-test
  (testing "PG&E client creation with production URL"
    (let [c (pge/create-client {:url pge/production-url})]
      (is (= pge/production-url (:api-root c))))))

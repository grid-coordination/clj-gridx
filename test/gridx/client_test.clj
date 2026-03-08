(ns gridx.client-test
  (:require [clojure.test :refer [deftest is testing]]
            [gridx.client :as client]))

(deftest create-client-test
  (testing "Client creation with defaults"
    (let [c (client/create-client)]
      (is (some? c))
      (is (= client/stage-url (:api-root c)))
      (is (seq (client/routes c)))
      (is (some #{:get-pricing} (client/routes c))))))

(deftest create-client-production-test
  (testing "Client creation with production URL"
    (let [c (client/create-client {:url client/production-url})]
      (is (= client/production-url (:api-root c))))))

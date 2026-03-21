(ns gridx.sce.client-test
  (:require [clojure.test :refer [deftest is testing]]
            [gridx.client :as client]
            [gridx.sce.client :as sce]))

(deftest create-client-test
  (testing "SCE client creation with defaults"
    (let [c (sce/create-client)]
      (is (some? c))
      (is (= sce/stage-url (:api-root c)))
      (is (some #{:get-pricing} (client/routes c))))))

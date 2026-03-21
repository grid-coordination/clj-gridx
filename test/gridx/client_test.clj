(ns gridx.client-test
  (:require [clojure.test :refer [deftest is testing]]
            [gridx.client :as client]
            [gridx.pge.client :as pge]))

(deftest create-client-requires-url-and-spec
  (testing "Shared create-client requires :url and :spec-path"
    (is (thrown? AssertionError (client/create-client {})))
    (is (thrown? AssertionError (client/create-client {:url "http://example.com"})))))

(deftest create-client-with-explicit-opts
  (testing "Shared create-client works with explicit opts"
    (let [c (client/create-client {:url pge/stage-url
                                   :spec-path pge/default-spec-path})]
      (is (some? c))
      (is (= pge/stage-url (:api-root c)))
      (is (some #{:get-pricing} (client/routes c))))))

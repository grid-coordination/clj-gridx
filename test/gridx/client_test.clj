(ns gridx.client-test
  (:require [clojure.test :refer [deftest is testing]]
            [gridx.client :as client]
            [gridx.pge.client :as pge])
  (:import [java.time ZoneId]))

(deftest create-client-requires-url-spec-and-zone
  (testing "Shared create-client requires :url, :spec-path, and :zone"
    (is (thrown? AssertionError (client/create-client {})))
    (is (thrown? AssertionError (client/create-client {:url "http://example.com"})))
    (is (thrown? AssertionError (client/create-client {:url       "http://example.com"
                                                       :spec-path "x"})))))

(deftest create-client-zone-string-or-zoneid
  (testing "Shared create-client accepts ZoneId or zone-id string for :zone"
    (let [zone (ZoneId/of "America/Los_Angeles")
          c1 (client/create-client {:url       pge/stage-url
                                    :spec-path pge/default-spec-path
                                    :zone      zone})
          c2 (client/create-client {:url       pge/stage-url
                                    :spec-path pge/default-spec-path
                                    :zone      "America/Los_Angeles"})]
      (is (= zone (:zone c1)))
      (is (= zone (:zone c2))))))

(deftest create-client-rejects-bad-zone
  (testing "Shared create-client throws on invalid :zone type"
    (is (thrown? clojure.lang.ExceptionInfo
                 (client/create-client {:url       pge/stage-url
                                        :spec-path pge/default-spec-path
                                        :zone      42})))))

(deftest create-client-with-explicit-opts
  (testing "Shared create-client works with explicit opts"
    (let [c (client/create-client {:url       pge/stage-url
                                   :spec-path pge/default-spec-path
                                   :zone      "America/Los_Angeles"})]
      (is (some? c))
      (is (= pge/stage-url (:api-root c)))
      (is (some #{:get-pricing} (client/routes c))))))

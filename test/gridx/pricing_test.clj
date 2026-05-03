(ns gridx.pricing-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [gridx.pricing :as pricing]
            [gridx.pricing.schema :as schema]
            [malli.core :as m])
  (:import [java.time ZoneId ZonedDateTime ZoneOffset]
           [java.time.temporal ChronoUnit]))

(def la-zone (ZoneId/of "America/Los_Angeles"))

(def pge-sample-body
  (-> (io/resource "gridx-pricing-spec/pge/examples/pge-pricing-response-sample.json")
      slurp
      (json/read-str :key-fn keyword)))

(def sce-sample-body
  (-> (io/resource "gridx-pricing-spec/sce/examples/sce-pricing-response-sample.json")
      slurp
      (json/read-str :key-fn keyword)))

;; -- PG&E tests -------------------------------------------------------------

(deftest pge-raw-schema-validation-test
  (testing "PG&E sample response validates against raw Malli schema"
    (is (nil? (pricing/validate-raw pge-sample-body)))))

(deftest pge-raw-extraction-test
  (testing "Extract raw price curves from PG&E sample"
    (let [curves (:data pge-sample-body)]
      (is (= 1 (count curves)))
      (is (= "PGE-CalFUSE-EELEC-SECONDARY"
             (get-in (first curves) [:priceHeader :priceCurveName]))))))

(deftest pge-coercion-test
  (testing "PG&E sample coerces to valid Curve schema"
    (let [response {:status 200 :body pge-sample-body :gridx/zone la-zone}
          curves (pricing/curves response)]
      (is (= 1 (count curves)))
      (is (= :gridx.market/caiso-dam (:gridx.curve/market (first curves))))
      (is (every? #(m/validate schema/Curve %) curves)))))

(deftest pge-curves-explicit-zone-test
  (testing "2-arity curves accepts an explicit zone (overrides response)"
    (let [response {:status 200 :body pge-sample-body}
          curves (pricing/curves response la-zone)]
      (is (every? #(m/validate schema/Curve %) curves))
      (is (= la-zone (.getZone ^ZonedDateTime (:gridx.curve/start (first curves))))))))

(deftest curves-without-zone-throws
  (testing "1-arity curves throws when response is missing :gridx/zone"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":gridx/zone"
                          (pricing/curves {:status 200 :body pge-sample-body})))))

;; -- SCE tests ---------------------------------------------------------------

(deftest sce-raw-schema-validation-test
  (testing "SCE sample response validates against raw Malli schema"
    (is (nil? (pricing/validate-raw sce-sample-body)))))

(deftest sce-raw-extraction-test
  (testing "Extract raw price curves from SCE sample"
    (let [curves (:data sce-sample-body)]
      (is (= 1 (count curves)))
      (is (= "SCE-CalFUSE-TOU-EV-9S-SECONDARY"
             (get-in (first curves) [:priceHeader :priceCurveName]))))))

(deftest sce-coercion-test
  (testing "SCE sample coerces to valid Curve schema"
    (let [response {:status 200 :body sce-sample-body :gridx/zone la-zone}
          curves (pricing/curves response)]
      (is (= 1 (count curves)))
      (is (= :gridx.market/caiso-dam (:gridx.curve/market (first curves))))
      (is (every? #(m/validate schema/Curve %) curves))
      ;; SCE has 8 components per interval
      (is (= 8 (count (:gridx.interval/components
                       (first (:gridx.curve/intervals (first curves))))))))))

;; -- Time / DST tests --------------------------------------------------------

(deftest zoned-datetime-end-to-end-test
  (testing "Coerced timestamps are ZonedDateTimes in the configured zone"
    (let [response {:status 200 :body pge-sample-body :gridx/zone la-zone}
          curve    (first (pricing/curves response))
          interval (first (:gridx.curve/intervals curve))]
      (is (instance? ZonedDateTime (:gridx.curve/start curve)))
      (is (instance? ZonedDateTime (:gridx.curve/end curve)))
      (is (instance? ZonedDateTime (:tick/beginning curve)))
      (is (instance? ZonedDateTime (:tick/end curve)))
      (is (instance? ZonedDateTime (:tick/beginning interval)))
      (is (instance? ZonedDateTime (:tick/end interval)))
      (is (= la-zone (.getZone ^ZonedDateTime (:gridx.curve/start curve))))
      (is (= la-zone (.getZone ^ZonedDateTime (:tick/beginning interval)))))))

(deftest zone-coercion-from-string-test
  (testing "2-arity curves coerces a zone-id string to ZoneId"
    (let [response {:status 200 :body pge-sample-body}
          curves   (pricing/curves response "America/Los_Angeles")]
      (is (= la-zone (.getZone ^ZonedDateTime (:gridx.curve/start (first curves))))))))

(deftest zone-mismatch-throws-test
  (testing "Parser throws when wire offset doesn't match configured zone"
    ;; A Pacific-serving API timestamp parsed against America/New_York: at
    ;; 2025-07-01T00:00 PDT (-0700), New York is on EDT (-0400), so the
    ;; wire offset would not match the zone's offset at that instant.
    (let [body {:meta {:code 200 :requestURL "" :requestBody "" :response ""}
                :data [{:priceHeader {:priceCurveName "MISMATCH"
                                      :marketName "CAISO-DAM"
                                      :intervalLengthInMinutes 60
                                      :settlementCurrency "USD"
                                      :settlementUnit "kWh"
                                      :startTime "2025-07-01T00:00:00-0700"
                                      :endTime "2025-07-01T23:59:59-0700"
                                      :recordCount 1}
                        :priceDetails [{:startIntervalTimeStamp "2025-07-01T00:00:00-0700"
                                        :intervalPrice "0.10"
                                        :priceStatus "Final"
                                        :priceComponents [{:component "cld"
                                                           :intervalPrice "0.01"
                                                           :priceType "distribution"}]}]}]}
          response {:status 200 :body body :gridx/zone (ZoneId/of "America/New_York")}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"wire offset does not match"
                            (pricing/curves response))))))

(deftest dst-spring-forward-coercion-test
  (testing "Spring-forward day: parser places intervals in PST and PDT correctly"
    ;; 2025-03-09 is the US spring-forward day; 02:00 PST does not exist —
    ;; the wall clock jumps from 02:00 PST directly to 03:00 PDT.
    ;; The API serializes each interval start with its own offset; we
    ;; verify the parser respects that offset and re-expresses the value
    ;; in America/Los_Angeles with the matching DST rule applied.
    (let [synthetic-body {:meta {:code 200 :requestURL "" :requestBody "" :response ""}
                          :data [{:priceHeader {:priceCurveName "DST-TEST"
                                                :marketName "CAISO-DAM"
                                                :intervalLengthInMinutes 60
                                                :settlementCurrency "USD"
                                                :settlementUnit "kWh"
                                                :startTime "2025-03-09T00:00:00-0800"
                                                :endTime "2025-03-09T23:59:59-0700"
                                                :recordCount 23}
                                  :priceDetails [{:startIntervalTimeStamp "2025-03-09T01:00:00-0800"
                                                  :intervalPrice "0.10"
                                                  :priceStatus "Final"
                                                  :priceComponents [{:component "cld" :intervalPrice "0.01" :priceType "distribution"}]}
                                                 {:startIntervalTimeStamp "2025-03-09T03:00:00-0700"
                                                  :intervalPrice "0.20"
                                                  :priceStatus "Final"
                                                  :priceComponents [{:component "cld" :intervalPrice "0.02" :priceType "distribution"}]}]}]}
          response {:status 200 :body synthetic-body :gridx/zone la-zone}
          curve    (first (pricing/curves response))
          [pre post] (:gridx.curve/intervals curve)
          pre-zdt  ^ZonedDateTime (:tick/beginning pre)
          post-zdt ^ZonedDateTime (:tick/beginning post)]
      (testing "curve start is PST, end is PDT (spans the transition)"
        (is (= ZoneOffset/UTC
               (.getOffset ^ZonedDateTime (.withZoneSameInstant
                                           ^ZonedDateTime (:gridx.curve/start curve)
                                           ZoneOffset/UTC))))
        (is (= (ZoneOffset/ofHours -8) (.getOffset ^ZonedDateTime (:gridx.curve/start curve))))
        (is (= (ZoneOffset/ofHours -7) (.getOffset ^ZonedDateTime (:gridx.curve/end curve)))))
      (testing "01:00 PST interval keeps the -08:00 offset"
        (is (= (ZoneOffset/ofHours -8) (.getOffset pre-zdt)))
        (is (= 1 (.getHour pre-zdt))))
      (testing "03:00 PDT interval has the -07:00 offset (02:00 PST does not exist)"
        (is (= (ZoneOffset/ofHours -7) (.getOffset post-zdt)))
        (is (= 3 (.getHour post-zdt))))
      (testing "consecutive intervals are 1 real hour apart even across the gap"
        (is (= 60 (.between ChronoUnit/MINUTES pre-zdt post-zdt)))))))

;; -- Shared tests ------------------------------------------------------------

(deftest date-format-test
  (testing "Date formatting for GridX API"
    (is (= "20250301" (pricing/->gridx-date "2025-03-01")))))

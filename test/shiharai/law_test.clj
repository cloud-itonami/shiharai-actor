(ns shiharai.law-test
  "The calendar refuses rather than guesses, and 取適法 第三条 answers in more
  than two values."
  (:require [clojure.test :refer [deftest is testing]]
            [shiharai.law :as law]))

;; ---------------------------------------------------------------------------
;; Calendar
;; ---------------------------------------------------------------------------

(deftest epoch-day-round-trips
  (doseq [d ["1970-01-01" "2000-02-29" "2026-01-01" "2026-08-17" "2100-03-01"]]
    (testing d
      (is (= d (law/date-string (law/epoch-day d)))))))

(deftest the-epoch-is-day-zero-and-that-is-why-nil-is-not-zero
  (is (= 0 (law/epoch-day "1970-01-01")))
  (testing "an unreadable date is nil, which a caller cannot confuse with the epoch"
    (is (nil? (law/epoch-day nil)))
    (is (nil? (law/epoch-day "")))
    (is (nil? (law/epoch-day "2026-8-17")))
    (is (nil? (law/epoch-day "17/08/2026")))
    (is (nil? (law/epoch-day 20260817)))))

(deftest impossible-days-are-rejected-by-round-trip
  (testing "well-formed strings naming days that do not exist"
    (is (nil? (law/epoch-day "2026-02-30")))
    (is (nil? (law/epoch-day "2026-13-01")))
    (is (nil? (law/epoch-day "2026-04-31")))
    (is (nil? (law/epoch-day "2026-00-10")))
    (is (nil? (law/epoch-day "2026-01-00"))))
  (testing "and leap years are real"
    (is (some? (law/epoch-day "2024-02-29")))
    (is (nil? (law/epoch-day "2026-02-29")))
    (is (nil? (law/epoch-day "1900-02-29")))
    (is (some? (law/epoch-day "2000-02-29")))))

(deftest days-between-spans-months-and-years
  (is (= 31 (law/days-between "2026-01-01" "2026-02-01")))
  (is (= 60 (law/days-between "2026-01-01" "2026-03-02")))
  (is (= 365 (law/days-between "2026-01-01" "2027-01-01")))
  (testing "negative for a prepayment; this fn does not decide that is wrong"
    (is (= -5 (law/days-between "2026-01-10" "2026-01-05"))))
  (testing "nil when either side is unreadable"
    (is (nil? (law/days-between "2026-01-01" "nope")))
    (is (nil? (law/days-between nil "2026-01-01")))))

;; ---------------------------------------------------------------------------
;; 取適法 第三条
;; ---------------------------------------------------------------------------

(def ^:private sme {:supplier/id "s-1" :supplier/sme-subcontractor? true})
(def ^:private not-sme {:supplier/id "s-2" :supplier/sme-subcontractor? false})

(defn- payable [& {:as kv}]
  (merge {:payable/id "pbl-1"
          :payable/mandate :manufacturing-subcontract
          :payable/received-date "2026-01-01"
          :payable/due-date "2026-02-01"}
         kv))

(deftest scope-needs-both-assertions
  (testing "neither side's silence puts a transaction in or out of scope"
    (is (law/in-scope? (payable) sme))
    (is (not (law/in-scope? (payable) not-sme)))
    (is (not (law/in-scope? (payable) {})))
    (is (not (law/in-scope? (payable :payable/mandate nil) sme)))
    (is (not (law/in-scope? (payable :payable/mandate :ordinary-purchase) sme)))))

(deftest out-of-scope-is-not-declared-not-a-pass
  (let [t (law/payment-term (payable :payable/mandate :ordinary-purchase) sme)]
    (is (= :not-declared (:law/coverage t)))
    (testing "and it is not reported as conforming"
      (is (nil? (:law/term t)))
      (is (not (law/exceeded? t)))
      (is (not (law/boundary? t))))))

(deftest a-conforming-term
  (let [t (law/payment-term (payable :payable/due-date "2026-02-01") sme)]
    (is (= :checked (:law/coverage t)))
    (is (= 31 (:law/days t)))
    (is (= :conforming (:law/term t)))
    (is (= "2026-03-02" (:law/latest-permissible-due-date t)))
    (is (nil? (:law/deemed-due-date t)))))

(deftest exactly-sixty-days-is-the-boundary-and-is-not-decided
  (let [t (law/payment-term (payable :payable/due-date "2026-03-02") sme)]
    (is (= 60 (:law/days t)))
    (is (= :boundary (:law/term t)))
    (testing "the boundary is neither reported as a violation nor as compliance"
      (is (not (law/exceeded? t)))
      (is (law/boundary? t)))))

(deftest sixty-one-days-exceeds-on-every-reading
  (let [t (law/payment-term (payable :payable/due-date "2026-03-03") sme)]
    (is (= 61 (:law/days t)))
    (is (= :exceeded (:law/term t)))
    (is (law/exceeded? t))
    (is (= "2026-03-02" (:law/deemed-due-date t)))
    (testing "and it says the bound is a bound"
      (is (some? (:law/deemed-due-date-note t))))))

(deftest in-scope-and-undatable-is-undeterminable-not-compliant
  (doseq [p [(payable :payable/received-date nil)
             (payable :payable/received-date "unknown")
             (payable :payable/due-date nil)]]
    (let [t (law/payment-term p sme)]
      (is (= :undeterminable (:law/coverage t))
          (str "should refuse to answer for " (pr-str p)))
      (is (not (law/exceeded? t)))
      (is (not (law/boundary? t)))
      (testing "and says why, so the gap is visible"
        (is (some? (:law/why t)))))))

(deftest the-statute-is-quoted-not-merely-cited
  (let [s law/subcontract-payment-term]
    (is (= "331AC0000000120" (:law/id s)))
    (is (= "第三条（製造委託等代金の支払期日）" (:law/provision s)))
    (is (= 60 (:law/max-days s)))
    (testing "both paragraphs are present verbatim"
      (is (re-find #"六十日の期間内において" (:law/quote s)))
      (is (re-find #"六十日を経過した日の前日" (:law/quote-2 s))))
    (testing "and the retrieval is recorded, so the claim is checkable"
      (is (re-find #"law_data/331AC0000000120" (:law/retrieved-via s)))
      (is (= "2026-08-17" (:law/retrieved-at s)))
      (is (= "331AC0000000120_20260101_507AC0000000041" (:law/revision-id s))))))

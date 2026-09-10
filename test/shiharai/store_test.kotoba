(ns shiharai.store-test
  "The store's derived reads are what every hold stands on, so they are
  tested separately from the governor that consumes them."
  (:require [clojure.test :refer [deftest is testing]]
            [shiharai.fixtures :as f]
            [shiharai.store :as store]))

(deftest outstanding-is-nil-for-an-unknown-amount-never-zero
  (let [st (f/fresh-store)]
    (is (= 120000 (store/outstanding st "pbl-1")))
    (testing "a payable whose amount nobody recorded"
      (is (nil? (store/outstanding st "pbl-unknown")))
      (testing "and nil is not falsey-equal to a settled balance"
        (is (not= 0 (store/outstanding st "pbl-unknown")))))
    (testing "an unregistered payable is also unknown, not zero"
      (is (nil? (store/outstanding st "no-such-payable"))))))

(deftest committed-payments-consume-the-balance
  (let [st (f/fresh-store)]
    (store/commit-payment! st (assoc (f/payment :payment/amount-minor 50000)
                                     :payment/status :scheduled))
    (is (= 70000 (store/outstanding st "pbl-1")))
    (store/commit-payment! st (assoc (f/payment :payment/id "pay-2"
                                                :payment/amount-minor 70000)
                                     :payment/status :authorised))
    (is (= 0 (store/outstanding st "pbl-1")))))

(deftest a-payment-not-in-a-settling-status-does-not-consume-anything
  (let [st (f/fresh-store)]
    (store/commit-payment! st (assoc (f/payment) :payment/status :cancelled))
    (is (= 120000 (store/outstanding st "pbl-1")))))

(deftest excluding-a-payment-is-what-lets-a-release-cite-itself
  (let [st (f/fresh-store)]
    (store/commit-payment! st (assoc (f/payment) :payment/status :scheduled))
    (is (= 0 (store/outstanding st "pbl-1")))
    (is (= 120000 (store/outstanding st "pbl-1" "pay-1")))))

(deftest payments-are-keyed-so-a-release-replaces-rather-than-doubles
  (let [st (f/fresh-store)]
    (store/commit-payment! st (assoc (f/payment) :payment/status :scheduled))
    (store/commit-payment! st (assoc (f/payment) :payment/status :authorised))
    (is (= 1 (count (store/payments-for st "pbl-1"))))
    (is (= :authorised (:payment/status (store/payment st "pay-1"))))
    (is (= 0 (store/outstanding st "pbl-1")))))

(deftest destination-check-is-three-valued
  (let [st (f/fresh-store)]
    (testing "IBAN — checked by ISO 13616 mod-97"
      (let [d (store/destination-check (store/account-of st f/valid-iban))]
        (is (= :checked (:destination/coverage d)))
        (is (true? (:destination/valid? d)))
        (is (= "DE" (:iban/country (:destination/parsed d))))))
    (testing "IBAN with a broken checksum"
      (let [d (store/destination-check (store/account-of st f/bad-iban))]
        (is (= :checked (:destination/coverage d)))
        (is (false? (:destination/valid? d)))))
    (testing "a scheme with no validator here is unchecked, not valid"
      (let [d (store/destination-check (store/account-of st "0001-123-4567890"))]
        (is (= :unverified (:destination/coverage d)))
        (is (not (contains? d :destination/valid?)))
        (is (some? (:destination/why d)))))
    (testing "no account at all"
      (is (= :none (:destination/coverage (store/destination-check nil))))
      (is (= :none (:destination/coverage (store/destination-check {})))))))

(deftest the-ledger-is-append-only-in-use
  (let [st (f/fresh-store)]
    (store/append-ledger! st {:disposition :hold})
    (store/append-ledger! st {:disposition :commit})
    (is (= [:hold :commit] (mapv :disposition (store/ledger st))))))

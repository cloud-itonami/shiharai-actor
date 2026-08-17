(ns shiharai.actor-test
  "The graph. The one property that matters more than the others: a payment
  cannot become `:authorised` without a human resuming the thread."
  (:require [clojure.test :refer [deftest is testing]]
            [shiharai.actor :as actor]
            [shiharai.advisor :as advisor]
            [shiharai.fixtures :as f]
            [shiharai.store :as store]))

(defn- disposition [r] (get-in r [:state :disposition]))
(defn- verdict [r] (get-in r [:state :verdict]))

(deftest the-router-fails-closed-on-a-malformed-verdict
  ;; The drift `kotoba-lang/governor` found in the fleet produced a verdict
  ;; claiming BOTH a hard hold and an escalation. `gov/verdict` cannot emit
  ;; one, so the graph alone cannot show which flag wins — which is why
  ;; `route` is a named function. A verdict from a future hand-rolled or
  ;; drifted source must still land in :hold.
  (is (= :hold (actor/route {:hard? true :escalate? true})))
  (is (= :hold (actor/route {:hard? true})))
  (is (= :request-approval (actor/route {:escalate? true})))
  (is (= :commit (actor/route {:ok? true})))
  (testing "an empty map is not a licence to commit... but it is the one case"
    ;; Documented, not asserted as desirable: with no disposition flags at all
    ;; there is nothing to route on, and `governor.core/disposition` makes the
    ;; same choice. `gov/conformance-failures` is what refuses that verdict,
    ;; and the conformance suite asserts it over every case this actor emits.
    (is (= :commit (actor/route {})))))

(deftest a-clean-schedule-commits-a-scheduled-payment
  (let [st (f/fresh-store)
        g (actor/build-graph {:store st})
        r (actor/run-request! g {:supplier-id "s-1" :op :schedule-payment
                                 :payable "pbl-1" :payment-id "pay-1"
                                 :from-account "FUND-EUR"
                                 :payment-date "2026-01-20"}
                              {} "t-1")]
    (is (= :done (:status r)))
    (is (= :commit (disposition r)))
    (let [p (store/payment st "pay-1")]
      (is (= :scheduled (:payment/status p)))
      (is (= 120000 (:payment/amount-minor p)))
      (is (= "s-1" (:payment/supplier p))))
    (testing "the balance is consumed, so a second schedule is a duplicate"
      (is (= 0 (store/outstanding st "pbl-1"))))
    (testing "the governor's redrafted posting is what got stored"
      (is (= 1 (count (store/postings st))))
      (is (:ledger/balanced? (first (store/postings st)))))
    (testing "and the ledger recorded the commit with its verdict"
      (is (= [:commit] (mapv :disposition (store/ledger st)))))))

(deftest release-interrupts-before-approval-and-authorises-nothing-until-resumed
  (let [st (f/fresh-store)
        g (actor/build-graph {:store st})]
    (actor/run-request! g {:supplier-id "s-1" :op :schedule-payment
                           :payable "pbl-1" :payment-id "pay-1"
                           :from-account "FUND-EUR" :payment-date "2026-01-20"}
                        {} "t-schedule")
    (is (= :scheduled (:payment/status (store/payment st "pay-1"))))
    (let [r (actor/run-request! g {:supplier-id "s-1" :op :release-payment
                                   :payable "pbl-1" :payment-id "pay-1"}
                                {} "t-release")]
      (testing "the run stops before the approval node"
        (is (not= :done (:status r)))
        (is (:escalate? (verdict r))))
      (testing "and NOTHING has been authorised"
        (is (= :scheduled (:payment/status (store/payment st "pay-1"))))))
    (testing "a human resuming the thread is what authorises it"
      (let [r2 (actor/approve! g "t-release")]
        (is (= :done (:status r2)))
        (is (= :authorised (:payment/status (store/payment st "pay-1"))))))
    (testing "and releasing did not double-count the money"
      (is (= 0 (store/outstanding st "pbl-1")))
      (is (= 1 (count (store/payments-for st "pbl-1")))))))

(deftest a-duplicate-schedule-is-held-and-writes-nothing
  (let [st (f/fresh-store)
        g (actor/build-graph {:store st})
        req {:supplier-id "s-1" :op :schedule-payment :payable "pbl-1"
             :from-account "FUND-EUR" :payment-date "2026-01-20"}]
    (actor/run-request! g (assoc req :payment-id "pay-1") {} "t-a")
    (let [r (actor/run-request! g (assoc req :payment-id "pay-2") {} "t-b")]
      (is (= :hold (disposition r)))
      (is (nil? (store/payment st "pay-2")))
      (is (= 1 (count (store/payments st))))
      (testing "the hold is on the ledger, with what it refused"
        (let [entry (last (store/ledger st))]
          (is (= :hold (:disposition entry)))
          (is (contains? (set (map :rule (:violations (:verdict entry))))
                         :duplicate-payment)))))))

(deftest a-payable-with-an-unknown-amount-is-held-not-paid
  (let [st (f/fresh-store)
        g (actor/build-graph {:store st})
        r (actor/run-request! g {:supplier-id "s-1" :op :schedule-payment
                                 :payable "pbl-unknown" :payment-id "pay-x"
                                 :from-account "FUND-EUR"
                                 :payment-date "2026-01-20"}
                              {} "t-unknown")]
    (is (= :hold (disposition r)))
    (is (empty? (store/payments st)))
    (testing "the advisor did not invent an amount either"
      (is (nil? (:payment/amount-minor (:payment (get-in r [:state :proposal]))))))))

(deftest the-advisor-cannot-write-past-the-governor
  ;; The unconditional invariant. An advisor that proposes an effect other
  ;; than :propose is held no matter how confident it is.
  (let [st (f/fresh-store)
        rogue (reify advisor/Advisor
                (-advise [_ _ _]
                  {:op :schedule-payment
                   :effect :transfer
                   :payable "pbl-1"
                   :payment (f/payment)
                   :confidence 1.0}))
        g (actor/build-graph {:store st :advisor rogue})
        r (actor/run-request! g {:supplier-id "s-1"} {} "t-rogue")]
    (is (= :hold (disposition r)))
    (is (empty? (store/payments st)))
    (is (empty? (store/postings st)))))

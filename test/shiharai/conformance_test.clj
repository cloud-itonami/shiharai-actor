(ns shiharai.conformance-test
  "Every verdict this actor can emit is a well-formed verdict.

  `kotoba-lang/governor` measured 376 hand-copied governors in this fleet and
  found one that had drifted into reporting a HARD violation as escalatable,
  so an approval queue would show a permanently-refused operation as awaiting
  sign-off. The drift was invisible through the actor's own graph — the router
  tests `:hard?` first — so no ordinary test caught it. On an accounts-payable
  actor the same drift would invite a human to try to approve a duplicate
  payment.

  This suite does not check WHAT the governor decided; `governor_test` does
  that. It checks that whatever it decided is internally consistent, across
  every disposition this actor has."
  (:require [clojure.test :refer [deftest is testing]]
            [governor.core :as gov]
            [shiharai.fixtures :as f]
            [shiharai.governor :as governor]
            [shiharai.store :as store]))

(defn- with-scheduled []
  (let [st (f/fresh-store)]
    (store/commit-payment! st (assoc (f/payment) :payment/status :scheduled))
    st))

(defn- with-payable [f']
  (let [st (f/fresh-store)]
    (store/register-payable! st (f' (store/payable st "pbl-2")))
    st))

(def ^:private jp-payment
  (f/payment :payment/payable "pbl-2" :payment/amount-minor 500000
             :payment/currency "JPY" :payment/from-account "FUND-JPY"))

(def ^:private cases
  [{:name :clean
    :request f/request :proposal (f/proposal) :store f/fresh-store}
   {:name :hard/no-supplier
    :request {:supplier-id "nobody"} :proposal (f/proposal) :store f/fresh-store}
   {:name :hard/no-actuation
    :request f/request :proposal (f/proposal :effect :direct-write) :store f/fresh-store}
   {:name :hard/unknown-payable
    :request f/request
    :proposal (f/proposal :payable "nope" :payment (f/payment :payment/payable "nope"))
    :store f/fresh-store}
   {:name :hard/payable-wrong-supplier
    :request f/request
    :proposal (f/proposal :payable "pbl-2" :payment jp-payment)
    :store f/fresh-store}
   {:name :hard/unknown-amount
    :request f/request
    :proposal (f/proposal :payable "pbl-unknown"
                          :payment (f/payment :payment/payable "pbl-unknown"))
    :store f/fresh-store}
   {:name :hard/overpayment
    :request f/request
    :proposal (f/proposal :payment (f/payment :payment/amount-minor 999999))
    :store f/fresh-store}
   {:name :hard/duplicate
    :request f/request
    :proposal (f/proposal :payment (f/payment :payment/id "pay-2"))
    :store with-scheduled}
   {:name :hard/currency-mismatch
    :request f/request
    :proposal (f/proposal :payment (f/payment :payment/currency "USD"))
    :store f/fresh-store}
   {:name :hard/bad-funding-account
    :request f/request
    :proposal (f/proposal :payment (f/payment :payment/from-account "NOPE"))
    :store f/fresh-store}
   {:name :hard/posting-mismatch
    :request f/request
    :proposal (f/proposal :posting {:ledger/entries
                                    [{:ledger/account "AP" :ledger/side :debit
                                      :ledger/amount 1 :ledger/currency "EUR"}
                                     {:ledger/account "FUND-EUR" :ledger/side :credit
                                      :ledger/amount 1 :ledger/currency "EUR"}]})
    :store f/fresh-store}
   {:name :hard/statutory-term
    :request {:supplier-id "s-2"}
    :proposal (f/proposal :payable "pbl-2" :payment jp-payment)
    :store #(with-payable (fn [p] (assoc p :payable/due-date "2026-03-03")))}
   {:name :hard/statutory-undeterminable
    :request {:supplier-id "s-2"}
    :proposal (f/proposal :payable "pbl-2" :payment jp-payment)
    :store #(with-payable (fn [p] (dissoc p :payable/received-date)))}
   {:name :hard/credit-unsupported
    :request {:supplier-id "s-2"}
    :proposal (f/proposal :payable "pbl-2" :payment jp-payment)
    :store #(with-payable (fn [p] (assoc p :payable/registration-number "nope")))}
   {:name :hard/preservation
    :request {:supplier-id "s-2"}
    :proposal (f/proposal :payable "pbl-2" :payment jp-payment)
    :store #(with-payable (fn [p] (assoc p :payable/preservation :paper)))}
   {:name :escalate/release
    :request f/request
    :proposal (f/proposal :op :release-payment :confidence 1.0)
    :store with-scheduled}
   {:name :escalate/unverified-destination
    :request {:supplier-id "s-2"}
    :proposal (f/proposal :payable "pbl-2" :payment jp-payment)
    :store f/fresh-store}
   {:name :escalate/statutory-boundary
    :request {:supplier-id "s-2"}
    :proposal (f/proposal :payable "pbl-2" :payment jp-payment)
    :store #(with-payable (fn [p] (assoc p :payable/due-date "2026-03-02")))}
   {:name :escalate/after-due-date
    :request f/request
    :proposal (f/proposal :payment (f/payment :payment/date "2026-03-01"))
    :store f/fresh-store}
   {:name :escalate/low-confidence
    :request f/request :proposal (f/proposal :confidence 0.3) :store f/fresh-store}
   ;; A proposal that does not say how confident it is has not said it is
   ;; confident — the absent key must read as 0.0, never as trustworthy.
   {:name :escalate/no-confidence-key
    :request f/request :proposal (dissoc (f/proposal) :confidence) :store f/fresh-store}])

(defn- verdict-for [{:keys [request proposal store]}]
  (governor/check request {} proposal ((or store f/fresh-store))))

(deftest every-verdict-is-well-formed
  (doseq [{:keys [name] :as c} cases]
    (testing (str name)
      (let [v (verdict-for c)]
        (is (empty? (gov/conformance-failures v))
            (str "非適合: " (pr-str (gov/conformance-failures v))))))))

(deftest the-drift-that-happened-elsewhere-cannot-happen-here
  (doseq [{:keys [name] :as c} cases
          :let [v (verdict-for c)]
          :when (:hard? v)]
    (testing (str name)
      (is (not (:escalate? v))
          "an approver cannot be invited to wave through a HARD hold")
      (is (not (:ok? v)))
      (is (seq (:violations v)) "a hold must say what it refused"))))

(deftest escalation-carries-a-reason-a-human-can-act-on
  (doseq [{:keys [name] :as c} cases
          :let [v (verdict-for c)]
          :when (:escalate? v)]
    (testing (str name)
      (is (some? (:escalation-reason v)))
      (testing "and the specific causes travel beside the routing keyword"
        (is (or (= :low-confidence (:escalation-reason v))
                (seq (:escalations v)))))
      (is (every? :rule (:escalations v))))))

(deftest the-case-set-actually-covers-the-three-dispositions
  ;; Evidence floor. A conformance suite whose cases all landed in one
  ;; disposition would pass while checking almost nothing — and the count
  ;; here is asserted, not assumed, so deleting cases reddens this test.
  (let [vs (map verdict-for cases)]
    (is (= 21 (count cases)) "case count changed; update the floors deliberately")
    (is (>= (count (filter :ok? vs)) 1) "no clean case")
    (is (>= (count (filter :hard? vs)) 14) "HARD rules under-covered")
    (is (>= (count (filter :escalate? vs)) 6) "escalation under-covered")))

(deftest every-named-case-reached-the-disposition-its-name-claims
  ;; "skipped" and "passed" must be distinguishable: a case named :hard/x that
  ;; quietly landed in :ok? would still satisfy the conformance assertions
  ;; above while testing nothing.
  (doseq [{:keys [name] :as c} cases
          :let [v (verdict-for c)
                ns' (namespace name)]]
    (testing (str name)
      (case ns'
        "hard" (is (:hard? v) (str "expected a HARD hold, got " (pr-str v)))
        "escalate" (is (:escalate? v) (str "expected an escalation, got " (pr-str v)))
        (is (:ok? v) (str "expected a clean verdict, got "
                          (pr-str (:violations v))))))))

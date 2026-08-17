(ns shiharai.governor-test
  "Every HARD invariant fires, every escalation fires, and every question the
  governor deliberately did not answer is on the verdict where a reader can
  see it."
  (:require [clojure.test :refer [deftest is testing]]
            [shiharai.fixtures :as f]
            [shiharai.governor :as governor]
            [shiharai.store :as store]))

(defn- check
  ([proposal] (check f/request proposal (f/fresh-store)))
  ([request proposal] (check request proposal (f/fresh-store)))
  ([request proposal st] (governor/check request {} proposal st)))

(defn- rules [v] (set (map :rule (:violations v))))
(defn- escalation-rules [v] (set (map :rule (:escalations v))))

;; ---------------------------------------------------------------------------
;; The clean case — a floor for everything below
;; ---------------------------------------------------------------------------

(deftest a-clean-schedule-commits
  (let [v (check (f/proposal))]
    (is (:ok? v) (str "unexpected violations: " (pr-str (:violations v))))
    (is (not (:hard? v)))
    (is (not (:escalate? v)))
    (is (empty? (:violations v)))
    (testing "and the governor's own redraft of the posting balances"
      (is (:ledger/balanced? (:posting v))))))

;; ---------------------------------------------------------------------------
;; The four shared provenance rules (kotoba-lang/governor)
;; ---------------------------------------------------------------------------

(deftest unregistered-supplier-holds
  (let [v (check {:supplier-id "nobody"} (f/proposal))]
    (is (:hard? v))
    (is (contains? (rules v) :no-supplier))))

(deftest an-effect-other-than-propose-holds
  (doseq [e [:direct-write :transfer :execute nil]]
    (testing (pr-str e)
      (let [v (check (f/proposal :effect e))]
        (is (:hard? v))
        (is (contains? (rules v) :no-actuation))))))

(deftest an-unregistered-payable-holds
  (let [v (check (f/proposal :payable "no-such-payable"
                             :payment (f/payment :payment/payable "no-such-payable")))]
    (is (:hard? v))
    (is (contains? (rules v) :unknown-payable))))

(deftest a-payable-belonging-to-another-supplier-holds
  (let [v (check {:supplier-id "s-1"}
                 (f/proposal :payable "pbl-2"
                             :payment (f/payment :payment/payable "pbl-2"
                                                 :payment/currency "JPY"
                                                 :payment/amount-minor 500000
                                                 :payment/from-account "FUND-JPY")))]
    (is (:hard? v))
    (is (contains? (rules v) :payable-wrong-supplier))))

;; ---------------------------------------------------------------------------
;; Amounts — and the unknown that is neither 0 nor unlimited
;; ---------------------------------------------------------------------------

(deftest an-unknown-payable-amount-holds
  (let [v (check (f/proposal :payable "pbl-unknown"
                             :payment (f/payment :payment/payable "pbl-unknown")))]
    (is (:hard? v))
    (is (contains? (rules v) :unknown-payable-amount))
    (testing "and the outstanding balance is reported as nil, never as 0"
      (is (nil? (:outstanding v))))))

(deftest a-non-positive-or-non-integer-amount-holds
  (doseq [a [0 -1 nil 120000.5 "120000"]]
    (testing (pr-str a)
      (let [v (check (f/proposal :payment (f/payment :payment/amount-minor a)))]
        (is (:hard? v))
        (is (contains? (rules v) :invalid-amount))))))

(deftest overpayment-holds
  (let [v (check (f/proposal :payment (f/payment :payment/amount-minor 120001)))]
    (is (:hard? v))
    (is (contains? (rules v) :overpayment))))

(deftest a-partial-payment-is-fine-and-leaves-the-rest-outstanding
  (let [st (f/fresh-store)
        v1 (check f/request (f/proposal :payment (f/payment :payment/amount-minor 50000)) st)]
    (is (:ok? v1))
    (store/commit-payment! st (assoc (f/payment :payment/amount-minor 50000)
                                     :payment/status :scheduled))
    (let [v2 (check f/request
                    (f/proposal :payment (f/payment :payment/id "pay-2"
                                                    :payment/amount-minor 70000))
                    st)]
      (is (:ok? v2) (str (pr-str (:violations v2))))
      (is (= 70000 (:outstanding v2))))))

;; ---------------------------------------------------------------------------
;; Duplicate payment — the classic AP failure
;; ---------------------------------------------------------------------------

(deftest a-second-payment-against-a-settled-payable-holds
  (let [st (f/fresh-store)]
    (store/commit-payment! st (assoc (f/payment) :payment/status :scheduled))
    (let [v (check f/request (f/proposal :payment (f/payment :payment/id "pay-2")) st)]
      (is (:hard? v))
      (is (contains? (rules v) :duplicate-payment))
      (testing "and it is NOT reported as a mere overpayment, which names the wrong problem"
        (is (not (contains? (rules v) :overpayment))))
      (testing "duplicate payment has no approval route"
        (is (not (:escalate? v)))))))

(deftest a-scheduled-payment-consumes-the-balance-just-as-an-authorised-one-does
  (doseq [status [:scheduled :authorised]]
    (testing (pr-str status)
      (let [st (f/fresh-store)]
        (store/commit-payment! st (assoc (f/payment) :payment/status status))
        (let [v (check f/request (f/proposal :payment (f/payment :payment/id "pay-2")) st)]
          (is (contains? (rules v) :duplicate-payment)))))))

(deftest reusing-a-committed-payment-id-holds
  (let [st (f/fresh-store)]
    (store/commit-payment! st (assoc (f/payment :payment/amount-minor 1)
                                     :payment/status :scheduled))
    (let [v (check f/request (f/proposal :payment (f/payment :payment/amount-minor 1)) st)]
      (is (:hard? v))
      (is (contains? (rules v) :payment-id-reused)))))

(deftest a-payment-citing-a-different-payable-than-the-proposal-holds
  (let [v (check (f/proposal :payment (f/payment :payment/payable "pbl-2")))]
    (is (:hard? v))
    (is (contains? (rules v) :payment-cites-other-payable))))

;; ---------------------------------------------------------------------------
;; Currency — no FX table, so no guessing
;; ---------------------------------------------------------------------------

(deftest a-currency-mismatch-holds
  (let [v (check (f/proposal :payment (f/payment :payment/currency "USD")))]
    (is (:hard? v))
    (is (contains? (rules v) :currency-mismatch)))
  (testing "an undeclared currency is a mismatch, not a default"
    (let [v (check (f/proposal :payment (f/payment :payment/currency nil)))]
      (is (:hard? v))
      (is (contains? (rules v) :currency-mismatch)))))

;; ---------------------------------------------------------------------------
;; Accounts — delegated to kotoba-lang/banking
;; ---------------------------------------------------------------------------

(deftest an-iban-that-fails-mod-97-holds
  (let [v (check {:supplier-id "s-3"}
                 (f/proposal :payable "pbl-1"))]
    ;; s-3 does not own pbl-1, so this also trips ownership; the point is the
    ;; destination rule fires on the account itself.
    (is (contains? (rules v) :invalid-destination-account))))

(deftest a-supplier-with-no-account-holds
  (let [st (f/fresh-store)]
    (store/register-payable! st {:payable/id "pbl-4" :payable/supplier "s-4"
                                 :payable/amount-minor 1000 :payable/currency "EUR"})
    (let [v (check {:supplier-id "s-4"}
                   (f/proposal :payable "pbl-4"
                               :payment (f/payment :payment/payable "pbl-4"
                                                   :payment/amount-minor 1000))
                   st)]
      (is (:hard? v))
      (is (contains? (rules v) :no-destination-account)))))

(deftest an-unregistered-funding-account-holds
  (let [v (check (f/proposal :payment (f/payment :payment/from-account "NOT-REGISTERED")))]
    (is (:hard? v))
    (is (contains? (rules v) :unknown-funding-account)))
  (testing "and so does an unspecified one"
    (let [v (check (f/proposal :payment (f/payment :payment/from-account nil)))]
      (is (:hard? v))
      (is (contains? (rules v) :unknown-funding-account)))))

(deftest a-funding-account-in-the-wrong-currency-holds
  (let [v (check (f/proposal :payment (f/payment :payment/from-account "FUND-JPY")))]
    (is (:hard? v))
    (is (contains? (rules v) :funding-account-currency-mismatch))))

;; ---------------------------------------------------------------------------
;; The posting — redrafted, not trusted
;; ---------------------------------------------------------------------------

(deftest an-advisor-supplied-posting-that-differs-from-the-redraft-holds
  ;; Balanced, so `:unbalanced-posting` does not fire — and still wrong,
  ;; because it moves 1 cent instead of 120000. A posting can be internally
  ;; consistent and describe a different payment.
  (let [v (check (f/proposal :posting {:ledger/entries
                                       [{:ledger/account "AP" :ledger/side :debit
                                         :ledger/amount 1 :ledger/currency "EUR"}
                                        {:ledger/account "FUND-EUR" :ledger/side :credit
                                         :ledger/amount 1 :ledger/currency "EUR"}]}))]
    (is (:hard? v))
    (is (contains? (rules v) :posting-mismatch))
    (is (not (contains? (rules v) :unbalanced-posting)))))

(deftest an-advisor-supplied-posting-that-does-not-balance-holds
  ;; Found by the mutation harness: the rule used to guard the GOVERNOR's own
  ;; redraft, which balances by construction and so could never trip it.
  (let [v (check (f/proposal
                  :posting {:ledger/entries
                            [{:ledger/account "AP" :ledger/side :debit
                              :ledger/amount 120000 :ledger/currency "EUR"}
                             {:ledger/account "FUND-EUR" :ledger/side :credit
                              :ledger/amount 119000 :ledger/currency "EUR"}]}))]
    (is (:hard? v))
    (is (contains? (rules v) :unbalanced-posting))
    (testing "and it is not ALSO reported as a mismatch — one problem, one name"
      (is (not (contains? (rules v) :posting-mismatch))))))

(deftest the-redrafted-posting-is-a-real-double-entry
  (let [v (check (f/proposal))
        p (:posting v)]
    (is (= 2 (count (:ledger/entries p))))
    (is (= #{:debit :credit} (set (map :ledger/side (:ledger/entries p)))))
    (is (= #{"AP" "FUND-EUR"} (set (map :ledger/account (:ledger/entries p)))))
    (is (:ledger/balanced? p))
    (is (not (contains? p :ledger/unbalanced)))))

;; ---------------------------------------------------------------------------
;; Tax — three-valued, and all three are kept
;; ---------------------------------------------------------------------------

(deftest claiming-a-credit-in-an-uncatalogued-jurisdiction-holds
  (let [st (f/fresh-store)]
    (store/register-payable! st {:payable/id "pbl-atl" :payable/supplier "s-1"
                                 :payable/amount-minor 1000 :payable/currency "EUR"
                                 :payable/jurisdiction [:atlantis]
                                 :payable/claims-input-tax-credit? true
                                 :payable/registration-number "T1234567890123"})
    (let [v (check f/request
                   (f/proposal :payable "pbl-atl"
                               :payment (f/payment :payment/payable "pbl-atl"
                                                   :payment/amount-minor 1000))
                   st)]
      (is (:hard? v))
      (is (contains? (rules v) :unchecked-credit-jurisdiction))
      (is (= :none (:taxlaw/coverage (:tax v)))))))

(deftest claiming-a-credit-without-a-valid-registration-number-holds
  (doseq [n [nil "" "1234567890123" "T123"]]
    (testing (pr-str n)
      (let [st (f/fresh-store)]
        (store/register-payable! st (assoc (store/payable st "pbl-2")
                                           :payable/registration-number n))
        (let [v (check {:supplier-id "s-2"}
                       (f/proposal :payable "pbl-2"
                                   :payment (f/payment :payment/payable "pbl-2"
                                                       :payment/amount-minor 500000
                                                       :payment/currency "JPY"
                                                       :payment/from-account "FUND-JPY"))
                       st)]
          (is (:hard? v))
          (is (contains? (rules v) :input-tax-credit-unsupported)))))))

(deftest a-payable-that-claims-no-credit-is-not-held-but-says-so
  (let [v (check (f/proposal))]
    (is (:ok? v))
    (testing "the verdict states that nothing was claimed, so nothing was checked"
      (is (= :not-claimed (:taxlaw/coverage (:tax v))))
      (is (some? (:taxlaw/why (:tax v)))))))

(deftest paper-preservation-of-an-electronic-transaction-holds
  (let [st (f/fresh-store)]
    (store/register-payable! st (assoc (store/payable st "pbl-2")
                                       :payable/preservation :paper))
    (let [v (check {:supplier-id "s-2"}
                   (f/proposal :payable "pbl-2"
                               :payment (f/payment :payment/payable "pbl-2"
                                                   :payment/amount-minor 500000
                                                   :payment/currency "JPY"
                                                   :payment/from-account "FUND-JPY"))
                   st)]
      (is (:hard? v))
      (is (contains? (rules v) :electronic-record-not-preserved))
      (testing "and cites the article kotoba.taxlaw actually read"
        (is (re-find #"第七条" (:taxlaw/provision (:preservation v))))))))

(deftest an-undeclared-origin-is-reported-not-held
  ;; Asymmetric with the credit rule ON PURPOSE: a credit is an affirmative
  ;; claim and needs support; an undeclared origin claims nothing.
  (let [v (check (f/proposal))]
    (is (:ok? v))
    (is (= :not-declared (:taxlaw/coverage (:preservation v))))
    (is (some? (:taxlaw/why (:preservation v))))))

(deftest an-uncatalogued-jurisdiction-is-reported-as-none-on-the-preservation-axis
  ;; A payable claiming no credit in a jurisdiction nobody catalogued is not
  ;; held — it asserted nothing — but the verdict must not look like a pass.
  (let [st (f/fresh-store)]
    (store/register-payable! st (dissoc (store/payable st "pbl-1")
                                        :payable/jurisdiction))
    (let [v (check f/request (f/proposal) st)]
      (is (:ok? v))
      (is (= :none (:taxlaw/coverage (:preservation v))))
      (is (= [nil] (:taxlaw/unchecked (:preservation v)))))))

;; ---------------------------------------------------------------------------
;; 取適法 第三条
;; ---------------------------------------------------------------------------

(defn- jp-check [st]
  (check {:supplier-id "s-2"}
         (f/proposal :payable "pbl-2"
                     :payment (f/payment :payment/payable "pbl-2"
                                         :payment/amount-minor 500000
                                         :payment/currency "JPY"
                                         :payment/from-account "FUND-JPY"
                                         :payment/date "2026-01-20"))
         st))

(deftest a-term-beyond-sixty-days-holds
  (let [st (f/fresh-store)]
    (store/register-payable! st (assoc (store/payable st "pbl-2")
                                       :payable/due-date "2026-03-03"))
    (let [v (jp-check st)]
      (is (:hard? v))
      (is (contains? (rules v) :statutory-payment-term-exceeded)))))

(deftest a-term-of-exactly-sixty-days-escalates-instead-of-holding
  (let [st (f/fresh-store)]
    (store/register-payable! st (assoc (store/payable st "pbl-2")
                                       :payable/due-date "2026-03-02"))
    (let [v (jp-check st)]
      (is (not (:hard? v)) (str (pr-str (:violations v))))
      (is (:escalate? v))
      (is (contains? (escalation-rules v) :statutory-term-boundary)))))

(deftest an-in-scope-payable-with-no-receipt-date-holds
  (let [st (f/fresh-store)]
    (store/register-payable! st (dissoc (store/payable st "pbl-2")
                                        :payable/received-date))
    (let [v (jp-check st)]
      (is (:hard? v))
      (is (contains? (rules v) :statutory-term-undeterminable)))))

(deftest an-out-of-scope-payable-with-a-long-term-does-not-hold
  ;; The same 200-day term, on a payable that does not assert 第三条 applies.
  ;; Widening the article to every invoice would be enforcing a rule nobody
  ;; wrote.
  (let [st (f/fresh-store)]
    (store/register-payable! st (assoc (store/payable st "pbl-1")
                                       :payable/due-date "2026-07-20"))
    (let [v (check f/request (f/proposal) st)]
      (is (:ok? v) (str (pr-str (:violations v))))
      (is (= :not-declared (:law/coverage (:payment-terms v)))))))

;; ---------------------------------------------------------------------------
;; Escalations
;; ---------------------------------------------------------------------------

(deftest release-payment-always-escalates
  (let [st (f/fresh-store)]
    (store/commit-payment! st (assoc (f/payment) :payment/status :scheduled))
    (let [v (check f/request
                   (f/proposal :op :release-payment :confidence 1.0)
                   st)]
      (is (not (:hard? v)) (str (pr-str (:violations v))))
      (is (:escalate? v) "real fund movement is never automatic")
      (is (contains? (escalation-rules v) :release-payment))
      (testing "the release does not read as a duplicate of the payment it releases"
        (is (= 120000 (:outstanding v)))))))

(deftest releasing-something-that-was-never-scheduled-holds
  (let [v (check (f/proposal :op :release-payment))]
    (is (:hard? v))
    (is (contains? (rules v) :release-of-unscheduled-payment))))

(deftest releasing-an-altered-payment-holds
  (let [st (f/fresh-store)]
    (store/commit-payment! st (assoc (f/payment) :payment/status :scheduled))
    (let [v (check f/request
                   (f/proposal :op :release-payment
                               :payment (f/payment :payment/amount-minor 119999))
                   st)]
      (is (:hard? v))
      (is (contains? (rules v) :release-alters-scheduled-payment)))))

(deftest an-unverifiable-destination-scheme-escalates
  (let [v (check {:supplier-id "s-2"}
                 (f/proposal :payable "pbl-2"
                             :payment (f/payment :payment/payable "pbl-2"
                                                 :payment/amount-minor 500000
                                                 :payment/currency "JPY"
                                                 :payment/from-account "FUND-JPY")))]
    (is (not (:hard? v)) (str (pr-str (:violations v))))
    (is (:escalate? v))
    (is (contains? (escalation-rules v) :unverified-destination))
    (testing "and the verdict says it is unchecked, not valid"
      (is (= :unverified (:destination/coverage (:destination v))))
      (is (not (contains? (:destination v) :destination/valid?))))))

(deftest paying-after-the-due-date-escalates-and-is-labelled-non-statutory
  (let [v (check (f/proposal :payment (f/payment :payment/date "2026-03-01")))]
    (is (not (:hard? v)))
    (is (:escalate? v))
    (let [e (first (filter #(= :payment-after-due-date (:rule %)) (:escalations v)))]
      (is (some? e))
      (is (re-find #"違法と述べているものではない" (:detail e))))))

(deftest low-confidence-escalates
  (doseq [p [(f/proposal :confidence 0.3) (dissoc (f/proposal) :confidence)]]
    (let [v (check p)]
      (is (:escalate? v))
      (is (= :low-confidence (:escalation-reason v)))))
  (testing "an absent :confidence is 0.0, never trusted"
    (is (= 0.0 (:confidence (check (dissoc (f/proposal) :confidence)))))))

;; ---------------------------------------------------------------------------
;; What the verdict carries when it did not hold
;; ---------------------------------------------------------------------------

(deftest every-verdict-reports-what-it-did-and-did-not-check
  (doseq [k [:tax :preservation :destination :payment-terms :escalations]]
    (testing (str k)
      (is (contains? (check (f/proposal)) k)
          "a question the governor asked must be visible on the verdict"))))

(deftest a-hard-hold-is-never-offered-for-approval
  (doseq [p [(f/proposal :effect :direct-write)
             (f/proposal :payment (f/payment :payment/amount-minor 999999))
             (f/proposal :payment (f/payment :payment/currency "USD"))]]
    (let [v (check p)]
      (is (:hard? v))
      (is (not (:escalate? v)))
      (is (not (:ok? v)))
      (is (seq (:violations v))))))

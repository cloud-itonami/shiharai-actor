(ns shiharai.edge.endpoints-test
  "The surface, and what it cannot be talked into.

  Most of this file is about refusals, which is the right proportion for an
  accounts-payable endpoint: the interesting requests are the ones that
  should not work."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [shiharai.actor :as actor]
            [shiharai.edge.endpoints :as edge]
            [shiharai.fixtures :as f]
            [shiharai.store :as store]))

(def ^:private allowlist
  {"did:key:zAcme" "s-1"      ;; EUR, IBAN destination
   "did:key:zJp" "s-2"        ;; JPY, 全銀 destination — unverifiable here
   "did:key:zGhost" "s-nobody"}) ;; on the list, not in the store

(defn- seeded [] (f/fresh-store))

(defn- schedule-body [& {:as kv}]
  (pr-str (merge {:payable "pbl-1" :payment-id "pay-1"
                  :from-account "FUND-EUR" :payment-date "2026-01-20"}
                 kv)))

;; ---------------------------------------------------------------------------
;; The two gates, on every endpoint
;; ---------------------------------------------------------------------------

(deftest an-absent-allowlist-serves-503-on-every-endpoint
  (let [st (seeded)]
    (is (= 503 (:status (edge/register-payable-core! st nil "did:key:zAcme" "{}"))))
    (is (= 503 (:status (edge/propose-payment-core! st :ephemeral nil "did:key:zAcme"
                                                    (schedule-body)))))
    (is (= 503 (:status (edge/verdict-core! st nil "did:key:zAcme" "pay-1"))))
    (is (= 503 (:status (edge/ledger-core! st nil "did:key:zAcme"))))))

(deftest an-unlisted-caller-is-refused-on-every-endpoint
  (let [st (seeded)]
    (doseq [r [(edge/register-payable-core! st allowlist "did:key:zMallory" "{}")
               (edge/propose-payment-core! st :ephemeral allowlist "did:key:zMallory"
                                           (schedule-body))
               (edge/verdict-core! st allowlist "did:key:zMallory" "pay-1")
               (edge/ledger-core! st allowlist "did:key:zMallory")]]
      (is (= 403 (:status r))))))

;; ---------------------------------------------------------------------------
;; The ceiling: nothing here releases, and nothing here authorises
;; ---------------------------------------------------------------------------

(deftest releasing-and-approving-have-no-representation-on-this-surface
  (let [publics (set (keys (ns-publics 'shiharai.edge.endpoints)))]
    (is (contains? publics 'propose-payment-core!))
    (doseq [absent '[release-payment-core! approve-core! authorise-core!
                     authorize-core! resume-core!]]
      (is (not (contains? publics absent)) (str absent " must not exist")))
    (testing "and no public name here mentions releasing or approving at all"
      (is (empty? (filter #(re-find #"release|approv|authoris|authoriz|resume"
                                    (name %))
                          publics))))))

(deftest a-body-asking-for-a-release-is-scheduled-like-any-other
  (testing "the op is a constant, not a field — so there is no request that
            reaches the path that writes :authorised"
    (let [st (seeded)
          r (edge/propose-payment-core! st :ephemeral allowlist "did:key:zAcme"
                                        (schedule-body :op :release-payment))]
      (is (= 200 (:status r)))
      (is (true? (get-in r [:body :scheduled])))
      (is (false? (get-in r [:body :authorised])))
      (is (= :scheduled (:payment/status (store/payment st "pay-1")))))))

(deftest no-sequence-of-requests-authorises-anything
  (testing "schedule it, then hammer every endpoint with everything"
    (let [st (seeded)]
      (edge/propose-payment-core! st :ephemeral allowlist "did:key:zAcme" (schedule-body))
      (dotimes [_ 5]
        (edge/propose-payment-core! st :ephemeral allowlist "did:key:zAcme"
                                    (schedule-body :op :release-payment))
        (edge/propose-payment-core! st :ephemeral allowlist "did:key:zAcme"
                                    (schedule-body :payment-id "pay-2"))
        (edge/verdict-core! st allowlist "did:key:zAcme" "pay-1")
        (edge/ledger-core! st allowlist "did:key:zAcme"))
      (is (= [:scheduled] (distinct (mapv :payment/status (store/payments st)))))
      (is (not-any? #(= :authorised (:payment/status %)) (store/payments st))))))

;; ---------------------------------------------------------------------------
;; POST /api/payable/register
;; ---------------------------------------------------------------------------

(deftest a-payable-registers-against-the-callers-own-supplier
  (let [st (seeded)
        r (edge/register-payable-core!
           st allowlist "did:key:zAcme"
           (pr-str {:payable-id "pbl-9" :amount-minor 4200 :currency "EUR"
                    :payable/received-date "2026-03-01"
                    :payable/due-date "2026-03-31"}))]
    (is (= 201 (:status r)))
    (is (= "s-1" (get-in r [:body :supplier])))
    (is (= 4200 (get-in r [:body :outstanding])))
    (let [p (store/payable st "pbl-9")]
      (is (= "s-1" (:payable/supplier p)))
      (is (= 4200 (:payable/amount-minor p)))
      (is (= "2026-03-31" (:payable/due-date p))))))

(deftest a-body-may-not-name-a-supplier
  (testing "silently ignoring it is how a caller comes to believe they
            registered an invoice against a supplier they never touched"
    (doseq [k [:supplier-id :supplier :payable/supplier]]
      (let [st (seeded)
            r (edge/register-payable-core!
               st allowlist "did:key:zAcme"
               (pr-str {:payable-id "pbl-9" :currency "EUR" k "s-2"}))]
        (is (= 400 (:status r)) (str "should refuse a body carrying " k))
        (is (nil? (store/payable st "pbl-9")) "and it wrote nothing")))))

(deftest a-body-naming-a-supplier-is-refused-on-the-payment-endpoint-too
  (let [r (edge/propose-payment-core! (seeded) :ephemeral allowlist "did:key:zAcme"
                                      (pr-str {:payable "pbl-1" :payment-id "pay-1"
                                               :from-account "FUND-EUR"
                                               :supplier-id "s-2"}))]
    (is (= 400 (:status r)))))

(deftest a-payable-with-no-id-or-no-currency-is-400
  (doseq [bad ["" "((" "[1 2]"
               (pr-str {:currency "EUR"})
               (pr-str {:payable-id "" :currency "EUR"})
               (pr-str {:payable-id "pbl-9"})
               (pr-str {:payable-id "pbl-9" :currency "  "})]]
    (is (= 400 (:status (edge/register-payable-core! (seeded) allowlist
                                                     "did:key:zAcme" bad)))
        (str "should reject " (pr-str bad)))))

(deftest an-amount-that-is-not-a-positive-integer-is-refused-rather-than-rounded
  (doseq [bad [0 -1 1.5 "4200"]]
    (let [r (edge/register-payable-core!
             (seeded) allowlist "did:key:zAcme"
             (pr-str {:payable-id "pbl-9" :currency "EUR" :amount-minor bad}))]
      (is (= 400 (:status r)) (str "should reject amount " (pr-str bad)))
      (is (re-find #"omit it" (get-in r [:body :hint]))))))

(deftest an-absent-amount-registers-as-UNKNOWN-and-the-response-says-so
  (testing "not 0. A payable worth nothing would be refused forever; a payable
            worth an unknown amount is a payable nobody may pay yet"
    (let [st (seeded)
          r (edge/register-payable-core! st allowlist "did:key:zAcme"
                                         (pr-str {:payable-id "pbl-9" :currency "EUR"}))]
      (is (= 201 (:status r)))
      (is (= :unknown (get-in r [:body :outstanding])))
      (is (re-find #"UNKNOWN" (get-in r [:body :note])))
      (is (nil? (:payable/amount-minor (store/payable st "pbl-9"))))
      (testing "and proposing against it is held, not paid"
        (let [p (edge/propose-payment-core! st :ephemeral allowlist "did:key:zAcme"
                                            (schedule-body :payable "pbl-9"
                                                           :payment-id "pay-9"))]
          (is (= 409 (:status p)))
          (is (contains? (set (map :rule (get-in p [:body :violations])))
                         :unknown-payable-amount))
          (is (= :unknown (get-in p [:body :outstanding]))))))))

(deftest a-caller-whose-supplier-is-not-registered-is-told-that-and-writes-nothing
  (let [st (seeded)
        r (edge/register-payable-core! st allowlist "did:key:zGhost"
                                       (pr-str {:payable-id "pbl-9" :currency "EUR"}))]
    (is (= 409 (:status r)))
    (is (= "supplier not registered" (get-in r [:body :error])))
    (is (nil? (store/payable st "pbl-9")))))

(deftest an-existing-payable-id-is-refused-rather-than-overwritten
  (testing "overwriting :payable/amount-minor moves the ceiling that
            :overpayment and :duplicate-payment are measured against, under
            payments that may already be scheduled"
    (let [st (seeded)
          r (edge/register-payable-core! st allowlist "did:key:zAcme"
                                         (pr-str {:payable-id "pbl-1"
                                                  :currency "EUR"
                                                  :amount-minor 99999999}))]
      (is (= 409 (:status r)))
      (is (= 120000 (:payable/amount-minor (store/payable st "pbl-1")))))))

(deftest a-caller-cannot-take-over-another-suppliers-payable-id
  (testing "s-2 registering pbl-1 would, if it upserted, reassign s-1's
            invoice to s-2 in one request"
    (let [st (seeded)
          r (edge/register-payable-core! st allowlist "did:key:zJp"
                                         (pr-str {:payable-id "pbl-1" :currency "JPY"}))]
      (is (= 409 (:status r)))
      (is (= "s-1" (:payable/supplier (store/payable st "pbl-1")))))))

;; ---------------------------------------------------------------------------
;; POST /api/payment/propose — three outcomes, kept three
;; ---------------------------------------------------------------------------

(deftest a-clean-proposal-schedules-and-reports-what-was-not-checked
  (let [st (seeded)
        r (edge/propose-payment-core! st :ephemeral allowlist "did:key:zAcme"
                                      (schedule-body))]
    (is (= 200 (:status r)))
    (is (true? (get-in r [:body :scheduled])))
    (is (= :scheduled (get-in r [:body :payment-status])))
    (is (= 120000 (get-in r [:body :outstanding])))
    (is (= 0 (get-in r [:body :outstanding-now])))
    (testing "the green verdict still shows the questions"
      (is (= :checked (get-in r [:body :destination :destination/coverage])))
      (is (= :not-claimed (get-in r [:body :tax :taxlaw/coverage])))
      (is (contains? (:body r) :preservation))
      (is (contains? (:body r) :payment-terms))
      (is (= [] (get-in r [:body :escalations]))))))

(deftest an-escalation-is-202-and-nothing-is-scheduled
  (testing "s-2's destination is a 全銀 account this repo has no validator
            for — neither valid nor invalid, so a person decides"
    (let [st (seeded)
          r (edge/propose-payment-core! st :ephemeral allowlist "did:key:zJp"
                                        (pr-str {:payable "pbl-2" :payment-id "pay-jp"
                                                 :from-account "FUND-JPY"
                                                 :payment-date "2026-01-20"}))]
      (is (= 202 (:status r)))
      (is (false? (get-in r [:body :scheduled])))
      (is (false? (get-in r [:body :authorised])))
      (is (= :request-approval (get-in r [:body :disposition])))
      (is (contains? (set (map :rule (get-in r [:body :escalations])))
                     :unverified-destination))
      (testing "and NOTHING was written"
        (is (nil? (store/payment st "pay-jp")))
        (is (= 500000 (store/outstanding st "pbl-2"))))
      (testing "202 is neither of its neighbours — not a success, not a hold"
        (is (not= 200 (:status r)))
        (is (not= 409 (:status r)))))))

(deftest an-interrupted-thread-cannot-be-resumed-through-this-surface
  (testing "the graph is built per request, so the checkpoint an approval
            would resume does not outlive the response"
    (let [st (seeded)]
      (edge/propose-payment-core! st :ephemeral allowlist "did:key:zJp"
                                  (pr-str {:payable "pbl-2" :payment-id "pay-jp"
                                           :from-account "FUND-JPY"}))
      (is (nil? (store/payment st "pay-jp")))
      (testing "a fresh graph knows nothing of that thread, and approving it
                writes nothing rather than authorising it"
        (let [g (actor/build-graph {:store st})]
          (actor/approve! g "edge-did:key:zJp-pay-jp")
          (is (nil? (store/payment st "pay-jp"))))))))

(deftest a-hold-is-409-with-the-rules-that-fired
  (let [st (seeded)]
    (edge/propose-payment-core! st :ephemeral allowlist "did:key:zAcme" (schedule-body))
    (let [r (edge/propose-payment-core! st :ephemeral allowlist "did:key:zAcme"
                                        (schedule-body :payment-id "pay-2"))]
      (is (= 409 (:status r)))
      (is (true? (get-in r [:body :hard?])))
      (is (contains? (set (map :rule (get-in r [:body :violations])))
                     :duplicate-payment))
      (is (nil? (store/payment st "pay-2"))))))

(deftest the-supplier-comes-from-the-did-so-cross-supplier-payment-is-blocked
  (testing "zJp is s-2; pbl-1 belongs to s-1"
    (let [r (edge/propose-payment-core! (seeded) :ephemeral allowlist "did:key:zJp"
                                        (schedule-body :from-account "FUND-JPY"))]
      (is (= 409 (:status r)))
      (is (contains? (set (map :rule (get-in r [:body :violations])))
                     :payable-wrong-supplier)))))

(deftest a-proposal-missing-what-a-payment-needs-is-400-not-a-verdict
  (doseq [bad ["" "not-edn(" "[1 2]"
               (pr-str {:payment-id "pay-1" :from-account "FUND-EUR"})
               (pr-str {:payable "pbl-1" :from-account "FUND-EUR"})
               (pr-str {:payable "pbl-1" :payment-id "pay-1"})]]
    (is (= 400 (:status (edge/propose-payment-core! (seeded) :ephemeral allowlist
                                                    "did:key:zAcme" bad)))
        (str "should reject " (pr-str bad)))))

;; ---------------------------------------------------------------------------
;; GET /api/payment/verdict — unknown is never a pass
;; ---------------------------------------------------------------------------

(deftest a-payment-nobody-asked-about-answers-unknown-and-not-an-empty-success
  (let [r (edge/verdict-core! (seeded) allowlist "did:key:zAcme" "pay-never")]
    (is (= 404 (:status r)))
    (is (= :unknown (get-in r [:body :verdict])))
    (is (false? (get-in r [:body :ok])))
    (is (re-find #"not a pass" (get-in r [:body :hint])))))

(deftest a-blank-or-non-string-payment-id-is-400-and-not-404
  (testing "400 and 404 are different sentences: one says the request was
            malformed, the other says the actor was never asked about this
            payment. Answering 404 to a malformed id tells the caller their
            id is fine and merely unknown."
    (doseq [bad [nil "" "   " 42 :pay-1 ["pay-1"]]]
      (is (= 400 (:status (edge/verdict-core! (seeded) allowlist "did:key:zAcme" bad)))
          (str "should reject " (pr-str bad))))))

(deftest a-non-string-id-is-refused-on-the-writing-endpoints-too
  (doseq [bad [42 :pbl-9 ["pbl-9"] {}]]
    (is (= 400 (:status (edge/register-payable-core!
                         (seeded) allowlist "did:key:zAcme"
                         (pr-str {:payable-id bad :currency "EUR"}))))
        (str "register should reject " (pr-str bad)))
    (is (= 400 (:status (edge/propose-payment-core!
                         (seeded) :ephemeral allowlist "did:key:zAcme"
                         (pr-str {:payable bad :payment-id "pay-1"
                                  :from-account "FUND-EUR"}))))
        (str "propose should reject " (pr-str bad)))))

(deftest a-committed-payments-verdict-reads-back
  (let [st (seeded)]
    (edge/propose-payment-core! st :ephemeral allowlist "did:key:zAcme" (schedule-body))
    (let [r (edge/verdict-core! st allowlist "did:key:zAcme" "pay-1")]
      (is (= 200 (:status r)))
      (is (= :commit (get-in r [:body :disposition])))
      (is (= :scheduled (get-in r [:body :payment-status])))
      (is (false? (get-in r [:body :hard?])))
      (is (= [] (get-in r [:body :violations]))))))

(deftest a-held-payments-verdict-reads-back-as-held-and-not-committed
  (let [st (seeded)]
    (edge/propose-payment-core! st :ephemeral allowlist "did:key:zAcme" (schedule-body))
    (edge/propose-payment-core! st :ephemeral allowlist "did:key:zAcme"
                                (schedule-body :payment-id "pay-2"))
    (let [r (edge/verdict-core! st allowlist "did:key:zAcme" "pay-2")]
      (is (= 200 (:status r)))
      (is (= :hold (get-in r [:body :disposition])))
      (is (true? (get-in r [:body :hard?])))
      (testing ":not-committed rather than nil — a held payment has no record,
                and that is a state rather than a missing field"
        (is (= :not-committed (get-in r [:body :payment-status]))))
      (is (contains? (set (map :rule (get-in r [:body :violations])))
                     :duplicate-payment)))))

(deftest another-suppliers-payment-id-answers-404-and-not-403
  (testing "403 would confirm the id exists, which is a fact about another
            supplier's payables"
    (let [st (seeded)]
      (edge/propose-payment-core! st :ephemeral allowlist "did:key:zAcme" (schedule-body))
      (is (some? (store/payment st "pay-1")) "it does exist")
      (let [r (edge/verdict-core! st allowlist "did:key:zJp" "pay-1")]
        (is (= 404 (:status r)))
        (is (= :unknown (get-in r [:body :verdict])))))))

;; ---------------------------------------------------------------------------
;; GET /api/ledger
;; ---------------------------------------------------------------------------

(deftest the-ledger-is-scoped-to-the-caller-and-says-that-it-is
  (let [st (seeded)]
    (edge/propose-payment-core! st :ephemeral allowlist "did:key:zAcme" (schedule-body))
    (edge/propose-payment-core! st :ephemeral allowlist "did:key:zJp"
                                (schedule-body :from-account "FUND-JPY"))
    (let [r (edge/ledger-core! st allowlist "did:key:zAcme")]
      (is (= 200 (:status r)))
      (is (= :supplier (get-in r [:body :scope])))
      (is (= 1 (get-in r [:body :visible])))
      (is (= ["pay-1"] (mapv :payment (get-in r [:body :entries]))))
      (is (= [:commit] (mapv :disposition (get-in r [:body :entries])))))
    (testing "and the other supplier sees only their own hold"
      (let [r (edge/ledger-core! st allowlist "did:key:zJp")]
        (is (= 1 (get-in r [:body :visible])))
        (is (= [:hold] (mapv :disposition (get-in r [:body :entries]))))))))

(deftest an-empty-ledger-is-a-true-answer-and-reports-zero-visible
  (let [r (edge/ledger-core! (seeded) allowlist "did:key:zAcme")]
    (is (= 200 (:status r)))
    (is (= 0 (get-in r [:body :visible])))
    (is (= [] (get-in r [:body :entries])))
    (is (= 0 (get-in r [:body :unattributed])))))

(deftest an-unattributable-ledger-entry-is-counted-rather-than-hidden
  (testing "a filtered view that cannot say it is incomplete is a view that
            claims to be the whole thing"
    (let [st (seeded)]
      (store/append-ledger! st {:disposition :hold :verdict {:hard? true}})
      (let [r (edge/ledger-core! st allowlist "did:key:zAcme")]
        (is (= 0 (get-in r [:body :visible])))
        (is (= 1 (get-in r [:body :unattributed])))))))

;; ---------------------------------------------------------------------------
;; Store configuration
;; ---------------------------------------------------------------------------

(deftest store-mode-reads-only-values-it-recognises
  (is (nil? (edge/store-mode {})))
  (is (nil? (edge/store-mode {"SHIHARAI_STORE" ""})))
  (is (= :ephemeral (edge/store-mode {"SHIHARAI_STORE" "ephemeral"})))
  (is (= :ephemeral (edge/store-mode {"SHIHARAI_STORE" "  ephemeral  "})))
  (is (= :journalled (edge/store-mode {"SHIHARAI_STORE" "journalled"})))
  (testing "a typo in a deployment variable must not silently select a mode"
    (is (nil? (edge/store-mode {"SHIHARAI_STORE" "ephemral"})))
    (is (nil? (edge/store-mode {"SHIHARAI_STORE" "journaled"})))
    (is (nil? (edge/store-mode {"SHIHARAI_STORE" "durable"})))))

(deftest an-unconfigured-store-serves-503-and-says-whose-fault-it-is
  (let [r (edge/store-unconfigured-response)]
    (is (= 503 (:status r)))
    (testing "not a 409 :no-supplier — an empty in-process store would fail
              the governor's registration check and blame the CALLER for a
              deployment that has no store at all"
      (is (= "no store configured" (get-in r [:body :error])))
      (is (re-find #"journalled" (get-in r [:body :hint]))))))

(deftest a-journalled-store-does-not-get-to-call-itself-durable
  (testing "this repo cannot vouch for a host it cannot see"
    (is (= :none (edge/persistence-of :ephemeral)))
    (is (= :delegated (edge/persistence-of :journalled)))
    (is (= :unknown (edge/persistence-of nil)))
    (testing "and the mode rides out on every success response"
      (is (= :none (get-in (edge/propose-payment-core! (seeded) :ephemeral allowlist
                                                       "did:key:zAcme" (schedule-body))
                           [:body :persistence])))
      (is (= :delegated (get-in (edge/propose-payment-core! (seeded) :journalled allowlist
                                                            "did:key:zAcme" (schedule-body))
                                [:body :persistence]))))))

;; ---------------------------------------------------------------------------
;; The two gaps, closed together
;; ---------------------------------------------------------------------------

(deftest the-surface-behaves-the-same-on-the-durable-store-and-survives-a-restart
  (testing "an edge that only worked over a store the process owns would be
            an edge that forgets what it paid"
    (let [log (atom [])
          st (f/seed! (store/datomic-store (store/journal log)))
          first-r (edge/propose-payment-core! st :journalled allowlist "did:key:zAcme"
                                              (schedule-body))]
      (is (= 200 (:status first-r)))
      (is (true? (get-in first-r [:body :scheduled])))
      ;; drop the store; rebuild from the journal's bytes alone
      (let [st2 (store/datomic-store
                 (store/journal (atom (edn/read-string (pr-str @log)))))
            second-r (edge/propose-payment-core! st2 :journalled allowlist "did:key:zAcme"
                                                 (schedule-body :payment-id "pay-2"))]
        (is (= 409 (:status second-r)))
        (is (contains? (set (map :rule (get-in second-r [:body :violations])))
                       :duplicate-payment))
        (testing "and the verdict endpoint can still answer for the first one"
          (let [v (edge/verdict-core! st2 allowlist "did:key:zAcme" "pay-1")]
            (is (= 200 (:status v)))
            (is (= :commit (get-in v [:body :disposition])))
            (is (= :scheduled (get-in v [:body :payment-status])))))))))

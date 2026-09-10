(ns shiharai.jurisdiction-test
  "What this actor answers for a supplier invoice outside Japan.

  The measurement that motivates the whole file: `kotoba.taxlaw` moved from
  per-jurisdiction coverage to per-FACET coverage and gained `[:eu]` and
  `[:us]`. Loading both pins side by side over every (jurisdiction,
  registration-number) and (jurisdiction, origin, preservation) pair this
  actor can produce showed exactly one value change reachable from here:
  `credit-support [:eu]` went from `:none` to `:checked`, so an EU input-tax
  claim stopped being held. `[:us]` stayed `:none` on both axes and `[:jp]`
  was identical on every shared key.

  **The suite did not notice, because no fixture was `[:eu]`.** That is the
  gap these tests close, and the reason the first one below compares a
  jurisdiction that was just added against one that will never exist."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.taxlaw :as taxlaw]
            [shiharai.actor :as actor]
            [shiharai.edge.endpoints :as ep]
            [shiharai.fixtures :as f]
            [shiharai.governor :as governor]
            [shiharai.jurisdiction :as juris]
            [shiharai.store :as store]))

(defn- rules [v] (set (map :rule (:violations v))))
(defn- escalation-rules [v] (set (map :rule (:escalations v))))

(def ^:private base
  "A payable that differs from its siblings in NOTHING but the two tax keys
  the tests vary. Supplier `s-1` is EUR with a mod-97-valid IBAN and is not a
  中小受託事業者, so no destination check, no 取適法 第三条 and no currency
  rule can fire — anything the verdict says is attributable to the
  jurisdiction."
  {:payable/supplier "s-1"
   :payable/amount-minor 120000
   :payable/currency "EUR"
   :payable/received-date "2026-01-01"
   :payable/due-date "2026-02-01"
   :payable/claims-input-tax-credit? true})

(defn- store-with
  "A fixture store carrying one extra payable `pbl-x`, built from `base`."
  [& {:as kv}]
  (let [st (f/fresh-store)]
    (store/register-payable! st (merge base {:payable/id "pbl-x"} kv))
    st))

(defn- verdict-for [& {:as kv}]
  (governor/check f/request {}
                  (f/proposal :payable "pbl-x"
                              :payment (f/payment :payment/payable "pbl-x"))
                  (apply store-with (mapcat identity kv))))

;; ---------------------------------------------------------------------------
;; 1. Adding a jurisdiction did not make anything payable
;; ---------------------------------------------------------------------------

(deftest a-us-payable-is-refused-exactly-as-hard-as-one-in-a-jurisdiction-that-does-not-exist
  ;; The whole point of the pin bump, stated about THIS actor's verdict rather
  ;; than about `kotoba.taxlaw`'s return value. `[:atlantis]` is the control:
  ;; it was uncatalogued before the bump and is uncatalogued after it, so a
  ;; US payable that behaves identically has not been widened by anything.
  (let [us (verdict-for :payable/jurisdiction [:us]
                        :payable/registration-number "12-3456789")
        atlantis (verdict-for :payable/jurisdiction [:atlantis]
                              :payable/registration-number "12-3456789")]
    (testing "both are HARD holds, and the United States is not the softer one"
      (doseq [[label v] [["us" us] ["atlantis" atlantis]]]
        (testing label
          (is (:hard? v))
          (is (not (:ok? v)))
          (is (not (:escalate? v))
              "a HARD hold must never invite an approver")
          (is (= :none (:taxlaw/coverage (:tax v))))
          (is (= :hold (actor/route v))))))
    (testing "and the two verdicts agree on every flag that decides an outcome"
      (is (= (select-keys us [:ok? :hard? :escalate?])
             (select-keys atlantis [:ok? :hard? :escalate?]))))))

(deftest the-united-states-being-in-the-catalog-schedules-no-payment
  ;; The verdict is one thing; what the graph WROTE is another. A hold that
  ;; left a payment record behind would be a hold nobody could rely on.
  (let [st (store-with :payable/jurisdiction [:us]
                       :payable/registration-number "12-3456789")
        g (actor/build-graph {:store st})
        r (actor/run-request! g {:supplier-id "s-1" :op :schedule-payment
                                 :payable "pbl-x" :payment-id "pay-us"
                                 :from-account "FUND-EUR"
                                 :payment-date "2026-01-20"}
                              {} "t-us")]
    (is (= :hold (get-in r [:state :disposition])))
    (is (nil? (store/payment st "pay-us"))
        "nothing may be written for a payable whose credit claim is held")
    (testing "and the hold is on the audit trail, attributed"
      (let [e (last (store/ledger st))]
        (is (= :hold (:disposition e)))
        (is (= "pay-us" (:payment-id e)))
        (is (contains? (rules (:verdict e)) :credit-jurisdiction-out-of-scope))))))

(deftest the-two-none-refusals-send-a-reader-to-different-places
  ;; Equally hard, differently named. `:unchecked-credit-jurisdiction` means
  ;; somebody could go and read a law; the US case means there is no federal
  ;; law to read, because there is no federal VAT.
  (let [us (verdict-for :payable/jurisdiction [:us])
        atlantis (verdict-for :payable/jurisdiction [:atlantis])]
    (is (contains? (rules us) :credit-jurisdiction-out-of-scope))
    (is (not (contains? (rules us) :unchecked-credit-jurisdiction)))
    (is (contains? (rules atlantis) :unchecked-credit-jurisdiction))
    (is (not (contains? (rules atlantis) :credit-jurisdiction-out-of-scope)))
    (testing "and the US refusal carries the reason rather than a bare name"
      (let [v (first (filter #(= :credit-jurisdiction-out-of-scope (:rule %))
                             (:violations us)))]
        (is (= :jurisdiction/input-tax-credit (:out-of-scope v)))
        (is (re-find #"no federal VAT" (:why v)))
        (is (re-find #"no federal VAT" (:detail v))
            "the operator reads :detail, so the reason has to be in it")))
    (testing "the uncatalogued one carries no out-of-scope key to read"
      (is (nil? (:taxlaw/out-of-scope (:tax atlantis)))))))

;; ---------------------------------------------------------------------------
;; 2. The EU — one thing checked, and it says which
;; ---------------------------------------------------------------------------

(deftest an-eu-registration-number-is-checked-for-its-prefix-and-nothing-else
  (testing "the catalog accepts a well-formed prefix"
    (is (taxlaw/registration-number-valid? [:eu] "DE811907980")))
  (testing "and accepts XX1, because Article 215 gives the prefix and no more"
    (is (taxlaw/registration-number-valid? [:eu] "XX1"))))

(deftest an-eu-input-tax-claim-reaches-a-human-rather-than-a-schedule
  ;; `XX1` is the case that must not go through. It satisfies every format
  ;; rule the Directive states and names no Member State that exists.
  (doseq [n ["DE811907980" "XX1"]]
    (testing n
      (let [v (verdict-for :payable/jurisdiction [:eu]
                           :payable/registration-number n)]
        (testing "taxlaw supports it — the Directive analogue was read"
          (is (= :checked (:taxlaw/coverage (:tax v))))
          (is (true? (:taxlaw/supported? (:tax v)))))
        (testing "and this actor still does not schedule it"
          (is (not (:ok? v)))
          (is (:escalate? v))
          (is (not (:hard? v)))
          (is (= :request-approval (actor/route v)))
          (is (contains? (escalation-rules v)
                         :registration-format-partially-checked)))))))

(deftest the-eu-verdict-names-what-was-not-checked
  (let [v (verdict-for :payable/jurisdiction [:eu]
                       :payable/registration-number "DE811907980")
        gap (:registration-gap v)]
    (is (= #{:prefix-shape} (:registration/checked gap)))
    (is (= #{:member-state-is-a-member :body-format :check-digit}
           (:registration/not-checked gap)))
    (is (= :iso-3166-alpha-2-prefix (:registration/kind gap)))
    (testing "and the escalation a human reads repeats it, not just the flag"
      (let [e (first (filter #(= :registration-format-partially-checked (:rule %))
                             (:escalations v)))]
        (is (= #{:member-state-is-a-member :body-format :check-digit}
               (:not-checked e)))
        (is (re-find #"member-state-is-a-member" (:detail e)))))))

(deftest a-japanese-registration-number-on-an-eu-payable-is-held-and-named-so
  ;; `T`+13 digits is the Japanese format. In the EU it fails Article 215's
  ;; prefix shape, and the refusal must not call it a 適格請求書発行事業者の
  ;; 登録番号 — there is no Japanese register with an entry for a German
  ;; supplier.
  (let [v (verdict-for :payable/jurisdiction [:eu]
                       :payable/registration-number "T1234567890123")
        d (:detail (first (filter #(= :input-tax-credit-unsupported (:rule %))
                                  (:violations v))))]
    (is (:hard? v))
    (is (contains? (rules v) :input-tax-credit-unsupported))
    (is (re-find #"\[:eu\]" d))
    (is (not (re-find #"適格請求書発行事業者" d)))))

(deftest japan-still-names-its-own-register
  (let [v (verdict-for :payable/jurisdiction [:jp]
                       :payable/registration-number "nope")
        d (:detail (first (filter #(= :input-tax-credit-unsupported (:rule %))
                                  (:violations v))))]
    (is (:hard? v))
    (is (re-find #"適格請求書発行事業者" d))))

(deftest a-japanese-claim-does-not-escalate-on-a-format-gap
  ;; The Japanese catalog entry declares no format metadata, so there is no
  ;; declared shortfall to escalate on. This asserts the absence rather than
  ;; assuming it: if the catalog ever declares one, this test reddens and the
  ;; behaviour change is a decision somebody makes on purpose.
  (let [v (verdict-for :payable/jurisdiction [:jp]
                       :payable/registration-number "T1234567890123")]
    (is (:ok? v))
    (is (nil? (:registration-gap v)))
    (is (not (contains? (escalation-rules v)
                        :registration-format-partially-checked)))))

;; ---------------------------------------------------------------------------
;; The electronic-record asymmetry
;; ---------------------------------------------------------------------------

(deftest an-eu-electronic-invoice-kept-on-paper-is-not-reported-as-preserved
  ;; 電子帳簿保存法 第七条 obliges the HOLDER to preserve; Directive Articles
  ;; 218 and 246 oblige the MEMBER STATE to accept, and Article 247(2) hands
  ;; the preservation obligation down. Same facet key, opposite direction.
  ;; Japan's answer must not be applied to an EU document in either
  ;; direction — not as a hold, and not as a pass.
  (let [v (verdict-for :payable/jurisdiction [:eu]
                       :payable/registration-number "DE811907980"
                       :payable/origin :electronic-transaction
                       :payable/preservation :paper)
        p (:preservation v)]
    (testing "not preserved — the actor did not check, and does not say it did"
      (is (not (true? (:taxlaw/preserved? p))))
      (is (= :none (:taxlaw/coverage p))))
    (testing "and it says WHY it did not check, naming the Article"
      (is (= :jurisdiction/electronic-transaction (:taxlaw/out-of-scope p)))
      (is (re-find #"247\(2\)" (:taxlaw/why p))))
    (testing "Japan's rule is not applied to an EU document"
      (is (not (contains? (rules v) :electronic-record-not-preserved))))
    (testing "and the convenience boolean is conservative"
      (is (false? (taxlaw/preserved?
                   [:eu] {:origin :electronic-transaction
                          :preservation :paper}))))))

(deftest the-same-document-in-japan-is-held
  ;; The contrast that makes the test above a finding rather than a shrug: the
  ;; identical origin/preservation pair IS a violation under 第七条.
  (let [v (verdict-for :payable/jurisdiction [:jp]
                       :payable/registration-number "T1234567890123"
                       :payable/origin :electronic-transaction
                       :payable/preservation :paper)]
    (is (:hard? v))
    (is (contains? (rules v) :electronic-record-not-preserved))
    (is (re-find #"第七条" (:taxlaw/provision (:preservation v))))))

;; ---------------------------------------------------------------------------
;; Retention — nil is two different answers
;; ---------------------------------------------------------------------------

(deftest retention-never-invents-a-number-the-instrument-does-not-state
  ;; `kotoba.taxlaw/retention-years` is nil for [:eu], for [:us] and for a
  ;; jurisdiction nobody read. A caller that saw only the nil would be free to
  ;; supply the seven years that appears in no instrument here.
  (doseq [j [[:eu] [:us] [:atlantis] nil]]
    (testing (pr-str j)
      (is (nil? (taxlaw/retention-years j)) "the precondition of this test")
      (let [r (juris/retention j)]
        (is (not (contains? r :retention/years))
            (str "answered a year count for " (pr-str j) ": " (pr-str r)))))))

(deftest retention-separates-not-read-from-set-somewhere-else
  (testing "the EU instrument was read and hands the period to the Member State"
    (let [r (juris/retention [:eu])]
      (is (= :deferred (:retention/coverage r)))
      (is (= :member-state (:retention/period-set-by r)))
      (is (re-find #"Article 247\(1\)" (:retention/provision r)))
      (is (re-find #"Each Member State shall determine the period"
                   (:retention/quote r)))))
  (testing "the US regulation states a CONDITION where a number would go"
    (let [r (juris/retention [:us])]
      (is (= :deferred (:retention/coverage r)))
      (is (= :materiality (:retention/period-set-by r)))
      (is (re-find #"1\.6001-1" (:retention/provision r)))
      (is (re-find #"may become material" (:retention/quote r)))))
  (testing "and a jurisdiction nobody read is a different value again"
    (let [r (juris/retention [:atlantis])]
      (is (= :none (:retention/coverage r)))
      (is (some? (:retention/why r)))))
  (testing "the three are not the same value"
    (is (= 2 (count (set (map #(:retention/coverage (juris/retention %))
                              [[:eu] [:us] [:atlantis]]))))
        "deferred, deferred, none")))

(deftest japan-states-a-number-and-says-what-it-depends-on
  ;; Not a bare 7. 法人税法施行規則 第五十九条 binds 青色申告法人, the period
  ;; is 10 years where a loss is carried forward, and the 起算日 is two months
  ;; after a fiscal-year end this actor does not hold.
  (let [r (juris/retention [:jp])]
    (is (= :stated (:retention/coverage r)))
    (is (= 7 (:retention/years r)))
    (is (= 10 (:retention/years-with-loss-carryforward r)))
    (is (= #{:blue-return-corporation :loss-carryforward :fiscal-year-end}
           (:retention/conditional-on r)))
    (is (re-find #"第五十九条第二項" (:retention/basis-date-provision r)))))

(deftest the-verdict-carries-retention-for-every-jurisdiction-it-sees
  (doseq [[j expected] [[[:jp] :stated] [[:eu] :deferred]
                        [[:us] :deferred] [[:atlantis] :none]]]
    (testing (pr-str j)
      (let [v (verdict-for :payable/jurisdiction j
                           :payable/registration-number "T1234567890123")]
        (is (= expected (:retention/coverage (:retention v))))))))

;; ---------------------------------------------------------------------------
;; The surface
;; ---------------------------------------------------------------------------

(def ^:private allowlist {"did:key:z6Mk1" "s-1"})

(defn- propose! [st body]
  (ep/propose-payment-core! st :ephemeral allowlist "did:key:z6Mk1" body))

(deftest the-endpoint-answers-202-for-an-eu-claim-and-shows-the-gap
  (let [st (store-with :payable/jurisdiction [:eu]
                       :payable/registration-number "XX1")
        r (propose! st (pr-str {:payable "pbl-x" :payment-id "pay-eu"
                                :from-account "FUND-EUR"
                                :payment-date "2026-01-20"}))]
    (is (= 202 (:status r)))
    (is (false? (get-in r [:body :scheduled])))
    (is (nil? (store/payment st "pay-eu")))
    (testing "and the body carries what was NOT checked, beside the tax map"
      (is (= #{:member-state-is-a-member :body-format :check-digit}
             (get-in r [:body :registration-gap :registration/not-checked])))
      (is (true? (get-in r [:body :tax :taxlaw/supported?]))
          "the two travel together on purpose: the second qualifies the first"))))

(deftest the-endpoint-answers-409-for-a-us-claim-with-the-reason
  (let [st (store-with :payable/jurisdiction [:us]
                       :payable/registration-number "12-3456789")
        r (propose! st (pr-str {:payable "pbl-x" :payment-id "pay-us"
                                :from-account "FUND-EUR"
                                :payment-date "2026-01-20"}))]
    (is (= 409 (:status r)))
    (is (true? (get-in r [:body :hard?])))
    (is (contains? (set (map :rule (get-in r [:body :violations])))
                   :credit-jurisdiction-out-of-scope))
    (is (re-find #"no federal VAT"
                 (get-in r [:body :tax :taxlaw/why])))))

(deftest the-endpoint-never-puts-a-year-count-on-a-deferred-retention
  (doseq [[j expected] [[[:eu] :deferred] [[:us] :deferred]]]
    (testing (pr-str j)
      (let [st (store-with :payable/jurisdiction j
                           :payable/claims-input-tax-credit? false)
            r (propose! st (pr-str {:payable "pbl-x" :payment-id "pay-r"
                                    :from-account "FUND-EUR"
                                    :payment-date "2026-01-20"}))
            ret (get-in r [:body :retention])]
        (is (= expected (:retention/coverage ret)))
        (is (not (contains? ret :retention/years)))))))

;; ---------------------------------------------------------------------------
;; Evidence floor
;; ---------------------------------------------------------------------------

(deftest this-file-actually-exercised-more-than-one-jurisdiction
  ;; A jurisdiction suite that only ever built [:jp] payables would pass every
  ;; assertion above by never reaching the code they are about.
  (let [seen (set (for [j [[:jp] [:eu] [:us] [:atlantis]]]
                    (:taxlaw/coverage
                     (:tax (verdict-for :payable/jurisdiction j
                                        :payable/registration-number "T1234567890123")))))]
    (is (= #{:checked :none} seen))
    (is (= 4 (count (filter some? [[:jp] [:eu] [:us] [:atlantis]]))))))

;; ---------------------------------------------------------------------------
;; Found by the mutation harness on 2026-08-18. `:gap-reported-on-a-refusal`
;; loosened `registration-gap`'s `(true? …)` guard to `(some? …)` and NO TEST
;; NOTICED — every assertion above is about an ACCEPTED claim, so nothing
;; described what the shortfall does beside a refusal.
;; ---------------------------------------------------------------------------

(deftest a-refused-claim-reports-no-shortfall-because-it-has-already-refused
  ;; "Here is what we did not check" printed next to a refusal reads as the
  ;; REASON for it — as though the claim would have passed had the check digit
  ;; been computed. What actually happened is that the prefix itself failed,
  ;; and there is nothing provisional about that. A shortfall qualifies a
  ;; `true`; it has no work to do beside a `false`.
  (doseq [n [nil "" "T1234567890123" "de811907980" "D1"]]
    (testing (pr-str n)
      (let [v (verdict-for :payable/jurisdiction [:eu]
                           :payable/registration-number n)]
        (is (= :checked (:taxlaw/coverage (:tax v))) "the precondition")
        (is (false? (:taxlaw/supported? (:tax v))) "the precondition")
        (is (nil? (:registration-gap v))
            (str "a refusal carried a shortfall: "
                 (pr-str (:registration-gap v)))))))
  (testing "and no escalation rides along on a HARD hold either — an approver
            must not be handed something to weigh on a verdict with no
            approval route"
    (let [v (verdict-for :payable/jurisdiction [:eu]
                         :payable/registration-number "D1")]
      (is (:hard? v))
      (is (not (contains? (escalation-rules v)
                          :registration-format-partially-checked))))))

(deftest a-jurisdiction-with-no-invoice-rule-reports-no-shortfall
  ;; The other half of the same guard: `:none` was never asked the question,
  ;; so it has nothing it declined to check.
  (doseq [j [[:us] [:atlantis]]]
    (testing (pr-str j)
      (let [v (verdict-for :payable/jurisdiction j
                           :payable/registration-number "DE811907980")]
        (is (= :none (:taxlaw/coverage (:tax v))))
        (is (nil? (:registration-gap v)))))))

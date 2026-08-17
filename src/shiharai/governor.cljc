(ns shiharai.governor
  "ShiharaiGovernor — the independent safety layer for the 支払 (shiharai)
  accounts-payable actor (itonami actor pattern, ADR-2607011000 / CLAUDE.md
  Actors section). Sibling of `cloud-itonami/tehai`'s governor, which gates
  the money coming IN; this one gates the money going OUT, which is the side
  where a mistake is unrecoverable.

  The verdict is assembled by `kotoba-lang/governor`'s `gov/verdict`, never
  by hand. The four shared provenance rules come from the same library. What
  is local to this actor is which domain facts are violations, and the answer
  to every question this governor could not settle is kept ON the verdict
  rather than dropped.

  ## The ceiling

  This governor can approve nothing that moves money, because nothing in this
  repository moves money. `:release-payment` marks a payment `:authorised`
  and always escalates to a human first. There is no bank client, no
  credential, no network call. See README.

  ## HARD invariants (`:hard?` true, ALWAYS `:hold`, never overridable)

   1. supplier provenance   — the requesting supplier must be registered.
   2. no-actuation          — `:effect` must be `:propose`.
   3. payable provenance    — a cited payable must be registered …
   4. payable ownership     — … and must belong to THIS supplier. Paying
                              one supplier's invoice out of another's
                              relationship is the AP analogue of billing the
                              wrong client.
   5. payment/payable agreement — the payment record must cite the same
                              payable the proposal does.
   6. unknown payable amount — a payable whose amount nobody recorded is not
                              worth 0 and not worth whatever the proposal
                              says. There is no approval that makes an
                              unknown figure known.
   7. invalid amount        — a payment must be a positive integer of minor
                              units.
   8. currency mismatch     — payment currency ≠ payable currency. This repo
                              holds no FX rates, so a cross-currency payment
                              cannot be compared with the balance it claims
                              to settle. Refuse; do not convert at a rate
                              nobody supplied.
   9. duplicate payment     — the payable is already fully covered by
                              committed payments. The classic AP failure, and
                              the reason `:scheduled` consumes the balance
                              rather than only `:authorised`.
  10. overpayment           — the payment exceeds what is still outstanding.
  11. payment id reuse      — `:schedule-payment` may not reuse an id that is
                              already committed (idempotency is not the
                              caller's private business when the record is an
                              instruction to pay).
  12. release of an unscheduled payment — `:release-payment` must cite a
                              payment that was scheduled and is still
                              `:scheduled`.
  13. release alters the scheduled payment — a release may not change the
                              amount, currency, payable or funding account a
                              human is about to be asked to approve.
  14. no destination account — the supplier has no account on file.
  15. invalid destination account — the account declares itself an IBAN and
                              fails ISO 13616 mod-97 (`kotoba.banking`).
  16. unknown funding account — `:payment/from-account` is not registered.
  17. funding account currency mismatch.
  18. unbalanced posting    — the double-entry posting this payment produces
                              does not balance. Built here from the store and
                              validated with `kotoba.banking/balanced?`.
  19. posting mismatch      — the advisor supplied a posting that differs
                              from the one redrafted from the store. tehai's
                              `:total-mismatch`, on the payment side.
  20. unchecked credit jurisdiction — the payable CLAIMS 仕入税額控除 in a
                              jurisdiction `kotoba.taxlaw` has never
                              catalogued. `:none` is not a pass.
  21. input-tax credit unsupported — it claims the credit and the qualified
                              invoice registration number is missing or
                              malformed.
  22. electronic record not preserved — 電子帳簿保存法 第七条, via
                              `kotoba.taxlaw/record-preservation`, and only
                              when that library answers `:checked`.
  23. statutory payment term exceeded — 取適法 第三条, and only in the
                              unambiguous direction (see `shiharai.law`).
  24. statutory term undeterminable — the payable asserts 第三条 applies and
                              does not carry the dates needed to check it. A
                              rule that applies and could not be evaluated is
                              not a rule that passed.

  ## ESCALATION (`:escalate?` true, human sign-off)

   E1. `:op :release-payment` — real fund movement. ALWAYS.
   E2. unverified destination — an account under a scheme this repo has no
       validator for (全銀, sort code, …). Neither a pass nor a hold: a
       person looks at it.
   E3. statutory term at the 60-day boundary — exactly 60 days, which is
       inside the limit on one reading of 「起算」 and outside it on the
       other. `shiharai.law` refuses to pick; the governor asks a human.
   E4. payment scheduled after the payable's own due date. A business fact,
       NOT a statutory one — no article in this repo says it is unlawful, so
       it does not hold.
   E5. confidence below `confidence-floor`.

  ## What is reported but not enforced

  `:extra` carries `:tax`, `:preservation`, `:destination`, `:payment-terms`,
  `:outstanding` and `:escalations`. Where this governor deliberately did not
  hold, the verdict still SAYS what was not checked — a console that shows a
  green verdict for a payable whose origin was never declared is showing a
  question, not an answer."
  (:require [governor.core :as gov]
            [kotoba.banking :as bank]
            [kotoba.taxlaw :as taxlaw]
            [shiharai.law :as law]
            [shiharai.store :as store]))

(def confidence-floor
  "0.6 — what 365 of the 376 governors `kotoba-lang/governor` surveyed use.
  Not lowered for this actor: the outgoing-payment side is not the place to
  become more tolerant of a model that is unsure."
  0.6)

(def default-ap-account
  "The 買掛金 control account a payment debits when the payable does not name
  one. A constant, not an inference — a posting that guessed which liability
  it was clearing would balance and still be wrong."
  "AP")

(def escalating-ops #{:release-payment})

;; ---------------------------------------------------------------------------
;; The posting, redrafted from the store
;; ---------------------------------------------------------------------------

(defn redraft-posting
  "The double-entry posting this payment would produce, built from the store
  rather than from the proposal: debit the payable's AP control account,
  credit the funding account. Returns nil when the payment is not
  well-enough-formed to post at all (those cases are their own violations).

  `kotoba.banking/posting` sets `:ledger/balanced?` and, when it does not
  balance, `:ledger/unbalanced` — this actor consumes that rather than
  summing debits and credits itself."
  [store proposal]
  (let [{:keys [payment]} proposal
        {:payment/keys [id payable amount-minor currency from-account]} payment
        pay (when payable (store/payable store payable))]
    (when (and id (int? amount-minor) (pos? amount-minor) (string? currency) from-account)
      (bank/posting
       (str "post-" id)
       [(bank/entry (or (:payable/ap-account pay) default-ap-account)
                    :debit amount-minor currency :ref payable)
        (bank/entry from-account :credit amount-minor currency :ref id)]
       :memo (str "支払 " id " → payable " payable)))))

;; ---------------------------------------------------------------------------
;; The three-valued answers, computed once and reported whether or not they
;; produced a violation.
;; ---------------------------------------------------------------------------

(defn assessments
  "Everything this governor asked that has more than two answers.

  Returned as data and merged onto the verdict, so the same map both drives
  the rules below and is what a console reads. Computing it twice — once to
  decide and once to display — is how the two drift apart."
  [request proposal store]
  (let [supplier-id (:supplier-id request)
        sup (store/supplier store supplier-id)
        pay (some->> (:payable proposal) (store/payable store))
        pmt (:payment proposal)
        claims? (true? (:payable/claims-input-tax-credit? pay))
        juris (:payable/jurisdiction pay)]
    {:supplier sup
     :payable pay
     :destination (store/destination-check
                   (some->> (:supplier/account sup) (store/account-of store)))
     :funding-account (some->> (:payment/from-account pmt) (store/account-of store))
     :outstanding (when pay
                    (store/outstanding store (:payable/id pay)
                                       (when (= :release-payment (:op proposal))
                                         (:payment/id pmt))))
     ;; taxlaw answers in three values and all three are kept. `:not-claimed`
     ;; is this actor's own fourth: nothing was asserted, so nothing was
     ;; checked, and that is different from a jurisdiction nobody catalogued.
     :tax (if-not claims?
            {:taxlaw/coverage :not-claimed
             :taxlaw/why "この payable は仕入税額控除を主張していない"}
            (taxlaw/credit-support
             juris {:registration-number (:payable/registration-number pay)}))
     :preservation (when pay
                     (taxlaw/record-preservation
                      juris {:origin (:payable/origin pay)
                             :preservation (:payable/preservation pay)}))
     :payment-terms (when pay (law/payment-term pay sup))
     :posting (redraft-posting store proposal)}))

;; ---------------------------------------------------------------------------
;; HARD violations
;; ---------------------------------------------------------------------------

(defn- hard-violations
  [request proposal store a]
  (let [{:keys [op effect payable payment posting]} proposal
        {:payment/keys [id amount-minor currency from-account]} payment
        supplier-id (:supplier-id request)
        {sup :supplier pay :payable dest :destination fund :funding-account
         out :outstanding tax :tax pres :preservation terms :payment-terms
         redrafted :posting} a
        existing (when id (store/payment store id))
        releasing? (= :release-payment op)]
    (gov/violations
     ;; --- the fleet's four, from kotoba-lang/governor --------------------
     (gov/missing-subject sup {:rule :no-supplier :detail "未登録の supplier への支払は不可"})
     (gov/no-actuation {:effect effect}
                       {:detail (str "effect は :propose のみ許可"
                                     "（governor を経ない送金・振込指図は行わない）")})
     (gov/unknown-scope pay {:applies? (boolean payable)
                             :rule :unknown-payable
                             :detail (str "未登録の payable: " (pr-str payable))})
     (gov/scope-owner-mismatch pay {:supplier-id supplier-id}
                               {:owner-key :supplier-id
                                :scope-key :payable/supplier
                                :rule :payable-wrong-supplier
                                :detail (str "payable " (pr-str payable) " は supplier "
                                             (pr-str (:payable/supplier pay))
                                             " のもの（" (pr-str supplier-id) " ではない）")})

     ;; --- shiharai's own ---------------------------------------------------
     (cond-> []
       (nil? payable)
       (conj {:rule :no-payable-cited
              :detail "支払は必ず登録済みの payable を指す（宛先の無い支払は作らない）"})

       (and payable payment (not= payable (:payment/payable payment)))
       (conj {:rule :payment-cites-other-payable
              :detail (str "proposal の payable " (pr-str payable)
                           " と payment の :payment/payable "
                           (pr-str (:payment/payable payment)) " が一致しない")})

       (nil? payment)
       (conj {:rule :no-payment
              :detail "payment レコードの無い支払提案は評価できない"})

       ;; 6. unknown is not zero and not unlimited
       (and pay (not (int? (:payable/amount-minor pay))))
       (conj {:rule :unknown-payable-amount
              :detail (str "payable " (pr-str (:payable/id pay))
                           " の金額 " (pr-str (:payable/amount-minor pay))
                           " は未知。未知は 0 でも無制限でもなく、"
                           "承認で既知にはならない")})

       (and payment (not (and (int? amount-minor) (pos? amount-minor))))
       (conj {:rule :invalid-amount
              :detail (str "支払額 " (pr-str amount-minor)
                           " は正の整数（最小通貨単位）でなければならない")})

       (and pay payment (some? currency) (not= currency (:payable/currency pay)))
       (conj {:rule :currency-mismatch
              :detail (str "支払通貨 " (pr-str currency) " と payable の通貨 "
                           (pr-str (:payable/currency pay))
                           " が異なる。この repo は FX rate を持たないので、"
                           "誰も出していないレートで換算せず拒否する")})

       (and payment (nil? currency))
       (conj {:rule :currency-mismatch
              :detail "支払通貨が宣言されていない（未宣言は payable の通貨と一致しない）"})

       ;; 9 / 10. duplicate before overpayment: when the payable is already
       ;; settled, "you are over by the full amount" is a true sentence that
       ;; names the wrong problem.
       (and (int? out) (<= out 0))
       (conj {:rule :duplicate-payment
              :detail (str "payable " (pr-str (:payable/id pay))
                           " は既に committed payment で全額 covered（残 " out
                           "）。二重支払は AP の典型的な事故で、承認経路は無い")})

       (and (int? out) (pos? out) (int? amount-minor) (> amount-minor out))
       (conj {:rule :overpayment
              :detail (str "支払額 " amount-minor " が残額 " out " を超える")})

       (and (= :schedule-payment op) existing)
       (conj {:rule :payment-id-reused
              :detail (str "payment id " (pr-str id) " は既に commit 済み（status "
                           (pr-str (:payment/status existing)) "）")})

       (and releasing? (not= :scheduled (:payment/status existing)))
       (conj {:rule :release-of-unscheduled-payment
              :detail (str "release は :scheduled な payment を指さなければならない。"
                           (pr-str id) " の status は "
                           (pr-str (:payment/status existing)))})

       (and releasing? (= :scheduled (:payment/status existing))
            (not= (select-keys existing [:payment/payable :payment/amount-minor
                                         :payment/currency :payment/from-account])
                  (select-keys payment [:payment/payable :payment/amount-minor
                                        :payment/currency :payment/from-account])))
       (conj {:rule :release-alters-scheduled-payment
              :detail (str "release が scheduled payment の内容を変更している。"
                           "人が承認するのは scheduled な内容であって、"
                           "解放時に差し替えられた内容ではない: "
                           (pr-str (select-keys existing
                                                [:payment/payable :payment/amount-minor
                                                 :payment/currency :payment/from-account])))})

       ;; 14 / 15. destination
       (= :none (:destination/coverage dest))
       (conj {:rule :no-destination-account
              :detail "supplier に送金先口座が登録されていない"})

       (and (= :checked (:destination/coverage dest))
            (false? (:destination/valid? dest)))
       (conj {:rule :invalid-destination-account
              :detail (str "IBAN " (pr-str (:destination/account dest))
                           " が ISO 13616 mod-97 検査に通らない"
                           "（kotoba.banking/iban-valid?）")})

       ;; 16 / 17. funding account
       (and payment from-account (nil? fund))
       (conj {:rule :unknown-funding-account
              :detail (str "出金元口座 " (pr-str from-account) " が未登録")})

       (and payment (nil? from-account))
       (conj {:rule :unknown-funding-account
              :detail "出金元口座が指定されていない"})

       (and fund (some? currency) (not= currency (:account/currency fund)))
       (conj {:rule :funding-account-currency-mismatch
              :detail (str "出金元口座 " (pr-str from-account) " の通貨 "
                           (pr-str (:account/currency fund)) " と支払通貨 "
                           (pr-str currency) " が異なる")})

       ;; 18 / 19. the posting.
       ;;
       ;; Both rules are about the posting the ADVISOR supplied. The
       ;; governor's own redraft is balanced by construction — a debit and a
       ;; credit of the same amount — so a rule guarding its balance could
       ;; never fire, and a check that cannot fail is theatre (CLAUDE.md:
       ;; 「落ちない gate は劇場」). The mutation harness found exactly that
       ;; and this is the repair. What the redraft's balance IS good for is
       ;; an assertion in the suite, where it belongs.
       (and posting (not (bank/balanced? (:ledger/entries posting))))
       (conj {:rule :unbalanced-posting
              :detail (str "提案された複式仕訳が均衡しない: "
                           (pr-str (:ledger/entries posting)))})

       (and posting redrafted
            (bank/balanced? (:ledger/entries posting))
            (not= (:ledger/entries posting) (:ledger/entries redrafted)))
       (conj {:rule :posting-mismatch
              :detail (str "提案された仕訳が台帳から再作成した仕訳と一致しない。"
                           "再作成: " (pr-str (:ledger/entries redrafted)))})

       ;; 20 / 21. 仕入税額控除
       (= :none (:taxlaw/coverage tax))
       (conj {:rule :unchecked-credit-jurisdiction
              :detail (str "仕入税額控除を主張しているが、法域 "
                           (pr-str (:payable/jurisdiction pay))
                           " は kotoba.taxlaw に無く判定できない（未検査は合格ではない）")})

       (and (= :checked (:taxlaw/coverage tax)) (false? (:taxlaw/supported? tax)))
       (conj {:rule :input-tax-credit-unsupported
              :detail (str "適格請求書発行事業者の登録番号が無いか不正（"
                           (name (or (:taxlaw/reason tax) :unknown))
                           "）。この payable では仕入税額控除を主張できない: "
                           (pr-str (:payable/registration-number pay)))})

       ;; 22. 電子帳簿保存法 第七条 — only when taxlaw actually answered
       (and (= :checked (:taxlaw/coverage pres)) (false? (:taxlaw/preserved? pres)))
       (conj {:rule :electronic-record-not-preserved
              :detail (str (:taxlaw/provision pres)
                           ": 電子取引の取引情報は電磁的記録のまま保存する必要がある。"
                           "この payable の preservation は "
                           (pr-str (:taxlaw/preservation pres)))})

       ;; 23 / 24. 取適法 第三条
       (law/exceeded? terms)
       (conj {:rule :statutory-payment-term-exceeded
              :detail (str (:law/provision terms) ": 受領日 "
                           (:law/received-date terms) " から支払期日 "
                           (:law/due-date terms) " まで " (:law/days terms)
                           " 日で、60 日をどの読み方でも超える。"
                           "第一項が許しうる最も遅い日は "
                           (:law/latest-permissible-due-date terms))})

       (= :undeterminable (:law/coverage terms))
       (conj {:rule :statutory-term-undeterminable
              :detail (str (:law/provision terms) " の適用を主張しているが、"
                           (:law/why terms))})))))

;; ---------------------------------------------------------------------------
;; Escalations
;; ---------------------------------------------------------------------------

(defn- escalations
  "Why a human is being asked, as data. `gov/verdict` carries one
  `:escalation-reason` keyword (`:counsel-decision`), which is the right
  shape for routing and the wrong shape for telling the approver what to look
  at — so the reasons travel beside it rather than replacing it."
  [proposal a]
  (let [{:keys [op payment]} proposal
        {dest :destination terms :payment-terms pay :payable} a]
    (cond-> []
      (contains? escalating-ops op)
      (conj {:rule :release-payment
             :detail (str "実際の資金移動にあたる操作。この actor は資金を動かす能力を"
                          "持たないが、:authorised の記録は人が付ける")})

      (= :unverified (:destination/coverage dest))
      (conj {:rule :unverified-destination
             :detail (:destination/why dest)})

      (law/boundary? terms)
      (conj {:rule :statutory-term-boundary
             :detail (str (:law/provision terms) ": 受領日から支払期日まで"
                          "ちょうど 60 日。「起算」の読み方によって適法にも違法にも"
                          "なる境界で、この repo はその解釈を決めない")})

      (let [d (:payable/due-date pay)
            pd (:payment/date payment)
            n (when (and d pd) (law/days-between d pd))]
        (and (int? n) (pos? n)))
      (conj {:rule :payment-after-due-date
             :detail (str "支払予定日 " (:payment/date payment)
                          " が payable の支払期日 " (:payable/due-date pay)
                          " より後。これは商慣行上の判断であって、"
                          "この repo が読んだ条文が違法と述べているものではない")}))))

;; ---------------------------------------------------------------------------
;; The verdict
;; ---------------------------------------------------------------------------

(defn check
  "Assess a proposal against `request` / `context` / `proposal` and a `store`
  implementing `shiharai.store/Store`. Pure — never mutates the store.

  Returns `gov/verdict`'s map plus `:tax`, `:preservation`, `:destination`,
  `:payment-terms`, `:outstanding` and `:escalations`."
  [request _context proposal store]
  (let [a (assessments request proposal store)
        esc (escalations proposal a)]
    (gov/verdict
     {:violations (hard-violations request proposal store a)
      :confidence (:confidence proposal)
      :escalating-op? (boolean (seq esc))
      :confidence-floor confidence-floor
      :extra {:tax (:tax a)
              :preservation (:preservation a)
              :destination (:destination a)
              :payment-terms (:payment-terms a)
              :outstanding (:outstanding a)
              :posting (:posting a)
              :escalations esc}})))

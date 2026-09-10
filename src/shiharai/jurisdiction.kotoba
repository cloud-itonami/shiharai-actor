(ns shiharai.jurisdiction
  "What this actor can say about a supplier invoice OUTSIDE Japan — and, at
  greater length, what it cannot.

  `kotoba.taxlaw` catalogues coverage **per facet**, not per jurisdiction. A
  jurisdiction is a bag of facets that were each read separately, so being in
  the catalog says something was read about somewhere and says nothing about
  the facet being asked after. This namespace is where that distinction is
  turned into sentences an accounts-payable operator can act on.

  Everything here is a REPORT. Nothing in this file holds a payment;
  `shiharai.governor` decides that, and it decides it from the same values
  computed once here.

  ## The question this actor asks is not the one `tehai` asks

  `cloud-itonami/tehai` issues invoices, so its question is 「この請求書を出して
  よいか」 — does the document we are about to emit carry what the law requires.
  This actor RECEIVES one and pays it, so its question is
  「この請求書で控除を主張してよいか、そしてこれをいつまで保管するのか」. The
  same statute reads differently from the two ends: an issuer that omits a
  registration number has issued a defective invoice, while a payer holding one
  has an input-tax claim with nothing under it. The second is this actor's
  problem and the first is not.

  ## `[:jp]` — the actor can answer, and does

  適格請求書 registration number checked against `T` + 13 digits; 電子帳簿保存法
  第七条 checked, because Japan is the jurisdiction whose catalog entry answers
  *must the HOLDER preserve the electromagnetic record as such* with `true`;
  取適法 第三条 checked by `shiharai.law`. Retention states a number.

  ## `[:eu]` — the actor can check ONE thing, and must not sound like it
  ##        checked more

  Directive 2006/112/EC Article 226 is a closed list and item (3) is the
  supplier's VAT identification number, so the EU genuinely has an analogue of
  the 適格請求書 registration number and `requires-qualified-invoice?` is true.
  But Article 215 gives the FORMAT as an ISO 3166 alpha-2 prefix and nothing
  else — Greece may use `EL` — so the only thing checkable here is the shape of
  the first two characters. **`XX1` satisfies it.**

  So `:taxlaw/supported? true` in the EU means *nothing this catalog can check
  is wrong with this string*. It does not mean the prefix names a Member State,
  that the body has the right shape, or that any check digit was computed —
  the catalog says so itself, in `:not-checked`. Reporting that as \"the
  supplier's VAT number is valid\" would be this actor's worst available
  sentence: it is the one an approver would believe.

  `registration-gap` names the shortfall and `shiharai.governor` escalates on
  it. The result is that an EU input-tax claim reaches a human rather than a
  schedule — the same answer, for the same reason, that a 全銀 destination
  account already gets from `shiharai.store/destination-check`. There is no
  validator, so there is a person.

  **What the EU catalog does NOT answer for a payer:** whether the holder must
  keep an electromagnetic record as such. Articles 218 and 246 oblige the
  MEMBER STATE to accept electronic form and require authenticity, integrity
  and legibility through the storage period; Article 247(2) hands the
  preservation obligation to the Member State. Same facet key as Japan's,
  opposite direction. `record-preservation` therefore answers `:none` for
  `[:eu]`, and **an EU electronic invoice kept on paper must never come back
  from this actor as preserved** — it comes back as not checked, with that
  reason attached.

  ## `[:us]` — there is no federal analogue, and that is the finding

  There is no federal VAT or GST, so no federal analogue of a qualified invoice
  exists; the consumption taxes that do exist are State sales and use taxes,
  fifty-odd bodies of law the catalog has not read. A US payable claiming an
  input-tax credit is therefore HELD, exactly as hard as a payable in a
  jurisdiction nobody has ever catalogued — **adding the United States to the
  catalog did not make a single US payment payable.**

  What changed is only the sentence attached to the refusal. `:out-of-scope`
  says the facet was considered and deliberately left out, and why. That
  distinction is the one this repository keeps making about misattributed
  blame: `:unchecked-credit-jurisdiction` sends an operator to go and read a
  law, and there is no law at the federal level to go and read.

  ## Retention — nil is TWO different answers and this actor separates them

  `kotoba.taxlaw/retention-years` returns nil for `[:eu]`, for `[:us]` and for
  `[:atlantis]`, and those are not the same nil:

  - `[:atlantis]` — nobody read a retention rule. Missing data.
  - `[:eu]` — Article 247(1) says *each Member State shall determine the
    period*. The instrument was read; its content is that the number lives one
    level down.
  - `[:us]` — 26 CFR § 1.6001-1(e) states a CONDITION, not a number: records
    are kept *so long as the contents thereof may become material in the
    administration of any internal revenue law*. The widely repeated \"seven
    years\" appears nowhere in the regulation.

  `retention` returns `:none` / `:deferred` / `:stated` so a caller can tell
  them apart. **This actor never answers 7 or 10 years for a jurisdiction whose
  instrument states no number.** Inventing one would be the same failure as
  defaulting an unknown payable amount to zero, one axis over.

  And it does not answer a bare 7 for Japan either. 法人税法施行規則 第五十九条
  binds 青色申告法人, the period is 10 years where a loss is carried forward
  (第二十六条の三第一項), and the 起算日 is two months after the fiscal year
  end — none of which is a fact this repository holds. So `:stated` carries the
  number together with what it is conditional on, and says the 起算日 was not
  computed here."
  (:require [kotoba.taxlaw :as taxlaw]))

;; ---------------------------------------------------------------------------
;; What a registration-number check did NOT look at
;; ---------------------------------------------------------------------------

(defn registration-gap
  "The things the catalog says it did **not** check about a registration
  number it nonetheless accepted, or **nil** when there is no shortfall.

  Read out of `:taxlaw/registration-format`, which `credit-support` carries
  precisely so that a `true` can be interrogated. Only meaningful on an
  accepted claim: a refusal has already refused, and a jurisdiction with no
  invoice rule was never asked.

  nil for `[:jp]`, because the Japanese entry declares no format metadata.
  That is what the catalog says and it is NOT evidence that the Japanese check
  is exhaustive — a `T`+13-digit string is well formed, and whether 国税庁 has
  that number on its 公表サイト is a question this repository does not ask of
  any jurisdiction. If the catalog ever declares that gap, this function will
  report it without being changed, which is the reason it reads the catalog
  rather than a list of jurisdictions kept here."
  [tax]
  (when (and (= :checked (:taxlaw/coverage tax))
             (true? (:taxlaw/supported? tax))
             (true? (:taxlaw/requires-qualified-invoice? tax)))
    (let [fmt (:taxlaw/registration-format tax)
          missing (:not-checked fmt)]
      (when (seq missing)
        {:registration/kind (:kind fmt)
         :registration/checked (:checked fmt)
         :registration/not-checked missing
         :registration/body-authority (:body-authority fmt)
         :registration/why (:why fmt)}))))

;; ---------------------------------------------------------------------------
;; Retention
;; ---------------------------------------------------------------------------

(defn retention
  "How long a supplier invoice in `j` must be kept, in three values.

    {:retention/coverage :none}      nobody catalogued a retention rule here.
                                     Missing data. Carries `:retention/why`
                                     when the catalog states one.
    {:retention/coverage :deferred}  the instrument WAS read and states no
                                     number — `:retention/period-set-by` names
                                     who does set it. This is an answer, not
                                     an absence.
    {:retention/coverage :stated}    the instrument states a number, in
                                     `:retention/years`, together with
                                     `:retention/conditional-on` — every
                                     retention period in this catalog binds a
                                     class of taxpayer rather than everybody.

  Never a bare integer, and never an integer at all where the instrument gives
  none. A caller that wants one may read `:retention/years`, which is absent
  on the two values where inventing it would be the failure."
  [j]
  (let [facet (taxlaw/facet-of j :jurisdiction/retention)
        years (taxlaw/retention-years j)
        why (taxlaw/out-of-scope j :jurisdiction/retention)]
    (cond
      (nil? facet)
      (cond-> {:retention/coverage :none
               :retention/jurisdiction j}
        why (assoc :retention/why why)
        (nil? why) (assoc :retention/why
                          (str "法域 " (pr-str j)
                               " の保存期間は kotoba.taxlaw に無い。"
                               "未読は「保存不要」ではない")))

      (nil? years)
      {:retention/coverage :deferred
       :retention/jurisdiction j
       :retention/period-set-by (:rule/period-set-by facet)
       :retention/provision (:rule/provision facet)
       :retention/quote (:rule/quote facet)
       :retention/why (str "この法令は年数を定めていない。定めるのは "
                           (pr-str (:rule/period-set-by facet))
                           "。ここで数字を出せば、どの条文も支持しない数字になる")}

      :else
      {:retention/coverage :stated
       :retention/jurisdiction j
       :retention/years years
       :retention/years-with-loss-carryforward
       (:rule/years-with-loss-carryforward facet)
       :retention/provision (:rule/provision facet)
       :retention/conditional-on
       (cond-> #{}
         (:rule/binds facet) (conj (:rule/binds facet))
         (:rule/years-with-loss-carryforward facet) (conj :loss-carryforward)
         (:rule/basis-date-provision facet) (conj :fiscal-year-end))
       :retention/basis-date-provision (:rule/basis-date-provision facet)
       :retention/why (str "年数は " (pr-str years) " 年だが、"
                           (pr-str (:rule/binds facet))
                           " に限られ、欠損金の繰越がある場合は "
                           (pr-str (:rule/years-with-loss-carryforward facet))
                           " 年になる。起算日は事業年度終了日に依存し、"
                           "この actor はそれを保持していないので計算しない")})))

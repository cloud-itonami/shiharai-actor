(ns shiharai.shiwake
  "仕訳 — an authorised payment as a journal entry request.

  This actor decides whether a supplier invoice may be paid. Deciding is not
  bookkeeping: **a payment that was authorised and never became a journal
  entry is money nobody's books show.** `cloud-itonami-isco-4311` owns the
  ledger; this namespace produces the value that actor accepts.

  ## It produces a value, it does not make a call

  There is no client here, no transport, and no reference to the ledger
  actor at all. `entry-request` returns the map that actor's entry endpoint
  takes, and something outside both actors carries it. Two reasons, and the
  second is the load-bearing one:

  1. This actor's ceiling is that it proposes. The strongest thing it writes
     is `:payment/status :authorised`, and reaching across to write into
     another actor's ledger would be the actuation this repository has spent
     its whole design refusing. `test/shiharai/shiwake_test.clj` asserts
     that by reading this file, so it is a check and not a sentence.
  2. **A call would make the accounts this actor's business.** They are not.
     Which 買掛金 account a supplier's invoices sit in and which 現金預金
     account the money leaves are the client's chart, and
     `kotoba-lang/shohyo` refuses to guess what an account is precisely
     because a statement that guessed still balances. So the mapping is an
     argument here too.

  ## The direction: a payment discharges a liability, it does not recognise a cost

      Dr 買掛金 / 未払金   (the liability this payable is, goes down)
      Cr 現金預金          (the funding account the money leaves, goes down)

  This is the opposite end of `cloud-itonami/keihi`'s entry, and the
  difference is not stylistic. An expense claim RECOGNISES a cost that was
  not on the books before. A payable was already recognised when the invoice
  was booked — this actor's own sibling `cloud-itonami/tehai` drafts them —
  so a payment that debited an expense would book the same cost twice: once
  when the invoice arrived and once when it was paid. What a payment changes
  is which side of the balance sheet the money sits on, not how much of it
  there is.

  It is also the direction `shiharai.governor/redraft-posting` already
  takes, and deliberately the same one. What this namespace does NOT reuse
  from that redraft is its account names: `\"AP\"` is
  `governor/default-ap-account`, a constant this repository chose, and
  `\"FUND-EUR\"` is a row in this actor's own account registry. Neither is
  an account in anybody's chart. The redraft proves the payment balances;
  the mapping says what it balances between.

  ## A scheduled payment is not a payment

  `:payment/status :scheduled` means a payment was found admissible and is
  waiting for a human to release it. Posting it would credit cash that has
  not left the account. So a scheduled payment yields `:not-released` and no
  entry — a named status, because the thing somebody has to do about it
  (approve it, or decide not to) is not the thing they have to do about a
  payment that was held.

  Note that `:scheduled` DOES consume the payable's balance in
  `shiharai.store` — that is this actor's duplicate-payment guard, which is
  a different question from what the client's books say. The two are not in
  conflict: one prevents paying an invoice twice, the other reports money
  that actually moved.

  ## Partial payments post at the payment's amount

  A payment for less than the payable's balance is an ordinary entry for
  what was paid; the residue stays an open 買掛金 balance and needs no entry
  of its own, because nothing about it changed. The amount therefore comes
  from `:payment/amount-minor`, and this namespace never sees the payable's
  face value — which is the point. There is no figure here to reach for by
  mistake, so a partial payment cannot post as a full settlement.

  ## What crosses, and what does not

  The request carries exactly what the ledger's entry endpoint reads out of
  a body: `:source-doc` and `:lines`. The payment id is not among them —
  that endpoint drops everything else — so it is reported on the wrapper
  this namespace returns instead of being smuggled into a body that would
  silently discard it. The consequence is worth naming rather than hiding:
  **an entry cannot be traced back to the payment that produced it except
  through the payable it cites.**"
  (:require [clojure.string :as str]))

(defn- minor-unit-amount?
  "A positive integer of the currency's minor unit — the same shape
  `shiharai.governor`'s `:invalid-amount` enforces, and NOT the looser
  `number?` the ledger's own body parser accepts. A fractional minor unit
  is not an amount anybody can pay, and it would cross that parser."
  [x]
  (and (int? x) (pos? x)))

(defn- absent? [x] (str/blank? (str x)))

(def ^:private postable-statuses
  "The payment statuses that have moved money and therefore belong in the
  books. Exactly one.

  Deliberately narrower than `shiharai.store/settled-statuses`, which
  includes `:scheduled` — and the difference between the two sets is the
  distinction this namespace exists to hold. `:scheduled` consumes a
  payable's balance because a second payment against an invoice already
  promised would be a duplicate; it does not enter the books, because
  nothing has left the account yet. One set answers 「もう払う約束をしたか」,
  the other 「もう出て行ったか」."
  #{:authorised})

(defn- postable-payment
  "The `:payment` of a ledger fact that committed and whose status has
  moved money — otherwise a named refusal.

  Tested in this order so `postable-statuses` is the ONLY thing deciding
  what posts. With the refusals first, widening that set would change
  nothing observable, and a set that cannot be widened wrongly is a set
  nobody has measured.

  The three refusals stay three answers. Collapsing them would say
  `:not-released`, which means \"a human still has to approve this\", about
  a record nobody understood."
  [{:keys [disposition record]}]
  (let [pmt (:payment record)
        status (:payment/status pmt)]
    (cond
      (not= :commit disposition)
      {:shiwake/status :not-authorised :shiwake/disposition disposition}

      (contains? postable-statuses status)
      pmt

      (= :scheduled status)
      {:shiwake/status :not-released
       :shiwake/payment-status status
       :shiwake/why (str "scheduled は支払の約束であって決済ではない。"
                         "これを計上すると、まだ口座を出ていない現金を貸方に立てる")}

      :else
      {:shiwake/status :unknown-payment-status
       :shiwake/payment-status status
       :shiwake/why (str "この repo が知らない payment status: " (pr-str status)
                         "。未知を :not-released と呼べば「承認待ち」という"
                         "誤った指示になる")})))

(defn entry-request
  "An authorised payment as the `:draft-entry` request `isco-4311` accepts,
  or the reason there is none.

      {:shiwake/status :not-authorised}         held, or nobody approved it
      {:shiwake/status :not-released}           scheduled; the cash has not moved
      {:shiwake/status :unknown-payment-status} a status this repo cannot read
      {:shiwake/status :no-mapping}             supplier or funding unmapped
      {:shiwake/status :unusable-payment}       amount, currency or invoice missing
      {:shiwake/status :ok
       :shiwake/request {:op :draft-entry :source-doc … :lines [...]}}

  `committed` is a **`shiharai.store/ledger` fact** — `{:disposition d
  :record {…}}`, appended by the graph's `:commit` and `:hold` nodes.

  Not the graph's final state, which has the same two keys and does not mean
  the same thing by them. A released payment routes through
  `:request-approval`, so `run-request!` returns `:disposition
  :request-approval` for a run that committed; converting from there would
  call an authorised payment `:not-authorised`. The `:commit` node writes
  `:disposition :commit` onto the fact it appends, and that is the value
  that records an outcome rather than a route. The ledger is also what
  survives the process, which is the other reason to fold it.

  `mapping` is `{:suppliers {supplier-id account} :accounts {funding-id
  account}}`. Both sides are looked up, and neither is inferred: the debit
  depends on which liability is being discharged and the credit on which
  bank account the money left, so one joint key would have to enumerate a
  cross product nobody maintains.

  Every refusal is its OWN value rather than nil. A caller that treats
  \"no entry\" as \"nothing to do\" would silently skip a payment that was
  actually refused, and refused payments are the ones somebody has to look
  at.

  One thing this function cannot lose, and says so instead of implying
  otherwise: a proposal that escalated and was never resumed appends no
  ledger fact at all (`shiharai.actor`'s `:request-approval` node writes
  nothing, and `:commit` never runs), so it does not arrive here to be
  refused. Folding the ledger reports on what the ledger holds."
  [{:keys [record] :as committed} mapping]
  (let [pmt (postable-payment committed)]
    (if (:shiwake/status pmt)
      pmt
      (let [{:payment/keys [payable amount-minor currency from-account]} pmt
            supplier-id (:supplier-id record)
            liability (get-in mapping [:suppliers supplier-id])
            cash (get-in mapping [:accounts from-account])]
        (cond
          (or (not (minor-unit-amount? amount-minor))
              (absent? currency)
              (absent? payable))
          {:shiwake/status :unusable-payment
           :shiwake/why (str "支払額は最小通貨単位の正の整数、通貨と原始証憑"
                             "（payable）は必須: "
                             (pr-str {:amount-minor amount-minor
                                      :currency currency
                                      :payable payable}))}

          ;; A half-filled mapping is NO mapping. An entry missing one line
          ;; balances by having lost it, and an unmapped side does not fall
          ;; back to a suspense account: that posts the entry and makes the
          ;; missing decision invisible, which is worse than not posting at
          ;; all, because a hole in the books can be found and a plausible
          ;; wrong account cannot.
          (or (absent? liability) (absent? cash))
          {:shiwake/status :no-mapping
           :shiwake/missing (cond-> #{}
                              (absent? liability) (conj :supplier)
                              (absent? cash) (conj :funding-account))
           :shiwake/supplier supplier-id
           :shiwake/funding-account from-account
           :shiwake/why (str "supplier " (pr-str supplier-id) " の負債勘定と"
                             " funding account " (pr-str from-account)
                             " の現預金勘定は、両方とも与えられなければならない"
                             "（この actor は勘定科目を選ばない）")}

          :else
          {:shiwake/status :ok
           :shiwake/payment-id (:payment/id pmt)
           :shiwake/request
           {:op :draft-entry
            ;; The payable IS the source document: the supplier invoice this
            ;; payment settles. It is cited on both sides of the hand-off,
            ;; and the registry that decides whether the citation is good is
            ;; 4311's, not this one's — that actor holds `:unknown-source-doc`
            ;; for a document its own store never registered. A payable this
            ;; actor knows about is not thereby a 原始証憑 the ledger knows
            ;; about, and that is the right way round.
            :source-doc payable
            ;; Dr the liability, Cr the cash. `:currency` rides on every
            ;; line because the ledger's balance check groups by it before
            ;; comparing — a line without one is a line whose unit that
            ;; arithmetic has to guess.
            :lines [{:side :dr :account liability :amount amount-minor :currency currency}
                    {:side :cr :account cash      :amount amount-minor :currency currency}]}})))))

(defn entry-requests
  "`entry-request` over many committed records, keeping the refusals.

  Returns `{:ok [...] :skipped [...]}` rather than filtering. A batch that
  quietly dropped what it could not convert would report a clean run and
  leave the unconvertible payments invisible — and on this side of the
  books, invisible means a disbursement with no entry against it. Each
  refusal carries `:shiwake/record`, the item it refused, because a refusal
  nobody can attribute is a refusal nobody can act on."
  [committed mapping]
  (let [rs (map #(assoc (entry-request % mapping) :shiwake/record %) committed)]
    {:ok (vec (filter #(= :ok (:shiwake/status %)) rs))
     :skipped (vec (remove #(= :ok (:shiwake/status %)) rs))}))

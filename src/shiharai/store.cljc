(ns shiharai.store
  "SSoT for the 支払 (shiharai) accounts-payable actor. `Store` is a protocol
  injected into the `shiharai.actor` StateGraph; `MemStore` is the default,
  deterministic, zero-dependency backend (itonami actor pattern,
  ADR-2607011000 / CLAUDE.md Actors section). Modelled on
  `cloud-itonami/tehai`'s `tehai.store` — tehai drafts the invoices, this
  actor pays them.

  Domain:

    supplier — a registered counterparty. Carries the destination account,
               the jurisdiction whose tax rules govern it, and whether it is
               recorded as a 中小受託事業者 (which is one of the two
               assertions 取適法 第三条 needs; see `shiharai.law`).
    account  — a `kotoba.banking/account`. Both the supplier's destination
               and this firm's funding accounts live here, so a funding
               account is a thing that was registered rather than a string
               someone typed into a proposal.
    payable  — a supplier invoice. `:payable/amount-minor` is an integer in
               the currency's MINOR unit. An ABSENT amount is unknown, and
               unknown is neither 0 nor unlimited — `outstanding` returns nil
               for it and the governor holds.
    payment  — a scheduled or authorised payment, keyed by `:payment/id`.
               Committing one is what consumes a payable's outstanding
               balance, which is what makes a second payment against a
               settled payable detectable rather than merely unlikely.
    posting  — the double-entry posting a committed payment produced, built
               by the governor with `kotoba.banking` and stored beside it.
    ledger   — append-only audit trail of every proposal / verdict /
               disposition, commit or hold.

  ## What this store cannot do

  There is no method here that moves money, and none that talks to anything.
  `commit-payment!` writes a record whose `:payment/status` is `:scheduled`
  or `:authorised`; a disbursement system that does not live in this
  repository would be what acts on it. See README, \"The ceiling\"."
  (:require [kotoba.banking :as bank]))

(defprotocol Store
  (supplier [s supplier-id])
  (payable [s payable-id])
  (payables-of [s supplier-id])
  (account-of [s account-id])
  (accounts [s])
  (payment [s payment-id])
  (payments [s])
  (payments-for [s payable-id])
  (postings [s])
  (ledger [s])
  (register-supplier! [s x])
  (register-account! [s x])
  (register-payable! [s x])
  (commit-payment! [s p])
  (commit-posting! [s p])
  (append-ledger! [s fact]))

;; ---------------------------------------------------------------------------
;; Derived reads — the ones a hold depends on
;; ---------------------------------------------------------------------------

(def settled-statuses
  "Payment statuses that consume a payable's balance. A `:scheduled` payment
  consumes it just as an `:authorised` one does: if it did not, two schedules
  for the full amount would both pass, and the duplicate would only be caught
  after a human had already approved the second."
  #{:scheduled :authorised})

(defn committed-minor
  "How much of `payable-id` is already covered by committed payments,
  optionally EXCLUDING one payment id.

  The exclusion is what lets `:release-payment` cite the payment it is
  releasing without appearing to double it."
  ([store payable-id] (committed-minor store payable-id nil))
  ([store payable-id exclude-payment-id]
   (->> (payments-for store payable-id)
        (filter #(contains? settled-statuses (:payment/status %)))
        (remove #(and exclude-payment-id (= exclude-payment-id (:payment/id %))))
        (map #(or (:payment/amount-minor %) 0))
        (reduce + 0))))

(defn outstanding
  "What is still owed on `payable-id`, in minor units — or **nil** when the
  payable's own amount is unknown.

  nil, not 0 and not a large number. A payable whose amount nobody recorded
  is the case `cloud.itonami.app.funding` states for balances: 「AN UNKNOWN
  BALANCE IS NOT ZERO, AND IT IS NOT UNLIMITED」. Defaulting it to 0 would
  refuse every payment against it forever; defaulting it to the proposal's
  own figure would let the proposal set its own ceiling. So it refuses to be
  a number, and the governor holds on that."
  ([store payable-id] (outstanding store payable-id nil))
  ([store payable-id exclude-payment-id]
   (let [p (payable store payable-id)
         amount (:payable/amount-minor p)]
     (when (int? amount)
       (- amount (committed-minor store payable-id exclude-payment-id))))))

(defn iban-account?
  "Does this account declare itself to be identified by an IBAN? Only then is
  `bank/iban-valid?` the right question — Japan has no IBAN, and running a
  mod-97 check over a 全銀 account number would refuse every domestic payment
  for the wrong reason."
  [account]
  (= :iban (:account/scheme account)))

(defn destination-check
  "Can this actor verify where the money would go?

  Three values, and the middle one is the point:

    {:destination/coverage :none}     no account on file at all
    {:destination/coverage :unverified :destination/scheme s}
                                      an account is on file under a scheme
                                      this repo has no validator for (全銀,
                                      sort code, …). NOT valid, NOT invalid —
                                      unchecked, which the governor turns
                                      into a human decision rather than a
                                      pass or a hold.
    {:destination/coverage :checked :destination/valid? bool}
                                      an IBAN, checked with ISO 13616 mod-97
                                      via `kotoba.banking/iban-valid?`"
  [account]
  (cond
    (or (nil? account) (nil? (:account/id account)))
    {:destination/coverage :none}

    (iban-account? account)
    {:destination/coverage :checked
     :destination/scheme :iban
     :destination/account (:account/id account)
     :destination/valid? (boolean (bank/iban-valid? (:account/id account)))
     :destination/parsed (bank/parse-iban (:account/id account))}

    :else
    {:destination/coverage :unverified
     :destination/scheme (:account/scheme account)
     :destination/account (:account/id account)
     :destination/why (str "口座体系 " (pr-str (:account/scheme account))
                           " の検証器はこの repo に無い（未検証は有効ではない）")}))

;; ---------------------------------------------------------------------------
;; MemStore
;; ---------------------------------------------------------------------------

(defrecord MemStore [a]
  Store
  (supplier [_ id] (get-in @a [:suppliers id]))
  (payable [_ id] (get-in @a [:payables id]))
  (payables-of [_ supplier-id]
    (filterv #(= supplier-id (:payable/supplier %)) (vals (:payables @a))))
  (account-of [_ id] (get-in @a [:accounts id]))
  (accounts [_] (vec (vals (:accounts @a))))
  (payment [_ id] (get-in @a [:payments id]))
  ;; Keyed by :payment/id, not conj'd onto a vector. Releasing a scheduled
  ;; payment REPLACES it; if both records stayed live, `committed-minor`
  ;; would count the same money twice and the release of a correct payment
  ;; would look like an overpayment.
  (payments [_] (vec (vals (:payments @a))))
  (payments-for [_ payable-id]
    (filterv #(= payable-id (:payment/payable %)) (vals (:payments @a))))
  (postings [_] (:postings @a))
  (ledger [_] (:ledger @a))
  (register-supplier! [s x] (swap! a assoc-in [:suppliers (:supplier/id x)] x) s)
  (register-account! [s x] (swap! a assoc-in [:accounts (:account/id x)] x) s)
  (register-payable! [s x] (swap! a assoc-in [:payables (:payable/id x)] x) s)
  (commit-payment! [s p] (swap! a assoc-in [:payments (:payment/id p)] p) s)
  (commit-posting! [s p] (swap! a update :postings conj p) s)
  (append-ledger! [s fact] (swap! a update :ledger conj fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed]
   (->MemStore (atom (merge {:suppliers {} :accounts {} :payables {}
                             :payments {} :postings [] :ledger []}
                            seed)))))

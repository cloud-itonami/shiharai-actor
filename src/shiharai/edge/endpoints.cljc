(ns shiharai.edge.endpoints
  "The request→response surface shiharai exposes — four functions, and a
  fifth that deliberately does not exist:

      register-payable-core!   record a supplier invoice
      propose-payment-core!    ask whether a payment may be SCHEDULED
      verdict-core!            read back what was decided about a payment id
      ledger-core!             read the caller's own audit trail
      ─────────────────────────────────────────────────────────────────────
      (release / approve)      NOT HERE, and not by omission

  Portable `.cljc` taking plain data and returning `{:status :body}`. No
  framework, no router, no host: whoever mounts these owns the transport,
  and this namespace owns only the decisions. `caller-did` arrives ALREADY
  VERIFIED — this repo ships no verifier, because shipping one would mean
  shipping a key.

  ## Why there is no release endpoint

  This is the sharpest place the actor's ceiling has to hold. `:commit`
  writes `:payment/status :authorised` on the release path, and an
  `:authorised` record is the statement that a human approved a
  disbursement. A request cannot be that human.

  So the op is a CONSTANT here. `propose-payment-core!` hard-codes
  `:op :schedule-payment`; it does not read an op out of the body, which is
  why no body can ask for a release. And when a proposal escalates, the
  graph interrupts at `:request-approval` and this namespace returns 202 and
  stops. **There is no function here that resumes an interrupted thread**,
  and the graph is built per request, so the checkpoint a resume would need
  does not outlive the response. A payment becomes `:authorised` because a
  person drove `shiharai.actor/approve!` in a process that is not this one.

  Scheduling is safe to expose for the same reason drafting is in
  `cloud-itonami/tehai`: the strongest thing it writes is `:scheduled`, and
  `:scheduled` consuming the payable's balance is a REFUSAL mechanism — a
  caller hammering this route gets one scheduled payment and then
  `:duplicate-payment`, forever.

  ## Two gates, and neither is optional

  1. the caller's DID is verified before it reaches here (the deployment's
     job — `cacao.edge.verify` is what the sibling actors mount);
  2. the verified DID must be on the allow-list, which maps DID → SUPPLIER
     ID. A map rather than a set, because that is what makes the supplier a
     derived fact instead of a claim: no body can name a supplier, so a
     signed caller cannot register a payable against — or pay out of —
     another supplier's relationship before the governor's
     `:payable-wrong-supplier` hold ever runs.

  **An absent allow-list serves 503, never an open endpoint.**

  ## Nothing here rounds a three-valued answer to two

  A verdict carries `:tax`, `:registration-gap`, `:retention`,
  `:preservation`, `:destination`, `:payment-terms`, `:outstanding` and
  `:escalations`, and every one of them rides out on the response.

  `:retention` is the one worth naming here. `retention-years` is nil for the
  EU, for the United States and for a jurisdiction nobody catalogued, and a
  response that flattened those to a missing key would let a reader supply
  the seven years the instruments do not state. An unknown outstanding travels as `:unknown`
  and not as `nil` — a nil crossing a JSON boundary becomes `null`, and a
  reader who treats `null` as 0 has turned \"nobody recorded this amount\"
  into \"this invoice is settled\". A payment nobody has a verdict for is
  **404 with `:verdict :unknown`**, not a 200 with an empty body."
  (:require [shiharai.actor :as actor]
            [shiharai.store :as store]
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])))

;; ---------------------------------------------------------------------------
;; Caller identity
;; ---------------------------------------------------------------------------

(defn parse-allowlist
  "`\"did:key:z6Mk…=s-1,did:key:zOther=s-2\"` -> `{did supplier-id}`, or nil
  when absent, blank or wholly malformed. nil is what makes the endpoint
  serve 503 rather than opening."
  [s]
  (when (and (string? s) (seq (.trim s)))
    (let [pairs (keep (fn [entry]
                        (let [[did supplier] (map #(.trim %) (.split entry "="))]
                          (when (and did supplier (seq did) (seq supplier))
                            [did supplier])))
                      (.split (.trim s) ","))]
      (when (seq pairs) (into {} pairs)))))

(defn supplier-for [allowlist did] (get allowlist did))

(defn- gate
  "The response to serve INSTEAD of doing the work, or nil to proceed."
  [allowlist caller-did]
  (cond
    (nil? allowlist)
    {:status 503 :body {:ok false :error "no allow-list configured"}}

    (nil? (supplier-for allowlist caller-did))
    {:status 403 :body {:ok false :error "caller not permitted"}}))

;; ---------------------------------------------------------------------------
;; Bodies
;; ---------------------------------------------------------------------------

(defn parse-body
  "EDN request body -> a map, or nil. Read with `clojure.edn/read-string`,
  which evaluates nothing."
  [s]
  (try
    (let [m (edn/read-string s)]
      (when (map? m) m))
    (catch #?(:clj Exception :cljs :default) _ nil)))

(defn- non-blank
  "`x` when it is a non-blank string, else **nil**.

  nil and not false, which is not a style preference: every caller here
  guards with `(nil? …)`, and `and` returning `false` for a non-string made
  `(nil? id)` false — so a numeric payment id sailed past the 400 and was
  looked up as `false`, answering 404 for a malformed request. Found by the
  test that feeds this a nil."
  [x]
  (when (and (string? x) (seq (.trim x))) x))

(def ^:private supplier-naming-keys
  "Keys a body must not carry. The supplier is derived from the verified
  caller, so a body that names one is either confused or probing. Refusing
  it is not pedantry: SILENTLY IGNORING it is how a caller comes to believe
  they registered an invoice against a supplier they never touched."
  #{:supplier-id :supplier :payable/supplier})

(defn- names-a-supplier? [body]
  (some #(contains? body %) supplier-naming-keys))

(defn- outstanding-value
  "An outstanding balance for a response body. nil becomes `:unknown`,
  never 0 and never null — see the namespace docstring."
  [store payable-id]
  (let [n (store/outstanding store payable-id)]
    (if (int? n) n :unknown)))

;; ---------------------------------------------------------------------------
;; Store selection
;; ---------------------------------------------------------------------------

(defn store-mode
  "How this deployment is configured to store what it accepts, from the
  `SHIHARAI_STORE` env var.

    nil           nothing configured
    :ephemeral    a `MemStore` that does not survive the request
    :journalled   a `DatomicStore` over an append-only journal

  Returns nil for anything else, INCLUDING an unrecognised value — a typo in
  a deployment variable must not silently select a storage mode.

  Portable (takes a plain map) so the decision is testable without a
  platform."
  [env]
  (case (some-> (get env "SHIHARAI_STORE") .trim)
    "ephemeral" :ephemeral
    "journalled" :journalled
    nil))

(defn persistence-of
  "What a given store mode actually promises, as a value that goes out on
  every success response.

    :none        the records are gone when the process is
    :delegated   the records are as durable as the host writing the journal,
                 which is not something this repository can assert on that
                 host's behalf

  `:delegated` is deliberately not the word `durable`. A journal held in
  memory by a Worker that is about to be evicted is a journal, and calling
  it durable here would be this repo vouching for a host it cannot see."
  [mode]
  (case mode
    :ephemeral :none
    :journalled :delegated
    :unknown))

(defn store-unconfigured-response
  "What to serve when no store mode is configured.

  Deliberately 503 and NOT an empty in-process store. An empty store makes
  every request fail the governor's registration check, so the caller is
  told `:no-supplier` — blamed for a deployment that has no store at all.
  Misattributed blame is worse than a refusal: the operator goes looking at
  their own registration while the actual fault is here."
  []
  {:status 503
   :body {:ok false :error "no store configured"
          ;; One line. A multi-line string literal here leaks the source
          ;; file's indentation into the JSON body.
          :hint (str "bind a journalled store (SHIHARAI_STORE=journalled),"
                     " or SHIHARAI_STORE=ephemeral for a non-persisting"
                     " smoke test")}})

;; ---------------------------------------------------------------------------
;; POST /api/payable/register
;; ---------------------------------------------------------------------------

(defn register-payable-core!
  "Record a supplier invoice against the CALLER's own supplier id.

    503  no allow-list configured
    403  caller not on the allow-list
    400  unparseable body, missing payable id or currency, a body that names
         a supplier, or an amount that is not a positive integer
    409  the caller's supplier is not registered, or the payable id is taken
    201  registered

  Two refusals worth their own sentence.

  **A body may not name a supplier.** It comes from the DID (see the ns
  docstring).

  **An existing payable id is refused rather than overwritten.** The
  protocol's `register-payable!` upserts, which is right for an operator
  correcting a record and wrong for an endpoint: overwriting
  `:payable/amount-minor` moves the ceiling that `:overpayment` and
  `:duplicate-payment` are measured against, under payments that may already
  be scheduled. A correction is not a request.

  **An absent amount is accepted, and says so.** A payable whose amount
  nobody recorded is a real thing; the response reports `:outstanding
  :unknown` and that no payment can be scheduled against it. Defaulting it
  to 0 here would be the same lie the store refuses to tell."
  [store allowlist caller-did raw-body]
  (or (gate allowlist caller-did)
      (let [supplier-id (supplier-for allowlist caller-did)
            body (parse-body raw-body)
            payable-id (some-> body :payable-id non-blank)
            currency (some-> body :currency non-blank)
            amount (:amount-minor body)]
        (cond
          (nil? body)
          {:status 400 :body {:ok false :error "invalid request body"}}

          (names-a-supplier? body)
          {:status 400 :body {:ok false :error "a body may not name a supplier"
                              :hint "the supplier is derived from the verified caller"}}

          (nil? payable-id)
          {:status 400 :body {:ok false :error "no payable id"}}

          (nil? currency)
          {:status 400 :body {:ok false :error "no currency"}}

          (and (some? amount) (not (and (int? amount) (pos? amount))))
          {:status 400 :body {:ok false :error "amount-minor must be a positive integer"
                              :hint "omit it entirely if the amount is unknown"}}

          (nil? (store/supplier store supplier-id))
          {:status 409 :body {:ok false :error "supplier not registered"
                              :supplier supplier-id}}

          (some? (store/payable store payable-id))
          {:status 409 :body {:ok false :error "payable id already registered"
                              :payable payable-id
                              :hint (str "an endpoint does not overwrite a payable;"
                                         " doing so would move the ceiling that"
                                         " :overpayment and :duplicate-payment are"
                                         " measured against")}}

          :else
          (do
            (store/register-payable!
             store
             (-> (select-keys body [:payable/currency :payable/received-date
                                    :payable/due-date :payable/jurisdiction
                                    :payable/mandate :payable/origin
                                    :payable/preservation
                                    :payable/registration-number
                                    :payable/claims-input-tax-credit?
                                    :payable/ap-account])
                 (assoc :payable/id payable-id
                        :payable/supplier supplier-id
                        :payable/currency currency)
                 (cond-> (int? amount) (assoc :payable/amount-minor amount))))
            {:status 201
             :body {:ok true
                    :payable payable-id
                    :supplier supplier-id
                    :currency currency
                    :outstanding (outstanding-value store payable-id)
                    :note (if (int? amount)
                            "payable registered"
                            (str "payable registered with an UNKNOWN amount;"
                                 " no payment can be scheduled against it until"
                                 " an amount is recorded"))}})))))

;; ---------------------------------------------------------------------------
;; POST /api/payment/propose
;; ---------------------------------------------------------------------------

(defn- assessment-body
  "The parts of a verdict that are answers rather than flags. Carried out
  verbatim on every outcome, including the green one: a verdict that skipped
  a question has to show the question."
  [verdict]
  {:destination (:destination verdict)
   :tax (:tax verdict)
   ;; What the catalog did NOT check about a registration number it accepted.
   ;; Rides out beside `:tax` rather than inside it, because a reader who
   ;; stops at `:taxlaw/supported? true` has to trip over this to be wrong —
   ;; in the EU it is present on every accepted claim.
   :registration-gap (:registration-gap verdict)
   ;; Three-valued, and never an invented number. See
   ;; `shiharai.jurisdiction/retention`.
   :retention (:retention verdict)
   :preservation (:preservation verdict)
   :payment-terms (:payment-terms verdict)
   :outstanding (let [n (:outstanding verdict)] (if (int? n) n :unknown))
   :escalations (mapv #(select-keys % [:rule :detail]) (:escalations verdict))})

(defn propose-payment-core!
  "Ask whether a payment may be SCHEDULED against a payable. Never released.

    503  no allow-list configured
    403  caller not on the allow-list
    400  unparseable body, or no payable / payment id / funding account
    409  the governor held it, with the violations
    202  a human has to decide; NOTHING was scheduled
    200  scheduled

  The op is a constant (`:schedule-payment`), not a field. A body carrying
  `:op :release-payment` is scheduled like any other, which is the point:
  there is no request that reaches the release path.

  202 rather than 200 for an escalation, because collapsing \"a person must
  look at this\" into either of its neighbours is exactly the two-valued
  rounding this actor refuses. And 202 is honest about what happened: the
  graph interrupted before `:request-approval`, nothing was written, and the
  interrupted thread is NOT resumable from here."
  [store mode allowlist caller-did raw-body]
  (or (gate allowlist caller-did)
      (let [supplier-id (supplier-for allowlist caller-did)
            body (parse-body raw-body)
            payable (some-> body :payable non-blank)
            payment-id (some-> body :payment-id non-blank)
            from-account (some-> body :from-account non-blank)]
        (cond
          (nil? body) {:status 400 :body {:ok false :error "invalid request body"}}
          (names-a-supplier? body)
          {:status 400 :body {:ok false :error "a body may not name a supplier"
                              :hint "the supplier is derived from the verified caller"}}
          (nil? payable) {:status 400 :body {:ok false :error "no payable named"}}
          (nil? payment-id) {:status 400 :body {:ok false :error "no payment id"}}
          (nil? from-account) {:status 400 :body {:ok false :error "no funding account"}}

          :else
          (let [g (actor/build-graph {:store store})
                r (actor/run-request! g {:supplier-id supplier-id
                                         :op :schedule-payment
                                         :payable payable
                                         :payment-id payment-id
                                         :from-account from-account
                                         :payment-date (:payment-date body)
                                         :amount-minor (:amount-minor body)}
                                      {} (str "edge-" caller-did "-" payment-id))
                verdict (get-in r [:state :verdict])
                disposition (get-in r [:state :disposition])
                common (merge {:supplier supplier-id :payable payable
                               :payment payment-id
                               :persistence (persistence-of mode)}
                              (assessment-body verdict))]
            (case disposition
              :commit
              {:status 200
               :body (merge common
                            {:ok true
                             :scheduled true
                             :authorised false
                             :payment-status (:payment/status
                                              (store/payment store payment-id))
                             :outstanding-now (outstanding-value store payable)
                             :note (:rationale (get-in r [:state :proposal]))})}

              :request-approval
              {:status 202
               :body (merge common
                            {:ok false
                             :scheduled false
                             :authorised false
                             :disposition :request-approval
                             :note (str "a person has to decide. Nothing was"
                                        " scheduled, and this surface cannot"
                                        " resume the thread")})}

              ;; :hold, and anything a future verdict shape routes elsewhere.
              ;; The default is the refusing one on purpose.
              {:status 409
               :body (merge common
                            {:ok false
                             :scheduled false
                             :authorised false
                             :disposition (or disposition :hold)
                             :hard? (boolean (:hard? verdict))
                             :violations (mapv #(select-keys % [:rule :detail])
                                               (:violations verdict))})}))))))

;; ---------------------------------------------------------------------------
;; GET /api/payment/verdict
;; ---------------------------------------------------------------------------

(defn- entries-for-supplier [store supplier-id]
  (filterv #(= supplier-id (:supplier-id %)) (store/ledger store)))

(defn verdict-core!
  "Read back what was decided about one payment id.

    503  no allow-list configured
    403  caller not on the allow-list
    400  no payment id given
    404  nothing on record for that id, for this caller — `:verdict :unknown`
    200  the recorded disposition and verdict

  404 carries `:verdict :unknown` rather than an empty 200, because those
  are different sentences: one says the actor refused nothing, the other
  says the actor was never asked. A console that renders an empty success as
  \"clear\" would show a payment that was never proposed as one that passed.

  A payment id belonging to ANOTHER supplier answers the same 404. Not 403 —
  a 403 would confirm the id exists, which is a fact about another
  supplier's payables that this caller is not entitled to."
  [store allowlist caller-did payment-id]
  (or (gate allowlist caller-did)
      (let [supplier-id (supplier-for allowlist caller-did)
            id (non-blank payment-id)]
        (if (nil? id)
          {:status 400 :body {:ok false :error "no payment id"}}
          (let [entry (last (filterv #(= id (:payment-id %))
                                     (entries-for-supplier store supplier-id)))]
            (if (nil? entry)
              {:status 404
               :body {:ok false
                      :verdict :unknown
                      :payment id
                      :error "no verdict on record for that payment id"
                      :hint (str "this is not a pass. Nothing was refused"
                                 " because nothing was asked")}}
              (let [v (:verdict entry)
                    pmt (store/payment store id)]
                {:status 200
                 :body (merge {:ok true
                               :payment id
                               :supplier supplier-id
                               :disposition (:disposition entry)
                               :hard? (boolean (:hard? v))
                               :escalate? (boolean (:escalate? v))
                               :violations (mapv #(select-keys % [:rule :detail])
                                                 (:violations v))
                               ;; `:not-committed` rather than nil: a held
                               ;; payment has no record, and that is a state
                               ;; rather than a missing field.
                               :payment-status (or (:payment/status pmt) :not-committed)}
                              (assessment-body v))})))))))

;; ---------------------------------------------------------------------------
;; GET /api/ledger
;; ---------------------------------------------------------------------------

(defn ledger-core!
  "The caller's own append-only audit trail.

    503  no allow-list configured
    403  caller not on the allow-list
    200  this supplier's entries, oldest first

  Scoped to the caller's supplier, and the response says so — `:scope
  :supplier`, so nobody reads a filtered list as the whole ledger.

  `:unattributed` counts entries carrying no supplier id at all. It is
  normally 0; both the `:commit` and the `:hold` node attribute what they
  write. A non-zero value means some part of this ledger cannot be shown to
  anyone, which is a defect in the writer and belongs in the response rather
  than in a silence. Other suppliers' entries are not counted — a count is
  still a disclosure."
  [store allowlist caller-did]
  (or (gate allowlist caller-did)
      (let [supplier-id (supplier-for allowlist caller-did)
            all (store/ledger store)
            mine (filterv #(= supplier-id (:supplier-id %)) all)]
        {:status 200
         :body {:ok true
                :scope :supplier
                :supplier supplier-id
                :visible (count mine)
                :unattributed (count (filterv #(nil? (:supplier-id %)) all))
                :entries (mapv (fn [e]
                                 {:disposition (:disposition e)
                                  :payment (:payment-id e)
                                  :hard? (boolean (get-in e [:verdict :hard?]))
                                  :violations (mapv :rule
                                                    (get-in e [:verdict :violations]))})
                               mine)}})))

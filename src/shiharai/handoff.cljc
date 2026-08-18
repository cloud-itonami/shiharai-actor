(ns shiharai.handoff
  "受領確認 — what `cloud-itonami-isco-4311` DID with an entry this actor
  emitted, as a ledger fact.

  `shiharai.shiwake` converts an authorised payment into the `:draft-entry`
  request that actor accepts. **Converted is not posted.** The ledger actor
  can commit the entry, find it already there, hold it against a rule, park
  it for a human, or refuse the body outright — and until this namespace
  existed all five arrived here as the same nothing, because the emitting
  side wrote none of them down.

  That is the same defect one layer up from the one `shiwake` exists for. A
  payment that was authorised and never converted is money nobody's books
  show; a payment that was converted, submitted and REFUSED is the same
  money, and it was indistinguishable from one that posted. Neither has an
  error message. Both actors report a clean run and the books are short by
  one disbursement.

  So this namespace turns one reply into a value the actor appends with
  `shiharai.store/append-ledger!`, beside the `:commit` and `:hold` facts
  the graph already writes.

  ## It produces a value, it does not make a call

  Same ceiling as everything else here: this actor proposes. It does not
  submit the entry and it does not read the reply off a socket — it is
  handed a response somebody else obtained. It requires exactly
  `clojure.string`, asserted by a test that reads its own source, and
  `shiharai.ceiling-test` pins it against the repository-wide allow-list.

  ## The good outcome is recorded too

  A ledger recording only refusals cannot answer 「これは計上されたか」,
  which is the one question the hand-off exists to close. `:posted` is a
  fact and is written down like any other.

  ## `:duplicate` is not `:posted`

  One call WROTE the posting; the other found it already there. 4311 is
  idempotent on the posting id and reports which happened. Folding them
  would make a re-send and a first post the same event in this ledger, and
  on this side of the books that distinction is the difference between a
  disbursement recorded once and one recorded twice.

  ## An unreadable reply is never a success

  A status this namespace does not know, a body that is not a map, a 200
  that does not say whether it duplicated: all `:unreadable`, carrying the
  response so somebody can see what actually arrived. A step that could not
  be read must not report the same value as a step that went well.

  ## The fact can be joined back

  Every fact names the payable it cites — the supplier invoice, the source
  document on both sides of the hand-off — plus the supplier, the payment
  id, and 4311's posting id where there is one. `shiwake` already states
  the limit this works around: the entry body carries no payment id,
  because the ledger's parser drops everything but `:source-doc` and
  `:lines`, so an entry can only be traced back through the payable. The
  fact carries the payment id on THIS side, where nothing drops it."
  (:require [clojure.string :as str]))

(def outcomes
  "Every value `:handoff/outcome` can take. Named as a set so the whole
  vocabulary is readable in one place, and so a test can assert nothing
  outside it is ever invented.

  The first seven mirror what the ledger answers. The last four are this
  side's: `:not-submitted` (there was no request, so the reply answers
  something else), `:misattributed` (the reply cites a different source
  document from the one it was paired with), `:length-mismatch` (the batch
  could not be paired at all) and `:unreadable`."
  #{:posted :duplicate :awaiting-approval :held :rejected
    :not-permitted :unavailable
    :not-submitted :misattributed :length-mismatch :unreadable})

(def posted-outcomes
  "The outcomes that mean the entry is IN the ledger. Two, not one, and
  never folded: one of them wrote it and the other confirmed it was already
  written."
  #{:posted :duplicate})

(defn- status-code
  "The numeric status, or nil. Normalised through `long`, so a status that
  arrived as 200.0 out of a decoder is the same 200 the tables below match —
  `(= 200 200.0)` is false, and a reply read as a double would otherwise
  fall through to `:unreadable`."
  [s]
  (when (number? s) (long s)))

(defn- absent? [x] (str/blank? (str x)))

(defn- single-outcome
  "One single-entry reply -> an outcome.

  400, 403 and 503 stay three answers where the ledger's own batch collapses
  them to `:rejected`. They send whoever reads this ledger to three
  different places: 400 is the body this actor emitted, 403 is who it
  authenticated as, 503 is a ledger deployment with no store and no
  allow-list. That actor makes exactly this argument about its own 503 —
  misattributed blame sends an operator to look at their own registration
  while the fault is elsewhere."
  [status body]
  (if-not (map? body)
    :unreadable
    (condp = status
      200 (cond
            (not (true? (:ok body))) :unreadable
            ;; A 200 that does not say whether it duplicated cannot be
            ;; called `:posted`: that would claim this call wrote the
            ;; posting, which is precisely what such a body does not say.
            (true? (:duplicate? body)) :duplicate
            (false? (:duplicate? body)) :posted
            :else :unreadable)
      202 :awaiting-approval
      409 :held
      400 :rejected
      403 :not-permitted
      503 :unavailable
      :unreadable)))

(defn- detail
  "The part of the reply worth keeping next to the outcome. `:unreadable`
  carries the WHOLE reply, because the point of that outcome is that nobody
  yet knows which part of it matters."
  [outcome status body response]
  (cond-> {}
    (= :held outcome) (assoc :handoff/violations (vec (:violations body)))
    (= :awaiting-approval outcome) (assoc :handoff/reason (:reason body))
    (and (map? body) (:error body)) (assoc :handoff/error (:error body))
    (= :unreadable outcome) (assoc :handoff/response response
                                   :handoff/raw-status status)))

(defn- identity-of
  "What the fact is about. The supplier is read from `:shiwake/record`,
  which `shiharai.shiwake/entry-requests` attaches to every conversion — it
  is the store's own ledger fact, stamped with the same `:supplier-id` and
  `:payment-id` the `:commit` and `:hold` nodes stamp. A fact built from a
  bare `entry-request` result carries nil there and is still joinable
  through the payable and the payment id."
  [converted]
  {:handoff/payable (get-in converted [:shiwake/request :source-doc])
   :handoff/payment-id (:shiwake/payment-id converted)
   :handoff/supplier-id (get-in converted [:shiwake/record :supplier-id])})

(defn- not-submitted
  [converted response why]
  (merge (identity-of converted)
         {:handoff/outcome :not-submitted
          :handoff/conversion (:shiwake/status converted)
          :handoff/why why
          :handoff/response response}))

(defn fact
  "One ledger reply as a `shiharai.store/ledger` fact.

      {:handoff/outcome :posted | :duplicate | :awaiting-approval | :held
                        | :rejected | :not-permitted | :unavailable
                        | :not-submitted | :unreadable
       :handoff/status  200
       :handoff/payable \"pbl-1\"
       :handoff/payment-id \"pay-1\"
       :handoff/supplier-id \"s-1\"
       :handoff/posting \"pst-…\"   ;; nil when the entry produced none
       …}

  `converted` is a `shiharai.shiwake` conversion — the `:shiwake/status :ok`
  map whose `:shiwake/request` was submitted. `response` is what came back:
  `{:status n :body {…}}`.

  A conversion that is not `:ok` never became a request, so a reply cannot
  belong to it: that is `:not-submitted`, and it keeps the reply rather than
  scoring it against a payment nobody sent. **The status is what decides
  this, not the presence of a request-shaped map** — a refusal carrying a
  stale request must not be read as a submission. Same for a conversion
  citing no source document: with nothing to cite, the fact could not be
  joined to a payable, and an unjoinable reconciliation record is not one.

  `:handoff/posting` is present even when nil, the same way the ledger
  reports it and for the same reason: an entry that committed without
  producing a posting is exactly what a reader has to be able to see, and a
  key that vanishes when the answer is interesting is a key nobody can
  query on.

  Pure. It is handed a reply; it does not go and get one."
  [converted response]
  (let [payable (get-in converted [:shiwake/request :source-doc])]
    (cond
      (not= :ok (:shiwake/status converted))
      (not-submitted converted response
                     (str "変換結果が " (pr-str (:shiwake/status converted))
                          " なので request は作られていない。この応答は別の"
                          "何かに対する答えであり、ここで採点すれば、出して"
                          "いない支払に結果が付く"))

      (absent? payable)
      (not-submitted converted response
                     (str "原始証憑（payable）が無いので、この結果を支払に"
                          "突き合わせられない。照合できない照合記録は照合記録"
                          "ではない"))

      :else
      (let [status (status-code (:status response))
            body (:body response)
            outcome (single-outcome status body)]
        (merge (identity-of converted)
               {:handoff/outcome outcome
                :handoff/status status
                :handoff/posting (when (map? body) (:posting body))}
               (detail outcome status body response))))))

;; ---------------------------------------------------------------------------
;; the batch
;; ---------------------------------------------------------------------------

(defn- result-outcome
  "One entry of a batch `:results` -> an outcome.

  A batch result carries BOTH a status and the ledger's own `:outcome`, and
  this reads both. It has to read `:outcome`, because a batch result does
  not carry `:duplicate?` and that keyword is the only place the posted /
  duplicate distinction survives the batch. It has to read `:status`,
  because taking `:outcome` alone would let a 500 arrive labelled
  `:posted`. Where the two disagree nobody knows what happened, and
  `:unreadable` is what that is called here."
  [status outcome]
  (let [permitted (condp = status
                    200 #{:posted :duplicate}
                    202 #{:awaiting-approval}
                    409 #{:held}
                    400 #{:rejected}
                    403 #{:rejected}
                    503 #{:rejected}
                    nil)]
    (if (and permitted (contains? permitted outcome))
      (condp = status
        403 :not-permitted
        503 :unavailable
        outcome)
      :unreadable)))

(defn- result-fact
  [converted result]
  (let [payable (get-in converted [:shiwake/request :source-doc])
        echoed (:source-doc result)]
    (cond
      (or (not= :ok (:shiwake/status converted)) (absent? payable))
      (not-submitted converted result
                     "request を作らなかった変換結果に、結果は帰属しない")

      ;; The ledger echoes the source document it read out of the entry. If
      ;; the echo is not the document this conversion submitted, the pairing
      ;; is wrong — and an outcome written against the wrong payable is
      ;; worse than none, because it reads as an answer.
      (and (some? echoed) (not= echoed payable))
      (merge (identity-of converted)
             {:handoff/outcome :misattributed
              :handoff/answered-for echoed
              :handoff/why (str "台帳は原始証憑 " (pr-str echoed)
                                " について答えたが、提出したのは "
                                (pr-str payable)
                                "。結果の順序が崩れており、ここのどの結果も"
                                "帰属させられない")
              :handoff/response result})

      :else
      (let [status (status-code (:status result))
            outcome (result-outcome status (:outcome result))]
        (merge (identity-of converted)
               {:handoff/outcome outcome
                :handoff/status status
                :handoff/posting (:posting result)}
               (cond-> {}
                 (= :held outcome) (assoc :handoff/violations
                                          (vec (:violations result)))
                 (:error result) (assoc :handoff/error (:error result))
                 (= :unreadable outcome) (assoc :handoff/response result
                                                :handoff/raw-status status)))))))

(defn facts
  "A batch reply as one ledger fact per submitted entry.

      {:handoff/status :paired | :length-mismatch | :unreadable-batch
       :handoff/facts  [fact …]
       :handoff/summary {outcome count}}

  `converted` is the sequence of conversions that were submitted, IN
  SUBMISSION ORDER — the ledger returns `:results` in that order, and that
  order is the only thing joining a result to its entry.

  ## A length mismatch is refused, not zipped

  If the counts differ, pairing by position misattributes EVERY outcome
  after the first missing one, and the entries that fall off the end of the
  shorter sequence get no outcome at all while the run reports clean. There
  is no partial pairing that is safe here, so there is none.

  ## The refusal is itself a fact

  A refused batch still returns `:handoff/facts` with one fact in it,
  describing the mismatch. Returning an empty vector would mean a caller
  looping over the facts and appending them wrote NOTHING, and a hand-off
  that failed would look exactly like a hand-off nobody attempted — the
  defect this namespace exists to remove, reproduced by the namespace
  itself."
  [converted batch]
  (let [converted (vec converted)
        results (get-in batch [:body :results])
        wrap (fn [status f] {:handoff/status status
                             :handoff/facts [f]
                             :handoff/summary {(:handoff/outcome f) 1}})]
    (cond
      (not (sequential? results))
      (wrap :unreadable-batch
            {:handoff/outcome :unreadable
             :handoff/status (status-code (:status batch))
             :handoff/submitted (count converted)
             :handoff/why (str "batch 応答に :results が無いので、提出した "
                               (count converted)
                               " 件のどれ一つ結果が分からない")
             :handoff/response batch})

      (not= (count converted) (count results))
      (wrap :length-mismatch
            {:handoff/outcome :length-mismatch
             :handoff/status (status-code (:status batch))
             :handoff/submitted (count converted)
             :handoff/answered (count results)
             :handoff/why (str "提出 " (count converted) " 件に対し結果 "
                               (count results)
                               " 件。結果と entry は位置でしか結び付かないので、"
                               "このまま組にすれば別の支払に結果が付く")
             :handoff/response batch})

      :else
      (let [fs (mapv result-fact converted (vec results))]
        {:handoff/status :paired
         :handoff/facts fs
         :handoff/summary (frequencies (map :handoff/outcome fs))}))))

(ns shiharai.handoff-test
  "What the ledger did with the entry — and every way that answer can be
  lost on the way back.

  `shiharai.shiwake-test` exists because a payment can be authorised and
  never converted, with nothing going red. This file exists because a
  converted payment can be SUBMITTED and never posted, with nothing going
  red either: `cloud-itonami-isco-4311` posts, or finds a duplicate, or
  holds, or parks it, or refuses the body — and from this side all five
  used to arrive as the same silence.

  Four things are pinned:

  1. the good outcome is recorded, not only the refusals — otherwise the
     ledger cannot answer the one question the hand-off closes;
  2. `:duplicate` never collapses into `:posted`;
  3. nothing unreadable is ever scored as a success;
  4. a batch whose results cannot be paired with its entries is refused
     rather than zipped."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shiharai.actor :as actor]
            [shiharai.fixtures :as fx]
            [shiharai.handoff :as handoff]
            [shiharai.shiwake :as shiwake]
            [shiharai.store :as store]))

(def ^:private mapping
  {:suppliers {"s-1" "買掛金" "s-2" "未払金"}
   :accounts  {"FUND-EUR" "普通預金（EUR）" "FUND-JPY" "普通預金（JPY）"}})

(defn- committed
  "A `store/ledger` fact, the shape the graph's `:commit` node appends."
  [& {:keys [payable payment-id supplier-id disposition status]
      :or {payable "pbl-1" payment-id "pay-1" supplier-id "s-1"
           disposition :commit status :authorised}}]
  {:disposition disposition
   :supplier-id supplier-id
   :payment-id payment-id
   :record {:supplier-id supplier-id
            :op :release-payment
            :payable payable
            :payment {:payment/id payment-id
                      :payment/payable payable
                      :payment/amount-minor 120000
                      :payment/currency "EUR"
                      :payment/from-account "FUND-EUR"
                      :payment/status status
                      :payment/supplier supplier-id}}})

(defn- conversions
  "Real `shiharai.shiwake` output, so this suite scores the value the actor
  actually submits rather than a hand-built stand-in of it."
  [& payables]
  (:ok (shiwake/entry-requests
        (map-indexed (fn [i p] (committed :payable p :payment-id (str "pay-" (inc i))))
                     payables)
        mapping)))

(defn- conv [payable] (first (conversions payable)))

(defn- reply [status body] {:status status :body body})

(defn- posted-reply
  ([] (posted-reply false "pst-1"))
  ([duplicate? posting]
   (reply 200 {:ok true :client "c-1" :ephemeral true
               :duplicate? duplicate? :posting posting
               :posting-count 1 :balanced? true})))

(defn- result
  "One entry of a batch `:results`, shaped as the ledger's entries endpoint
  builds it."
  [source-doc status outcome & {:keys [posting violations error]}]
  (cond-> {:status status :outcome outcome :source-doc source-doc}
    posting (assoc :posting posting)
    violations (assoc :violations violations)
    error (assoc :error error)))

(defn- batch-reply [results]
  {:status 207
   :body {:client "c-1" :submitted (count results)
          :summary (frequencies (map :outcome results))
          :results results}})

;; ---------------------------------------------------------------------------
;; the good outcome is a fact too
;; ---------------------------------------------------------------------------

(deftest a-posted-entry-is-recorded
  (testing "a ledger holding only refusals cannot answer 「計上されたか」,
            which is the one question the hand-off exists to close"
    (let [f (handoff/fact (conv "pbl-1") (posted-reply))]
      (is (= :posted (:handoff/outcome f)))
      (is (= 200 (:handoff/status f)))
      (is (= "pst-1" (:handoff/posting f))))))

(deftest a-duplicate-is-its-own-outcome
  (testing "one call WROTE the posting and the other found it already there.
            On this side of the books that is the difference between a
            disbursement recorded once and one recorded twice"
    (let [f (handoff/fact (conv "pbl-1") (posted-reply true "pst-1"))]
      (is (= :duplicate (:handoff/outcome f)))
      (is (not= :posted (:handoff/outcome f)))
      (is (= "pst-1" (:handoff/posting f)))))
  (testing "and both still mean the entry is in the ledger"
    (is (= handoff/posted-outcomes
           (set (for [d [false true]]
                  (:handoff/outcome (handoff/fact (conv "pbl-1")
                                                  (posted-reply d "pst-1")))))))))

(deftest every-status-the-ledger-can-answer-with-becomes-a-named-outcome
  (testing "no status that endpoint can return may arrive here as nil — a
            nil outcome in the ledger is the silence this namespace removes"
    (doseq [[status body expected]
            [[200 {:ok true :duplicate? false :posting "p"} :posted]
             [200 {:ok true :duplicate? true :posting "p"} :duplicate]
             [202 {:ok false :disposition :request-approval
                   :reason "external send"} :awaiting-approval]
             [409 {:ok false :disposition :hold
                   :violations [{:rule :unknown-source-doc :detail "pbl-1"}]} :held]
             [400 {:ok false :error "invalid request body"} :rejected]
             [403 {:ok false :error "caller not permitted"} :not-permitted]
             [503 {:ok false :error "no store configured"} :unavailable]]]
      (let [f (handoff/fact (conv "pbl-1") (reply status body))]
        (is (= expected (:handoff/outcome f)) (str "status " status))
        (is (= status (:handoff/status f)))))))

(deftest the-three-deployment-refusals-stay-three-answers
  (testing "the ledger's own batch collapses 400/403/503 into :rejected.
            They send a reader to three different places — the body this
            actor emitted, who it authenticated as, and a ledger with no
            store — and misattributed blame is the failure that endpoint's
            own 503 exists to avoid"
    (is (= 3 (count (set (for [s [400 403 503]]
                           (:handoff/outcome
                            (handoff/fact (conv "pbl-1")
                                          (reply s {:ok false :error "x"}))))))))))

(deftest a-refusal-carries-what-it-was-refused-for
  (testing "violations, so the queue this fact creates can be worked"
    (let [v [{:rule :unknown-source-doc :detail "pbl-1 not registered"}]
          f (handoff/fact (conv "pbl-1") (reply 409 {:ok false :violations v}))]
      (is (= v (:handoff/violations f)))))
  (testing "the escalation reason, so a parked entry says what it waits on"
    (is (= "external send"
           (:handoff/reason (handoff/fact (conv "pbl-1")
                                          (reply 202 {:ok false
                                                      :reason "external send"}))))))
  (testing "and the error text, the only place a 400 says which part of the
            body the ledger would not take"
    (is (= "invalid request body"
           (:handoff/error (handoff/fact (conv "pbl-1")
                                         (reply 400 {:ok false
                                                     :error "invalid request body"})))))))

;; ---------------------------------------------------------------------------
;; nothing unreadable becomes a success
;; ---------------------------------------------------------------------------

(deftest an-unrecognised-reply-is-never-a-success
  (doseq [[label response]
          [["a status nobody here knows" (reply 500 {:ok false})]
           ["a redirect" (reply 301 {})]
           ["no status at all" {:body {:ok true :duplicate? false}}]
           ["a status that is not a number" (reply "200" {:ok true :duplicate? false})]
           ["a body that is not a map" (reply 200 "OK")]
           ["no body at all" {:status 200}]
           ["a 200 that says it did not succeed" (reply 200 {:ok false :posting "p"})]]]
    (let [f (handoff/fact (conv "pbl-1") response)]
      (is (= :unreadable (:handoff/outcome f)) label)
      (is (not (contains? handoff/posted-outcomes (:handoff/outcome f))) label)
      (testing "and it carries the reply, or nobody can diagnose it"
        (is (= response (:handoff/response f)) label)))))

(deftest a-200-that-does-not-say-whether-it-duplicated-is-unreadable
  (testing "calling it :posted would claim THIS call wrote the posting,
            which is exactly what such a body does not say"
    (doseq [body [{:ok true :posting "p"}
                  {:ok true :posting "p" :duplicate? nil}
                  {:ok true :posting "p" :duplicate? "false"}]]
      (is (= :unreadable
             (:handoff/outcome (handoff/fact (conv "pbl-1") (reply 200 body))))))))

;; ---------------------------------------------------------------------------
;; the fact can be joined back
;; ---------------------------------------------------------------------------

(deftest the-fact-names-what-it-is-about
  (testing "a reconciliation record that cannot be joined to the thing it
            reconciles is not one. The payable is the join key the entry
            itself carries; the payment id is the one the entry CANNOT
            carry, because the ledger's parser drops everything but
            :source-doc and :lines — so this side keeps it"
    (let [f (handoff/fact (conv "pbl-1") (posted-reply false "pst-9"))]
      (is (= "pbl-1" (:handoff/payable f)))
      (is (= "pay-1" (:handoff/payment-id f)))
      (is (= "s-1" (:handoff/supplier-id f)))
      (is (= "pst-9" (:handoff/posting f)))))
  (testing "and all three are named on a REFUSAL too — a refusal nobody can
            attribute is a refusal nobody can act on, which is the same
            argument the actor's own :hold node already makes"
    (doseq [r [(reply 409 {:ok false :violations []})
               (reply 400 {:ok false :error "invalid request body"})
               (reply 500 {})]]
      (let [f (handoff/fact (conv "pbl-1") r)]
        (is (= "pbl-1" (:handoff/payable f)))
        (is (= "pay-1" (:handoff/payment-id f)))
        (is (= "s-1" (:handoff/supplier-id f))))))
  (testing ":handoff/posting is PRESENT even when there is none — a key that
            vanishes when the answer is interesting cannot be queried on"
    (let [f (handoff/fact (conv "pbl-1") (reply 200 {:ok true :duplicate? false}))]
      (is (contains? f :handoff/posting))
      (is (nil? (:handoff/posting f))))))

(deftest a-conversion-that-produced-no-request-is-not-scored
  (testing "a reply cannot belong to a payment nobody sent; attributing one
            would record an outcome for a disbursement that never left"
    (let [skipped (first (:skipped (shiwake/entry-requests
                                    [(committed :status :scheduled)] mapping)))
          f (handoff/fact skipped (posted-reply))]
      (is (= :not-submitted (:handoff/outcome f)))
      (is (= :not-released (:handoff/conversion f)))
      (is (not (contains? handoff/posted-outcomes (:handoff/outcome f))))))
  (testing "a conversion citing no source document is refused for the same
            reason: the fact could not be joined to a payable"
    (is (= :not-submitted
           (:handoff/outcome
            (handoff/fact {:shiwake/status :ok :shiwake/request {:source-doc "  "}}
                          (posted-reply))))))
  (testing "the STATUS is what says whether something was submitted, not the
            presence of a request-shaped map. Measured: without this case,
            making the status check unconditional reddened nothing, because
            every refusal shiwake itself emits also lacks a source document
            and was caught by the second guard"
    (doseq [s [:not-authorised :not-released :unknown-payment-status
               :no-mapping :unusable-payment]]
      (let [stale {:shiwake/status s
                   :shiwake/payment-id "pay-1"
                   :shiwake/request {:op :draft-entry :source-doc "pbl-1" :lines []}
                   :shiwake/record {:supplier-id "s-1"}}
            f (handoff/fact stale (posted-reply))
            b (handoff/facts [stale] (batch-reply [(result "pbl-1" 200 :posted)]))]
        (is (= :not-submitted (:handoff/outcome f)) (str s))
        (is (= s (:handoff/conversion f)))
        (is (= :not-submitted (:handoff/outcome (first (:handoff/facts b)))) (str s))))))

;; ---------------------------------------------------------------------------
;; the batch
;; ---------------------------------------------------------------------------

(deftest a-batch-pairs-each-result-with-the-entry-it-answers
  (let [cs (conversions "pbl-1" "pbl-2" "pbl-3")
        r (handoff/facts cs (batch-reply [(result "pbl-1" 200 :posted :posting "p1")
                                          (result "pbl-2" 200 :duplicate :posting "p1")
                                          (result "pbl-3" 409 :held
                                                  :violations [{:rule :unbalanced}])]))]
    (is (= :paired (:handoff/status r)))
    (is (= 3 (count (:handoff/facts r))))
    (is (= ["pbl-1" "pbl-2" "pbl-3"] (mapv :handoff/payable (:handoff/facts r))))
    (is (= ["pay-1" "pay-2" "pay-3"] (mapv :handoff/payment-id (:handoff/facts r))))
    (is (= [:posted :duplicate :held] (mapv :handoff/outcome (:handoff/facts r))))
    (is (= {:posted 1 :duplicate 1 :held 1} (:handoff/summary r)))
    (testing "and the held one keeps its violations"
      (is (= [{:rule :unbalanced}] (:handoff/violations (nth (:handoff/facts r) 2)))))))

(deftest a-length-mismatch-is-refused-rather-than-zipped
  (testing "results join to entries by POSITION only. One missing result
            misattributes every outcome after it, and the entries that fall
            off the end get no outcome at all while the run reports clean"
    (doseq [[submitted answered]
            [[["pbl-1" "pbl-2" "pbl-3"] ["pbl-1" "pbl-2"]]
             [["pbl-1"] ["pbl-1" "pbl-2"]]
             [["pbl-1" "pbl-2"] []]]]
      (let [cs (apply conversions submitted)
            r (handoff/facts cs (batch-reply (mapv #(result % 200 :posted :posting "p")
                                                   answered)))]
        (is (= :length-mismatch (:handoff/status r))
            (str submitted " vs " answered))
        (is (= (count submitted) (:handoff/submitted (first (:handoff/facts r)))))
        (is (= (count answered) (:handoff/answered (first (:handoff/facts r)))))))))

(deftest a-refused-batch-still-yields-a-fact-to-append
  (testing "returning no facts would mean a caller looping over them wrote
            NOTHING, so a failed hand-off would look exactly like one nobody
            attempted — the very defect this namespace removes"
    (doseq [r [(handoff/facts (conversions "pbl-1" "pbl-2")
                              (batch-reply [(result "pbl-1" 200 :posted)]))
               (handoff/facts (conversions "pbl-1") {:status 500 :body {:ok false}})
               (handoff/facts (conversions "pbl-1") {:status 207 :body {:results "nope"}})]]
      (is (= 1 (count (:handoff/facts r))))
      (is (some? (:handoff/outcome (first (:handoff/facts r)))))
      (is (not (contains? handoff/posted-outcomes
                          (:handoff/outcome (first (:handoff/facts r))))))
      (is (some? (:handoff/why (first (:handoff/facts r)))))
      (is (= 1 (reduce + 0 (vals (:handoff/summary r))))))))

(deftest a-batch-with-no-results-sequence-is-unreadable-not-empty
  (testing "an empty run and an unreadable one must not be the same value:
            the first says every entry was answered, the second that none
            of them was"
    (let [r (handoff/facts (conversions "pbl-1") {:status 207 :body {:ok false}})]
      (is (= :unreadable-batch (:handoff/status r)))
      (is (= :unreadable (:handoff/outcome (first (:handoff/facts r)))))
      (is (= 1 (:handoff/submitted (first (:handoff/facts r)))))))
  (testing "whereas submitting nothing and being answered nothing pairs"
    (let [r (handoff/facts [] (batch-reply []))]
      (is (= :paired (:handoff/status r)))
      (is (= [] (:handoff/facts r))))))

(deftest a-result-answering-a-different-document-is-misattributed
  (testing "the ledger echoes the source document it read. If the echo is
            not the one this conversion submitted, the results are out of
            order, and an outcome written against the wrong payable is worse
            than none because it reads as an answer"
    (let [cs (conversions "pbl-1" "pbl-2")
          r (handoff/facts cs (batch-reply [(result "pbl-2" 200 :posted :posting "p")
                                            (result "pbl-1" 200 :posted :posting "p")]))]
      (is (= [:misattributed :misattributed]
             (mapv :handoff/outcome (:handoff/facts r))))
      (is (= "pbl-2" (:handoff/answered-for (first (:handoff/facts r)))))
      (is (= "pbl-1" (:handoff/payable (first (:handoff/facts r))))))))

(deftest a-batch-result-whose-status-and-outcome-disagree-is-unreadable
  (testing "taking :outcome alone would let a 500 arrive labelled :posted;
            taking :status alone would lose the posted/duplicate
            distinction, which a batch result carries nowhere else"
    (doseq [[status outcome] [[500 :posted] [200 :held] [409 :posted]
                              [200 nil] [400 :duplicate] [202 :posted]]]
      (let [r (handoff/facts (conversions "pbl-1")
                             (batch-reply [(result "pbl-1" status outcome)]))
            f (first (:handoff/facts r))]
        (is (= :unreadable (:handoff/outcome f)) (str status " / " outcome))
        (is (= (result "pbl-1" status outcome) (:handoff/response f))))))
  (testing "and where they agree, the outcome is kept"
    (doseq [[status outcome] [[200 :posted] [200 :duplicate] [409 :held]
                              [202 :awaiting-approval] [400 :rejected]]]
      (let [r (handoff/facts (conversions "pbl-1")
                             (batch-reply [(result "pbl-1" status outcome)]))]
        (is (= outcome (:handoff/outcome (first (:handoff/facts r)))))))))

;; ---------------------------------------------------------------------------
;; the vocabulary, the ledger, and the ceiling
;; ---------------------------------------------------------------------------

(deftest no-outcome-is-invented-outside-the-declared-vocabulary
  (let [singles (for [status [200 202 409 400 403 503 500 nil]
                      body [{:ok true :duplicate? false :posting "p"}
                            {:ok true :duplicate? true}
                            {:ok false :error "x"}
                            "not a map"]]
                  (handoff/fact (conv "pbl-1") (reply status body)))
        batched (mapcat :handoff/facts
                        [(handoff/facts (conversions "pbl-1")
                                        (batch-reply [(result "pbl-1" 200 :posted)]))
                         (handoff/facts (conversions "pbl-1")
                                        (batch-reply [(result "pbl-9" 200 :posted)]))
                         (handoff/facts (conversions "pbl-1") (batch-reply []))
                         (handoff/facts (conversions "pbl-1") {:status 500})])
        all (concat singles batched)]
    (testing "the scan actually scored something"
      (is (>= (count all) 30) (str "scored " (count all) " replies")))
    (doseq [f all]
      (is (contains? handoff/outcomes (:handoff/outcome f))
          (str "undeclared outcome " (pr-str (:handoff/outcome f)))))))

(deftest the-fact-is-what-the-ledger-already-takes
  (testing "it goes in beside the graph's own :commit and :hold facts, with
            no new store call and no new schema"
    (let [st (fx/fresh-store)
          before (count (store/ledger st))
          f (handoff/fact (conv "pbl-1") (posted-reply))]
      (store/append-ledger! st f)
      (is (= (inc before) (count (store/ledger st))))
      (is (= :posted (:handoff/outcome (last (store/ledger st)))))))
  (testing "and a whole refused batch appends by the same loop"
    (let [st (fx/fresh-store)
          before (count (store/ledger st))
          r (handoff/facts (conversions "pbl-1" "pbl-2")
                           (batch-reply [(result "pbl-1" 200 :posted)]))]
      (doseq [f (:handoff/facts r)] (store/append-ledger! st f))
      (is (= (inc before) (count (store/ledger st))))
      (is (= :length-mismatch (:handoff/outcome (last (store/ledger st))))))))

(def ^:private call-shaped-tokens
  "The same list `shiharai.shiwake-test` scans for, and for the same reason.
  `\"post\"` is deliberately absent: in an accounts-payable repository it is
  domain vocabulary, so scanning for it reddens on the subject matter."
  ["http" "fetch" "slurp" "spit" "client/" "js/" "send" "exec"])

(deftest this-namespace-reaches-nothing
  (testing "it records what happened; obtaining the reply would be the
            actuation this repository refuses, and it would put the
            hand-off's own transport inside the thing that reports on it"
    (let [path "src/shiharai/handoff.cljc"
          src (slurp path)]
      (testing "SCANNED — nothing found is only meaningful if something was read"
        (is (> (count src) 2000) (str "read " (count src) " chars of " path))
        (is (>= (count call-shaped-tokens) 8)
            (str "checked " (count call-shaped-tokens) " tokens")))
      (doseq [tok call-shaped-tokens]
        (is (not (re-find (re-pattern (str "\\(" tok)) src))
            (str "handoff must not call out: found (" tok)))
      (testing "and the dependency list is pinned exactly, not merely
                allow-listed: an addition has to be made here"
        (let [required (->> (read-string src)
                            (filter list?)
                            (filter #(= :require (first %)))
                            (mapcat rest)
                            (map #(if (sequential? %) (first %) %))
                            set)]
          (is (= '#{clojure.string} required)
              (str "handoff requires " (pr-str required)))))))
  (testing "the namespace exposes exactly two functions and two vocabularies
            — there is no third function that carries anything anywhere"
    (is (= #{'fact 'facts 'outcomes 'posted-outcomes}
           (set (keys (ns-publics 'shiharai.handoff))))))
  (testing "and it names no ledger endpoint, so the transport cannot be
            reconstructed from a constant left lying here"
    (is (not (str/includes? (slurp "src/shiharai/handoff.cljc") "/api/")))))

;; ---------------------------------------------------------------------------
;; Against the real actor, not only a hand-built map
;; ---------------------------------------------------------------------------

(deftest a-real-authorised-payment-round-trips-to-a-recorded-outcome
  (testing "the conversion this scores is the one the real graph and the
            real shiwake produce — asserted by driving the actor rather than
            by a fixture built to match"
    (let [st (fx/fresh-store)
          g (actor/build-graph {:store st})]
      (actor/run-request! g {:supplier-id "s-1" :op :schedule-payment
                             :payable "pbl-1" :payment-id "pay-1"
                             :from-account "FUND-EUR"
                             :payment-date "2026-01-20"}
                          {} "t-schedule")
      (actor/run-request! g {:supplier-id "s-1" :op :release-payment
                             :payable "pbl-1" :payment-id "pay-1"}
                          {} "t-release")
      (actor/approve! g "t-release")
      (let [released (last (store/ledger st))
            converted (assoc (shiwake/entry-request released mapping)
                             :shiwake/record released)
            f (handoff/fact converted (posted-reply false "pst-real"))]
        (is (= :ok (:shiwake/status converted)))
        (is (= :posted (:handoff/outcome f)))
        (is (= "pbl-1" (:handoff/payable f)))
        (is (= "pay-1" (:handoff/payment-id f)))
        (is (= "s-1" (:handoff/supplier-id f)))
        (store/append-ledger! st f)
        (is (= f (last (store/ledger st)))
            "the outcome survives a round trip through the store the actor
             already writes to"))))
  (testing "and a scheduled payment, which shiwake refuses, is not scored as
            an outcome even when a posted reply is handed in beside it"
    (let [st (fx/fresh-store)
          g (actor/build-graph {:store st})]
      (actor/run-request! g {:supplier-id "s-1" :op :schedule-payment
                             :payable "pbl-1" :payment-id "pay-1"
                             :from-account "FUND-EUR"
                             :payment-date "2026-01-20"}
                          {} "t-s")
      (let [scheduled (last (store/ledger st))
            converted (assoc (shiwake/entry-request scheduled mapping)
                             :shiwake/record scheduled)]
        (is (= :not-released (:shiwake/status converted)))
        (is (= :not-submitted
               (:handoff/outcome (handoff/fact converted (posted-reply)))))))))

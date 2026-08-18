(ns shiharai.store-contract-test
  "MemStore ≡ DatomicStore for the shiharai store.

  Every assertion in this file runs against BOTH backends. The set of
  committed payments is the reason it exists: `:duplicate-payment` is a HARD
  hold with no approval route, and what it is enforced against is that set.
  A backend that answered it differently — or lost it — would not make the
  hold fail. It would leave the hold with nothing to be true about, and the
  verdict would come back green.

  Two things are asserted here that a per-backend test cannot ask:

  1. the two backends give the SAME answer, method by method;
  2. the committed-payment set lives in the BACKING STORE and not in the
     store object, so a store opened afresh over the same backing still
     refuses the duplicate. For `MemStore` the backing is a process-local
     atom; for `DatomicStore` it is an append-only EDN event log that is
     round-tripped through `pr-str` here, so what is reopened is bytes and
     not object identity."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [shiharai.actor :as actor]
            [shiharai.fixtures :as f]
            [shiharai.store :as store]))

;; ---------------------------------------------------------------------------
;; The two backends, each with a way to REOPEN it
;;
;; `reopen` is the whole point. It throws the store record away and builds
;; another one over the same backing — which is as close as a single process
;; gets to "the actor restarted", and is exactly the question the AP hold
;; depends on.
;; ---------------------------------------------------------------------------

(defn- mem-backend []
  (let [st (store/mem-store)]
    {:label :mem
     :store st
     ;; A NEW MemStore record over the SAME atom. Honest about its limit:
     ;; the atom is process-local, so this shows the payment set is not in
     ;; the record — it does not show it survives a process.
     :reopen (fn [] (store/->MemStore (:a st)))
     :backing :process-atom}))

(defn- datomic-backend []
  (let [log (atom [])
        st (store/datomic-store (store/journal log))]
    {:label :datomic
     :store st
     ;; A NEW connection replayed from the journal ALONE. The old conn and
     ;; the old record are dropped on the floor, and the events are read
     ;; back out of an EDN string first, so the only thing crossing from
     ;; before to after is serialised data.
     :reopen (fn []
               (let [as-bytes (pr-str @log)]
                 (store/datomic-store (store/journal (atom (edn/read-string as-bytes))))))
     :backing :edn-event-log
     :log log}))

(def ^:private backends [mem-backend datomic-backend])

(defn- each-backend
  "Run `f` against a freshly-seeded instance of each backend."
  [f]
  (doseq [make backends]
    (let [b (make)]
      (f/seed! (:store b))
      (testing (str "backend " (:label b)) (f b)))))

(defn- each-empty-backend [f]
  (doseq [make backends]
    (let [b (make)]
      (testing (str "backend " (:label b)) (f b)))))

(defn- both
  "Run `f` on a seeded instance of each backend and return the two answers
  keyed by backend, so a test can assert they are EQUAL and then assert what
  the shared answer IS. Equality alone would be satisfied by two backends
  that are wrong in the same way."
  [f]
  (into {} (map (fn [make]
                  (let [b (make)]
                    (f/seed! (:store b))
                    [(:label b) (f b)])))
        backends))

(def ^:private schedule-req
  {:supplier-id "s-1" :op :schedule-payment :payable "pbl-1"
   :payment-id "pay-1" :from-account "FUND-EUR" :payment-date "2026-01-20"})

(defn- rules [r] (set (map :rule (get-in r [:state :verdict :violations]))))

;; ---------------------------------------------------------------------------
;; Directories
;; ---------------------------------------------------------------------------

(deftest suppliers-payables-and-accounts-read-back
  (each-backend
   (fn [{:keys [store]}]
     (is (= "Acme GmbH" (:supplier/name (store/supplier store "s-1"))))
     (is (nil? (store/supplier store "nobody")))
     (is (= 120000 (:payable/amount-minor (store/payable store "pbl-1"))))
     (is (nil? (store/payable store "no-such-payable")))
     (is (= "EUR" (:account/currency (store/account-of store "FUND-EUR"))))
     (is (nil? (store/account-of store "no-such-account"))))))

(deftest a-false-flag-survives-the-round-trip-as-false-and-not-as-absent
  (testing ":supplier/sme-subcontractor? is one of the two assertions 取適法
            第三条 needs. Read back as nil it would silently take every
            domestic payable OUT of the statute's scope."
    (each-backend
     (fn [{:keys [store]}]
       (is (false? (:supplier/sme-subcontractor? (store/supplier store "s-1"))))
       (is (true? (:supplier/sme-subcontractor? (store/supplier store "s-2"))))
       (testing "and so does the input-tax-credit claim, on the other side"
         (is (false? (:payable/claims-input-tax-credit? (store/payable store "pbl-1"))))
         (is (true? (:payable/claims-input-tax-credit? (store/payable store "pbl-2")))))))))

(deftest an-absent-payable-amount-reads-back-absent-and-not-as-zero
  (testing "the whole `unknown is not zero` rule is one round-trip away from
            being a rule about a payable worth nothing"
    (each-backend
     (fn [{:keys [store]}]
       (let [p (store/payable store "pbl-unknown")]
         (is (some? p))
         (is (nil? (:payable/amount-minor p)))
         (is (nil? (store/outstanding store "pbl-unknown"))))))))

(deftest payables-are-scoped-to-their-supplier-and-ordered
  (each-backend
   (fn [{:keys [store]}]
     (is (= ["pbl-1" "pbl-unknown"] (mapv :payable/id (store/payables-of store "s-1"))))
     (is (= ["pbl-2"] (mapv :payable/id (store/payables-of store "s-2"))))
     (is (empty? (store/payables-of store "s-4"))))))

(deftest accounts-read-back-in-the-same-order-on-either-backend
  (let [answers (both (fn [{:keys [store]}] (mapv :account/id (store/accounts store))))]
    (is (apply = (vals answers)))
    (is (= ["0001-123-4567890" f/valid-iban f/bad-iban "FUND-EUR" "FUND-JPY"]
           (:datomic answers))
        "sorted by :account/id, which is a decision rather than whatever the
         backend's map iteration happened to produce")))

(deftest re-registering-a-payable-replaces-it-rather-than-forking-it
  (testing "two live payables for one id would make `outstanding` depend on
            which one the backend happened to return first"
    (each-backend
     (fn [{:keys [store]}]
       (is (= 2 (count (store/payables-of store "s-1")))
           "pbl-1 and pbl-unknown, before the re-registration")
       (store/register-payable! store (assoc (store/payable store "pbl-1")
                                             :payable/amount-minor 999))
       (is (= 2 (count (store/payables-of store "s-1")))
           "still two — the re-registration replaced pbl-1, it did not add one")
       (is (= 999 (:payable/amount-minor (store/payable store "pbl-1"))))))))

;; ---------------------------------------------------------------------------
;; The committed-payment set — what `:duplicate-payment` is enforced against
;; ---------------------------------------------------------------------------

(deftest committed-payments-consume-the-balance-identically
  (each-backend
   (fn [{:keys [store]}]
     (is (= 120000 (store/outstanding store "pbl-1")))
     (store/commit-payment! store (assoc (f/payment :payment/amount-minor 50000)
                                         :payment/status :scheduled))
     (is (= 70000 (store/outstanding store "pbl-1")))
     (store/commit-payment! store (assoc (f/payment :payment/id "pay-2"
                                                    :payment/amount-minor 70000)
                                         :payment/status :authorised))
     (is (= 0 (store/outstanding store "pbl-1"))))))

(deftest a-scheduled-payment-consumes-the-balance-on-either-backend
  (testing "if :scheduled did not count, two schedules for the full amount
            would both pass and the duplicate would surface only after a
            human had approved the second"
    (each-backend
     (fn [{:keys [store]}]
       (store/commit-payment! store (assoc (f/payment) :payment/status :scheduled))
       (is (= 0 (store/outstanding store "pbl-1")))))))

(deftest a-non-settling-status-consumes-nothing-on-either-backend
  (each-backend
   (fn [{:keys [store]}]
     (store/commit-payment! store (assoc (f/payment) :payment/status :cancelled))
     (is (= 120000 (store/outstanding store "pbl-1"))))))

(deftest re-committing-a-payment-id-replaces-it-rather-than-doubling-it
  (testing "a release rewrites the scheduled record; two live records would
            make committed-minor count the same money twice and turn a
            correct release into an overpayment"
    (each-backend
     (fn [{:keys [store]}]
       (store/commit-payment! store (assoc (f/payment) :payment/status :scheduled))
       (store/commit-payment! store (assoc (f/payment) :payment/status :authorised))
       (is (= 1 (count (store/payments-for store "pbl-1"))))
       (is (= :authorised (:payment/status (store/payment store "pay-1"))))
       (is (= 0 (store/outstanding store "pbl-1")))))))

(deftest excluding-a-payment-lets-a-release-cite-itself-on-either-backend
  (each-backend
   (fn [{:keys [store]}]
     (store/commit-payment! store (assoc (f/payment) :payment/status :scheduled))
     (is (= 0 (store/outstanding store "pbl-1")))
     (is (= 120000 (store/outstanding store "pbl-1" "pay-1"))))))

;; ---------------------------------------------------------------------------
;; THE ASSERTION THIS FILE EXISTS FOR
;;
;; Reopen the store. The payment must still be committed, and the hard hold
;; must still fire. Under `MemStore` before this change the answer was
;; unmeasured; under `DatomicStore` it is answered from an EDN log.
;; ---------------------------------------------------------------------------

(deftest a-committed-payment-survives-reopening-the-store
  (each-backend
   (fn [{:keys [store reopen]}]
     (store/commit-payment! store (assoc (f/payment) :payment/status :scheduled))
     (let [st2 (reopen)]
       (testing "a store record opened afresh over the same backing"
         (is (not (identical? store st2)))
         (is (= :scheduled (:payment/status (store/payment st2 "pay-1"))))
         (is (= 120000 (:payment/amount-minor (store/payment st2 "pay-1"))))
         (is (= 0 (store/outstanding st2 "pbl-1"))))))))

(deftest a-payment-scheduled-through-the-actor-still-blocks-a-duplicate-after-reopening
  (testing "the reason this store gained a second backend: the committed-payment
            set is what makes :duplicate-payment a hard hold, and a set that
            lives only in the store object makes every prior payment payable
            again the moment the object is rebuilt"
    (each-backend
     (fn [{:keys [store reopen]}]
       (let [g (actor/build-graph {:store store})
             first-run (actor/run-request! g schedule-req {} "t-1")]
         (is (= :commit (get-in first-run [:state :disposition])))
         (is (= :scheduled (:payment/status (store/payment store "pay-1")))))
       ;; --- everything above is dropped; only the backing crosses over ---
       (let [st2 (reopen)
             g2 (actor/build-graph {:store st2})
             second-run (actor/run-request! g2 (assoc schedule-req :payment-id "pay-2")
                                            {} "t-2")]
         (testing "the payable still reads as covered"
           (is (= 0 (store/outstanding st2 "pbl-1"))))
         (testing "so the second payment is HELD, hard, as a duplicate"
           (is (= :hold (get-in second-run [:state :disposition])))
           (is (true? (:hard? (get-in second-run [:state :verdict]))))
           (is (contains? (rules second-run) :duplicate-payment))
           (is (false? (:escalate? (get-in second-run [:state :verdict])))
               "there is no approval route out of a duplicate"))
         (testing "and nothing was written for the duplicate"
           (is (nil? (store/payment st2 "pay-2")))
           (is (= 1 (count (store/payments st2))))))))))

(deftest an-authorised-payment-survives-reopening-too
  (testing "the release path is the one that produces the record a human
            signed for; losing THAT set is losing the audit trail as well as
            the hold"
    (each-backend
     (fn [{:keys [store reopen]}]
       (let [g (actor/build-graph {:store store})]
         (actor/run-request! g schedule-req {} "t-schedule")
         (actor/run-request! g {:supplier-id "s-1" :op :release-payment
                                :payable "pbl-1" :payment-id "pay-1"}
                             {} "t-release")
         (is (= :scheduled (:payment/status (store/payment store "pay-1")))
             "nothing is authorised until a human resumes the thread")
         (actor/approve! g "t-release")
         (is (= :authorised (:payment/status (store/payment store "pay-1")))))
       (let [st2 (reopen)]
         (is (= :authorised (:payment/status (store/payment st2 "pay-1"))))
         (is (= 0 (store/outstanding st2 "pbl-1")))
         (testing "and the double-entry posting the governor redrafted is there too"
           ;; TWO entries, not one, and that is measured rather than assumed:
           ;; `:commit` runs twice on this path (once scheduling, once
           ;; releasing) and each run appends the governor's redraft. The
           ;; postings stream is therefore one entry per COMMIT EVENT, and
           ;; both entries carry the same `:ledger/posting` id. A consumer building a
           ;; trial balance must dedupe by that id rather than sum the
           ;; stream. Recorded in the README's known gaps; asserting it here
           ;; is what keeps it from being a surprise found by whoever sums it
           ;; first.
           (is (= 2 (count (store/postings st2))))
           (is (= ["post-pay-1" "post-pay-1"] (mapv :ledger/posting (store/postings st2))))
           (is (every? :ledger/balanced? (store/postings st2)))))))))

(deftest the-datomic-journal-is-the-only-carrier-and-it-is-plain-edn
  (testing "the reopened store above could have been reading a shared
            connection. It is not: the log round-trips through a string."
    (let [b (datomic-backend)]
      (f/seed! (:store b))
      (store/commit-payment! (:store b) (assoc (f/payment) :payment/status :scheduled))
      (let [events @(:log b)
            as-string (pr-str events)]
        (is (pos? (count events)))
        (is (= events (edn/read-string as-string))
            "the journal survives serialisation, so a host that writes bytes
             is an {:append :read} pair away")
        (testing "and a store built from nothing but those bytes has the payment"
          (let [st2 (store/datomic-store
                     (store/journal (atom (edn/read-string as-string))))]
            (is (= :scheduled (:payment/status (store/payment st2 "pay-1"))))
            (is (= 0 (store/outstanding st2 "pbl-1")))))))))

(deftest a-datomic-store-with-no-journal-keeps-nothing-and-says-so-by-being-empty
  (testing "durability is the journal's, not the record's — a DatomicStore
            opened with no persistence port must not appear to have any"
    (let [st (store/datomic-store)]
      (f/seed! st)
      (store/commit-payment! st (assoc (f/payment) :payment/status :scheduled))
      (is (= :scheduled (:payment/status (store/payment st "pay-1"))))
      (let [fresh (store/datomic-store)]
        (is (nil? (store/payment fresh "pay-1")))
        (is (empty? (store/payments fresh)))))))

;; ---------------------------------------------------------------------------
;; Destination checking reads the same account on either backend
;; ---------------------------------------------------------------------------

(deftest destination-check-is-three-valued-on-either-backend
  (each-backend
   (fn [{:keys [store]}]
     (let [d (store/destination-check (store/account-of store f/valid-iban))]
       (is (= :checked (:destination/coverage d)))
       (is (true? (:destination/valid? d))))
     (let [d (store/destination-check (store/account-of store f/bad-iban))]
       (is (= :checked (:destination/coverage d)))
       (is (false? (:destination/valid? d))))
     (testing "a scheme with no validator here is unchecked, and unchecked is
               not valid — the middle value has to survive the round-trip or
               an unverifiable 全銀 account starts passing"
       (let [d (store/destination-check (store/account-of store "0001-123-4567890"))]
         (is (= :unverified (:destination/coverage d)))
         (is (not (contains? d :destination/valid?)))))
     (is (= :none (:destination/coverage
                   (store/destination-check (store/account-of store "s-4-has-none"))))))))

;; ---------------------------------------------------------------------------
;; Streams
;; ---------------------------------------------------------------------------

(deftest the-ledger-is-append-only-and-ordered-on-either-backend
  (each-backend
   (fn [{:keys [store]}]
     (doseq [n (range 5)] (store/append-ledger! store {:disposition :hold :n n}))
     (is (= [0 1 2 3 4] (mapv :n (store/ledger store)))))))

(deftest postings-append-in-order-on-either-backend
  (each-backend
   (fn [{:keys [store]}]
     (doseq [n (range 3)] (store/commit-posting! store {:ledger/posting (str "post-" n)}))
     (is (= ["post-0" "post-1" "post-2"] (mapv :ledger/posting (store/postings store)))))))

(deftest an-empty-store-answers-empty-not-nil
  (each-empty-backend
   (fn [{:keys [store]}]
     (is (empty? (store/payments store)))
     (is (empty? (store/payments-for store "pbl-1")))
     (is (empty? (store/postings store)))
     (is (empty? (store/ledger store)))
     (is (empty? (store/accounts store)))
     (is (empty? (store/payables-of store "s-1")))
     (is (nil? (store/supplier store "s-1")))
     (is (nil? (store/outstanding store "pbl-1"))))))

;; ---------------------------------------------------------------------------
;; The actor's verdicts do not depend on which store a deployment runs
;; ---------------------------------------------------------------------------

(deftest a-clean-schedule-behaves-identically-on-both-backends
  (let [answers (both (fn [{:keys [store]}]
                        (let [g (actor/build-graph {:store store})
                              r (actor/run-request! g schedule-req {} "t-1")]
                          {:status (:status r)
                           :disposition (get-in r [:state :disposition])
                           :payment-status (:payment/status (store/payment store "pay-1"))
                           :outstanding (store/outstanding store "pbl-1")
                           :postings (count (store/postings store))
                           :ledger (mapv :disposition (store/ledger store))})))]
    (is (= (:mem answers) (:datomic answers)))
    (is (= {:status :done :disposition :commit :payment-status :scheduled
            :outstanding 0 :postings 1 :ledger [:commit]}
           (:datomic answers)))))

(deftest the-duplicate-hold-fires-identically-on-both-backends
  (let [answers (both (fn [{:keys [store]}]
                        (let [g (actor/build-graph {:store store})]
                          (actor/run-request! g schedule-req {} "t-1")
                          (let [r (actor/run-request!
                                   g (assoc schedule-req :payment-id "pay-2") {} "t-2")]
                            {:disposition (get-in r [:state :disposition])
                             :hard? (:hard? (get-in r [:state :verdict]))
                             :rules (rules r)
                             :payments (count (store/payments store))
                             :ledger (mapv :disposition (store/ledger store))}))))]
    (is (= (:mem answers) (:datomic answers)))
    ;; `:invalid-amount` rides along because the advisor, asked to pay a
    ;; payable with 0 outstanding, proposes 0 — which is not a positive
    ;; integer. Both rules are true and both are reported; the duplicate is
    ;; the one with no approval route.
    (is (= {:disposition :hold :hard? true
            :rules #{:duplicate-payment :invalid-amount}
            :payments 1 :ledger [:commit :hold]}
           (:datomic answers)))))

(deftest the-unknown-amount-hold-fires-identically-on-both-backends
  (testing "the three-valued answer has to survive the store, not just the
            governor: a backend that read a missing amount as 0 would turn
            :unknown-payable-amount into :duplicate-payment, which is a
            different sentence about a different fact"
    (let [answers (both (fn [{:keys [store]}]
                          (let [g (actor/build-graph {:store store})
                                r (actor/run-request!
                                   g (assoc schedule-req :payable "pbl-unknown"
                                            :payment-id "pay-x")
                                   {} "t-unknown")]
                            {:disposition (get-in r [:state :disposition])
                             :rules (rules r)
                             :outstanding (get-in r [:state :verdict :outstanding])
                             :payments (count (store/payments store))})))]
      (is (= (:mem answers) (:datomic answers)))
      ;; `:invalid-amount` comes along because the advisor refuses to invent
      ;; a figure and proposes nil. That is the correct pair of sentences:
      ;; the payable's amount is unknown, and nil is not a payable amount.
      (is (= {:disposition :hold
              :rules #{:unknown-payable-amount :invalid-amount}
              :outstanding nil
              :payments 0}
             (:datomic answers))))))

(deftest the-statutory-term-answer-is-the-same-on-both-backends
  (testing "取適法 第三条 needs :payable/mandate and the supplier's
            中小受託事業者 flag; both come out of the store"
    (let [answers (both (fn [{:keys [store]}]
                          (store/register-payable!
                           store (assoc (store/payable store "pbl-2")
                                        :payable/due-date "2026-06-01"))
                          (let [g (actor/build-graph {:store store})
                                r (actor/run-request!
                                   g {:supplier-id "s-2" :op :schedule-payment
                                      :payable "pbl-2" :payment-id "pay-jp"
                                      :from-account "FUND-JPY"
                                      :payment-date "2026-06-01"}
                                   {} "t-jp")]
                            {:disposition (get-in r [:state :disposition])
                             :rules (rules r)
                             :term (get-in r [:state :verdict :payment-terms :law/term])})))]
      (is (= (:mem answers) (:datomic answers)))
      (is (= {:disposition :hold
              :rules #{:statutory-payment-term-exceeded}
              :term :exceeded}
             (:datomic answers))))))

(deftest the-ledger-attributes-both-a-commit-and-a-hold-on-either-backend
  (testing "a refusal nobody can attribute is a refusal nobody can be shown"
    (each-backend
     (fn [{:keys [store]}]
       (let [g (actor/build-graph {:store store})]
         (actor/run-request! g schedule-req {} "t-1")
         (actor/run-request! g (assoc schedule-req :payment-id "pay-2") {} "t-2"))
       (let [entries (store/ledger store)]
         (is (= [:commit :hold] (mapv :disposition entries)))
         (is (= ["s-1" "s-1"] (mapv :supplier-id entries)))
         (is (= ["pay-1" "pay-2"] (mapv :payment-id entries))))))))

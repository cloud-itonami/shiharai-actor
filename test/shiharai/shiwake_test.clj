(ns shiharai.shiwake-test
  "An authorised payment becoming a journal entry — and every way that
  hand-off can quietly lose one.

  The failure this file exists for has no error message: a payment is
  authorised, no entry is ever drafted for it, and both actors report a
  clean run. Nothing goes red. The books are simply short by one
  disbursement."
  (:require [clojure.test :refer [deftest is testing]]
            [shiharai.actor :as actor]
            [shiharai.fixtures :as fx]
            [shiharai.shiwake :as shiwake]
            [shiharai.store :as store]))

(def ^:private mapping
  {:suppliers {"s-1" "買掛金"
               "s-2" "未払金"}
   :accounts  {"FUND-EUR" "普通預金（EUR）"
               "FUND-JPY" "普通預金（JPY）"}})

(defn- committed
  "A `store/ledger` fact — `{:disposition d :record {…}}`, the shape the
  graph's `:commit` node appends. Hand-built so a refusal can be provoked
  that the graph would not produce; the two tests at the bottom drive the
  real graph, so a drift between this shape and the actor's would show."
  [& {:keys [disposition supplier-id status payable amount currency from-account
             payment-id]
      :or {disposition :commit supplier-id "s-1" status :authorised
           payable "pbl-1" amount 120000 currency "EUR"
           from-account "FUND-EUR" payment-id "pay-1"}}]
  {:disposition disposition
   :record {:supplier-id supplier-id
            :op :release-payment
            :payable payable
            :payment {:payment/id payment-id
                      :payment/payable payable
                      :payment/amount-minor amount
                      :payment/currency currency
                      :payment/from-account from-account
                      :payment/status status
                      :payment/supplier supplier-id}}})

;; ---------------------------------------------------------------------------
;; The entry itself
;; ---------------------------------------------------------------------------

(deftest an-authorised-payment-becomes-a-balanced-entry
  (let [r (shiwake/entry-request (committed) mapping)
        req (:shiwake/request r)]
    (is (= :ok (:shiwake/status r)))
    (is (= :draft-entry (:op req)))
    (is (= "pbl-1" (:source-doc req)))
    (is (= [["買掛金" :dr 120000] ["普通預金（EUR）" :cr 120000]]
           (mapv (juxt :account :side :amount) (:lines req))))
    (is (= ["EUR" "EUR"] (mapv :currency (:lines req)))
        "the ledger groups by currency before comparing, so a line without
         one is a line whose unit its arithmetic has to guess")))

(deftest the-payment-discharges-a-liability-it-does-not-recognise-a-cost
  (testing "Dr the liability, Cr the cash — the payable was recognised when
            the invoice was booked, so debiting an expense here would book
            the same cost twice"
    (let [lines (get-in (shiwake/entry-request (committed) mapping)
                        [:shiwake/request :lines])
          by-side (into {} (map (juxt :side :account) lines))]
      (is (= "買掛金" (:dr by-side)))
      (is (= "普通預金（EUR）" (:cr by-side)))))
  (testing "the credit is the funding account the money left, not the
            supplier's destination account — this repo has no entry for
            where the money arrived, because that is the supplier's books"
    (let [r (shiwake/entry-request (committed :from-account "FUND-JPY"
                                              :currency "JPY"
                                              :supplier-id "s-2")
                                   mapping)]
      (is (= "普通預金（JPY）"
             (->> (get-in r [:shiwake/request :lines])
                  (filter #(= :cr (:side %)))
                  first :account))))))

(deftest a-partial-payment-posts-what-was-paid
  (testing "the amount comes from the payment, and this namespace never sees
            the payable's face value — so a partial settlement cannot post
            as a full one. The residual 買掛金 balance needs no entry: it
            did not change"
    (let [req (:shiwake/request (shiwake/entry-request (committed :amount 50000)
                                                       mapping))]
      (is (= [50000 50000] (mapv :amount (:lines req))))
      (is (= "pbl-1" (:source-doc req))
          "still the same invoice — a partial payment settles part of one
           document, not a different one"))))

;; ---------------------------------------------------------------------------
;; Every refusal is a named value, never nil
;; ---------------------------------------------------------------------------

(deftest a-payment-that-was-not-authorised-yields-a-named-refusal
  (testing "not nil — a caller treating `no entry` as `nothing to do` would
            skip exactly the payments somebody has to look at"
    (doseq [d [:hold :request-approval]]
      (let [r (shiwake/entry-request (committed :disposition d) mapping)]
        (is (= :not-authorised (:shiwake/status r)))
        (is (= d (:shiwake/disposition r)))
        (is (nil? (:shiwake/request r)))))))

(deftest a-scheduled-payment-is-not-a-payment
  (testing "posting a scheduled payment would credit cash that has not left
            the account. It is its own status because the action it calls
            for — approve it, or decide not to — is not the action a hold
            calls for"
    (let [r (shiwake/entry-request (committed :status :scheduled) mapping)]
      (is (= :not-released (:shiwake/status r)))
      (is (= :scheduled (:shiwake/payment-status r)))
      (is (nil? (:shiwake/request r)))))
  (testing "and it is NOT the same answer as a hold"
    (is (not= (:shiwake/status (shiwake/entry-request (committed :status :scheduled)
                                                      mapping))
              (:shiwake/status (shiwake/entry-request (committed :disposition :hold)
                                                      mapping))))))

(deftest a-status-this-repo-cannot-read-is-not-called-awaiting-approval
  (testing "`:not-released` means a human still has to approve it. Saying
            that about a record nobody understood is a wrong instruction,
            not a cautious one"
    (doseq [s [:paid :reversed nil "authorised"]]
      (let [r (shiwake/entry-request (committed :status s) mapping)]
        (is (= :unknown-payment-status (:shiwake/status r))
            (str "status " (pr-str s)))
        (is (= s (:shiwake/payment-status r)))
        (is (nil? (:shiwake/request r)))))))

(deftest an-unmapped-side-is-refused-not-suspensed
  (testing "falling back to a suspense account would post the entry and make
            the missing decision invisible"
    (let [r (shiwake/entry-request (committed :supplier-id "s-9") mapping)]
      (is (= :no-mapping (:shiwake/status r)))
      (is (= #{:supplier} (:shiwake/missing r)))
      (is (= "s-9" (:shiwake/supplier r)))
      (is (nil? (:shiwake/request r)))))
  (testing "an unmapped funding account is the same refusal from the other side"
    (let [r (shiwake/entry-request (committed :from-account "FUND-CHF") mapping)]
      (is (= :no-mapping (:shiwake/status r)))
      (is (= #{:funding-account} (:shiwake/missing r)))
      (is (= "FUND-CHF" (:shiwake/funding-account r)))))
  (testing "a half-filled mapping is no mapping — an entry missing one line
            balances by having lost it"
    (is (= :no-mapping (:shiwake/status
                        (shiwake/entry-request (committed)
                                               {:suppliers {"s-1" "買掛金"}}))))
    (is (= :no-mapping (:shiwake/status
                        (shiwake/entry-request (committed)
                                               {:accounts {"FUND-EUR" "普通預金"}}))))
    (is (= #{:supplier :funding-account}
           (:shiwake/missing (shiwake/entry-request (committed) {})))))
  (testing "a blank account name is not a mapping either"
    (is (= :no-mapping (:shiwake/status
                        (shiwake/entry-request
                         (committed)
                         {:suppliers {"s-1" "  "} :accounts {"FUND-EUR" "普通預金"}}))))))

(deftest an-unusable-payment-is-refused
  (testing "a positive integer of minor units — the ledger's own body parser
            accepts any `number?`, so a fractional minor unit would cross it"
    (doseq [a [0 -5 nil "120000" 1200.5]]
      (is (= :unusable-payment (:shiwake/status
                                (shiwake/entry-request (committed :amount a) mapping)))
          (str "amount " (pr-str a)))))
  (testing "an amount with no unit is not an amount"
    (doseq [c [nil ""]]
      (is (= :unusable-payment (:shiwake/status
                                (shiwake/entry-request (committed :currency c) mapping))))))
  (testing "and an entry citing no document is a transaction nobody can trace"
    (doseq [p [nil ""]]
      (is (= :unusable-payment (:shiwake/status
                                (shiwake/entry-request (committed :payable p) mapping)))))))

;; ---------------------------------------------------------------------------
;; The source document, and whose registry decides
;; ---------------------------------------------------------------------------

(deftest the-payable-is-carried-as-the-source-document
  (testing "4311 holds an entry citing a document its OWN registry has no
            record of (`:unknown-source-doc`), so a payment authorised here
            against a payable that was never registered there is refused
            there rather than posted. The ledger's registry is the one that
            counts — a payable this actor knows is not thereby a 原始証憑
            that actor knows"
    (is (= "pbl-2" (get-in (shiwake/entry-request
                            (committed :payable "pbl-2" :supplier-id "s-2"
                                       :currency "JPY" :from-account "FUND-JPY")
                            mapping)
                           [:shiwake/request :source-doc])))))

(deftest the-request-carries-only-what-the-ledger-reads
  (testing "that endpoint reads :source-doc and :lines out of a body and
            drops everything else, so the payment id is reported on the
            wrapper rather than smuggled into a field that would vanish"
    (let [r (shiwake/entry-request (committed :payment-id "pay-77") mapping)]
      (is (= #{:op :source-doc :lines} (set (keys (:shiwake/request r)))))
      (is (= "pay-77" (:shiwake/payment-id r))))))

(deftest every-line-carries-what-the-ledgers-parser-requires
  (testing "`parse-entry-body` rejects a body whose lines lack a recognised
            :side, a string :account or a numeric :amount — a request this
            namespace built must not be one it rejects"
    (let [lines (get-in (shiwake/entry-request (committed) mapping)
                        [:shiwake/request :lines])]
      (is (seq lines))
      (is (every? #(and (#{:dr :cr} (:side %))
                        (string? (:account %))
                        (number? (:amount %)))
                  lines))))
  (testing "and it rejects a body that names a client, which this one never does"
    (is (not (contains? (:shiwake/request (shiwake/entry-request (committed) mapping))
                        :client-id)))))

;; ---------------------------------------------------------------------------
;; The batch
;; ---------------------------------------------------------------------------

(deftest a-batch-keeps-what-it-could-not-convert
  (testing "filtering would report a clean run and leave the unconvertible
            payments invisible — which on this side of the books is a
            disbursement with no entry against it"
    (let [b (shiwake/entry-requests
             [(committed)
              (committed :disposition :hold)
              (committed :status :scheduled)
              (committed :supplier-id "s-9")]
             mapping)]
      (is (= 1 (count (:ok b))))
      (is (= 3 (count (:skipped b))))
      (is (= #{:not-authorised :not-released :no-mapping}
             (set (map :shiwake/status (:skipped b)))))
      (is (every? :shiwake/record (:skipped b))
          "each refusal carries the record it refused, or it cannot be acted on")
      (is (= 4 (+ (count (:ok b)) (count (:skipped b))))
          "nothing was dropped between the two halves")))
  (testing "an empty batch reports empty, not clean"
    (is (= {:ok [] :skipped []} (shiwake/entry-requests [] mapping)))))

;; ---------------------------------------------------------------------------
;; It reaches nothing
;; ---------------------------------------------------------------------------

(def ^:private call-shaped-tokens
  "Call shapes that need no dependency. `\"post\"` is deliberately NOT here:
  in an accounts-payable repository it is domain vocabulary — `postable-
  statuses`, `commit-posting!`, `redraft-posting` — so scanning for it
  reddens on the subject matter rather than on a call, and a check that
  cries wolf gets deleted. `keihi` could afford the token; this repo cannot."
  ["http" "fetch" "slurp" "spit" "client/" "js/" "send" "exec"])

(deftest this-namespace-reaches-nothing
  (testing "it produces a value; reaching across to write into another
            actor's ledger would be the actuation this repo refuses, and it
            would also make the accounts this actor's business"
    (let [path "src/shiharai/shiwake.cljc"
          src (slurp path)]
      (testing "SCANNED — nothing found is only meaningful if something was read"
        (is (> (count src) 2000) (str "read " (count src) " chars of " path))
        (is (>= (count call-shaped-tokens) 8)
            (str "checked " (count call-shaped-tokens) " tokens")))
      (doseq [tok call-shaped-tokens]
        (is (not (re-find (re-pattern (str "\\(" tok)) src))
            (str "shiwake must not call out: found (" tok)))
      (testing "a call needs either a host escape — the scan above, and
                `shiharai.ceiling-test` over the whole of src/ — or a
                dependency. So the dependency list is pinned exactly, not
                merely allow-listed: an addition has to be made here"
        (let [required (->> (read-string src)
                            (filter list?)
                            (filter #(= :require (first %)))
                            (mapcat rest)
                            (map #(if (sequential? %) (first %) %))
                            set)]
          (is (= '#{clojure.string} required)
              (str "shiwake requires " (pr-str required)))))))
  (testing "the namespace exposes exactly two functions — there is no third
            that carries the request anywhere"
    (is (= #{'entry-request 'entry-requests}
           (set (keys (ns-publics 'shiharai.shiwake)))))))

;; ---------------------------------------------------------------------------
;; Against the real actor, not only a hand-built map
;; ---------------------------------------------------------------------------

(deftest a-real-authorised-payment-converts-and-a-scheduled-one-does-not
  (testing "the shape this namespace consumes is the shape the actor and the
            ledger actually produce — asserted by driving the graph rather
            than by a fixture built to match"
    (let [st (fx/fresh-store)
          g (actor/build-graph {:store st})
          _ (actor/run-request! g {:supplier-id "s-1" :op :schedule-payment
                                   :payable "pbl-1" :payment-id "pay-1"
                                   :from-account "FUND-EUR"
                                   :payment-date "2026-01-20"}
                                {} "t-schedule")
          scheduled (last (store/ledger st))]
      (is (= :scheduled (get-in scheduled [:record :payment :payment/status])))
      (is (= :not-released (:shiwake/status (shiwake/entry-request scheduled mapping)))
          "a scheduled payment on the real ledger yields no entry")

      (actor/run-request! g {:supplier-id "s-1" :op :release-payment
                             :payable "pbl-1" :payment-id "pay-1"}
                          {} "t-release")
      (actor/approve! g "t-release")
      (let [released (last (store/ledger st))
            r (shiwake/entry-request released mapping)]
        (is (= :authorised (get-in released [:record :payment :payment/status])))
        (is (= :ok (:shiwake/status r)))
        (is (= [["買掛金" :dr 120000] ["普通預金（EUR）" :cr 120000]]
               (mapv (juxt :account :side :amount)
                     (get-in r [:shiwake/request :lines]))))
        (is (= "pbl-1" (get-in r [:shiwake/request :source-doc])))))))

(deftest the-graphs-final-state-is-a-route-and-the-ledger-fact-is-an-outcome
  (testing "a released payment routes through :request-approval, so
            `run-request!` reports that disposition for a run that
            committed. Folding the STATE would call an authorised payment
            :not-authorised — which is why the input is the ledger fact, and
            why this is asserted rather than assumed"
    (let [st (fx/fresh-store)
          g (actor/build-graph {:store st})]
      (actor/run-request! g {:supplier-id "s-1" :op :schedule-payment
                             :payable "pbl-1" :payment-id "pay-1"
                             :from-account "FUND-EUR"
                             :payment-date "2026-01-20"}
                          {} "t-s")
      (actor/run-request! g {:supplier-id "s-1" :op :release-payment
                             :payable "pbl-1" :payment-id "pay-1"}
                          {} "t-r")
      (let [state (:state (actor/approve! g "t-r"))
            fact (last (store/ledger st))]
        (is (= :request-approval (:disposition state)))
        (is (= :commit (:disposition fact)))
        (is (= (:record state) (:record fact))
            "same record — only the disposition means something different")
        (is (= :not-authorised (:shiwake/status (shiwake/entry-request state mapping))))
        (is (= :ok (:shiwake/status (shiwake/entry-request fact mapping))))))))

(deftest a-held-payment-on-the-real-ledger-is-refused-not-dropped
  (testing "a hold fact carries no :record at all, so the refusal is all
            there is to report — and it is reported"
    (let [st (fx/fresh-store)
          g (actor/build-graph {:store st})]
      (actor/run-request! g {:supplier-id "s-1" :op :schedule-payment
                             :payable "pbl-unknown" :payment-id "pay-x"
                             :from-account "FUND-EUR"
                             :payment-date "2026-01-20"}
                          {} "t-hold")
      (let [fact (last (store/ledger st))
            b (shiwake/entry-requests [fact] mapping)]
        (is (= :hold (:disposition fact)))
        (is (empty? (:ok b)))
        (is (= [:not-authorised] (mapv :shiwake/status (:skipped b))))
        (is (= fact (:shiwake/record (first (:skipped b)))))))))

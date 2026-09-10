(ns shiharai.advisor
  "ShiharaiAdvisor — proposes an accounts-payable operation: schedule a
  payment against a supplier invoice, or release one a human is about to
  approve. Swappable: `mock-advisor` (deterministic, the default) or
  `llm-advisor`.

  **The advisor may only propose.** Every proposal it emits carries
  `:effect :propose`, and `shiharai.governor` independently recomputes the
  outstanding balance and redrafts the double-entry posting from the store —
  so a figure this node invents is HELD, not corrected. That asymmetry is the
  whole design: a governor that repaired the advisor's arithmetic would make
  a wrong proposal indistinguishable from a right one in the ledger.

  A proposal is a map:

    {:op       :schedule-payment | :release-payment
     :effect   :propose
     :payable  \"pbl-1\"
     :payment  {:payment/id \"pay-1\" :payment/payable \"pbl-1\"
                :payment/amount-minor 120000 :payment/currency \"JPY\"
                :payment/from-account \"FUND-JPY\" :payment/date \"2026-03-01\"}
     :confidence 0.0-1.0
     :rationale str}

  `:posting` is optional. When present the governor compares it with its own
  redraft and holds a mismatch; when absent the governor's redraft is used.
  An advisor that omits it is not trusted more, it is merely quieter."
  (:require [shiharai.store :as store]
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])))

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- propose-schedule
  [store {:keys [payable payment-id from-account payment-date amount-minor]}]
  (let [pay (store/payable store payable)
        out (store/outstanding store payable)
        ;; An unknown outstanding is passed through as nil, not as the
        ;; payable's face value and not as 0. The governor holds on it; an
        ;; advisor that substituted a number here would be inventing the one
        ;; fact the hold exists to protect.
        amount (or amount-minor out)]
    {:op :schedule-payment
     :effect :propose
     :payable payable
     :payment {:payment/id payment-id
               :payment/payable payable
               :payment/amount-minor amount
               :payment/currency (:payable/currency pay)
               :payment/from-account from-account
               :payment/date payment-date}
     :confidence (if (nil? out) 0.0 0.9)
     :rationale (if (nil? out)
                  "payable の残額が未知のため、金額を提案できない"
                  (str "payable " payable " の残額 " out " に対する支払を提案"))}))

(defn- propose-release
  [store {:keys [payable payment-id]}]
  (let [existing (store/payment store payment-id)]
    {:op :release-payment
     :effect :propose
     :payable (or payable (:payment/payable existing))
     ;; Echoed verbatim. Changing anything here between scheduling and
     ;; release is `:release-alters-scheduled-payment`, because the human is
     ;; approving what was scheduled.
     :payment (select-keys existing [:payment/id :payment/payable
                                     :payment/amount-minor :payment/currency
                                     :payment/from-account :payment/date])
     :confidence (if existing 0.9 0.0)
     :rationale (if existing
                  (str "scheduled payment " payment-id " の解放を提案（人の承認が要る）")
                  (str "payment " payment-id " は scheduled ではない"))}))

(defn- infer [store {:keys [op] :as request}]
  (case op
    :schedule-payment (propose-schedule store request)
    :release-payment (propose-release store request)
    {:op op :effect :propose :confidence 0.0
     :rationale (str "未知の op: " (pr-str op))}))

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are an accounts-payable advisor. Given a request, propose which
   registered payable to pay, from which registered funding account, and on
   what date. Never propose an amount larger than the outstanding balance,
   never propose a second payment against a payable that is already covered,
   and never state an amount when the payable's own amount is unknown — say
   nil. You cannot release funds; a release is a proposal a human approves.
   Every figure you give is recomputed independently, so a number you guess
   will be held, not corrected.")

(defn- parse-proposal [content]
  (try
    (let [p (edn/read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        ;; A parse failure yields confidence 0.0, never a fabricated one.
        ;; `governor.core` treats an ABSENT :confidence as 0.0 for the same
        ;; reason; this makes it explicit rather than relying on it.
        {:op :unknown :effect :propose :confidence 0.0
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  "Wraps a `langchain.model/ChatModel`-shaped generate fn; decoupled from any
  concrete model. The model sees the payable's id, currency and outstanding
  balance — never the supplier's bank account, and never another supplier's
  payables."
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ store request]
      (let [payable (:payable request)
            pay (store/payable store payable)
            msgs [{:role :system :content system-prompt}
                  {:role :user
                   :content (str "payable: " (pr-str payable)
                                 "\ncurrency: " (pr-str (:payable/currency pay))
                                 "\noutstanding-minor: "
                                 (pr-str (store/outstanding store payable))
                                 "\nrequest: " (pr-str (dissoc request :supplier-id)))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (assoc (parse-proposal (:content resp)) :payable payable)))))

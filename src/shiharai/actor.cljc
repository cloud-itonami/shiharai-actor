(ns shiharai.actor
  "ShiharaiActor — the 支払 accounts-payable actor as a
  `langgraph.graph/state-graph` (ADR-2607011000 / CLAUDE.md Actors section).
  One graph run = one payment request.

  ```text
  :intake -> :advise -> :govern -> :decide -+-> :commit            (:ok? true)
                                            +-> :request-approval  (:escalate? true, interrupt-before)
                                            +-> :hold              (:hard? true)
  ```

  The unconditional invariant: the ShiharaiAdvisor can never commit what the
  ShiharaiGovernor refuses, and **`:release-payment` always reaches
  `:request-approval` first**, so no payment is marked authorised without a
  human resuming the thread.

  ## What `:commit` does, and what it cannot do

  `:commit` writes records. `:schedule-payment` writes a payment with
  `:payment/status :scheduled`; a resumed `:release-payment` rewrites it as
  `:authorised` and stores the double-entry posting the governor redrafted.

  That is the end of this actor's reach. There is no bank client here, no
  credential, no network call, and `kotoba.banking.api`'s payment-initiation
  request builder is deliberately not used. An `:authorised` record is a
  statement that a human approved a disbursement — it is not the
  disbursement. Whatever performs one is a separate system with its own
  authority, and adding it to this repository would remove the ceiling this
  actor exists to hold."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [shiharai.advisor :as advisor]
            [shiharai.governor :as governor]
            [shiharai.store :as store]))

(defn route
  "`:hold` | `:request-approval` | `:commit` for a verdict.

  Same precedence as `governor.core/disposition`, and `:hard?` is tested
  FIRST for the same reason: a verdict that says both `:hard?` and
  `:escalate?` is malformed, and the one drifted governor in the fleet
  produced exactly that. Testing `:hard?` first is what kept that drift from
  mis-routing. Named and public so the order is a thing a test can observe —
  through the graph alone it is unobservable, because `gov/verdict` never
  emits both flags, and an unobservable invariant is an unmeasured one."
  [{:keys [hard? escalate?]}]
  (cond hard? :hold
        escalate? :request-approval
        :else :commit))

(defn build-graph
  "Build a compiled ShiharaiActor graph. `store` implements
  `shiharai.store/Store`. `advisor` implements `shiharai.advisor/Advisor`
  (defaults to `mock-advisor`). `checkpointer` defaults to an in-memory one."
  [{:keys [store advisor checkpointer]
    :or {advisor (advisor/mock-advisor)
         checkpointer (cp/mem-checkpointer)}}]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}
         :record      {:default nil}
         :audit       {:reducer into :default []}}})
      (g/add-node :intake (fn [s] s))
      (g/add-node :advise
                  (fn [{:keys [request]}]
                    (let [p (advisor/-advise advisor store request)]
                      {:proposal p
                       :audit [{:node :advise :request request :proposal p}]})))
      (g/add-node :govern
                  (fn [{:keys [request context proposal]}]
                    (let [v (governor/check request context proposal store)]
                      {:verdict v
                       :audit [{:node :govern :verdict v}]})))
      (g/add-node :decide (fn [{:keys [verdict]}] {:disposition (route verdict)}))
      (g/add-node :request-approval (fn [s] s))
      (g/add-node :commit
                  (fn [{:keys [request proposal verdict]}]
                    (let [pmt (:payment proposal)
                          status (case (:op proposal)
                                   :release-payment :authorised
                                   :scheduled)
                          committed (assoc pmt
                                           :payment/status status
                                           :payment/supplier (:supplier-id request))
                          record {:supplier-id (:supplier-id request)
                                  :op (:op proposal)
                                  :payable (:payable proposal)
                                  :payment committed}]
                      (store/commit-payment! store committed)
                      ;; The posting stored is the governor's redraft, not
                      ;; the advisor's — the ledger records what was checked.
                      (when-let [p (:posting verdict)]
                        (store/commit-posting! store p))
                      (store/append-ledger! store {:disposition :commit
                                                   :supplier-id (:supplier-id request)
                                                   :payment-id (:payment/id pmt)
                                                   :record record
                                                   :verdict verdict})
                      {:record record
                       :audit [{:node :commit :record record}]})))
      (g/add-node :hold
                  ;; A hold is attributed to the same two identifiers a
                  ;; commit is. A refusal nobody can attribute is a refusal
                  ;; nobody can be shown: without these, the only queryable
                  ;; ledger is the one of things that went through, and a
                  ;; reader asking "what happened to pay-2" gets silence
                  ;; whether it was held or never proposed.
                  (fn [{:keys [request proposal verdict]}]
                    (store/append-ledger! store
                                          {:disposition :hold
                                           :supplier-id (:supplier-id request)
                                           :payment-id (get-in proposal [:payment :payment/id])
                                           :verdict verdict})
                    {:audit [{:node :hold :verdict verdict}]}))
      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)
      (g/add-conditional-edges
       :decide
       (fn [{:keys [disposition]}]
         (case disposition
           :commit :commit
           :request-approval :request-approval
           :hold)))
      (g/add-edge :request-approval :commit)
      (g/set-finish-point :commit)
      (g/set-finish-point :hold)
      (g/compile-graph {:checkpointer checkpointer
                        :interrupt-before #{:request-approval}})))

(defn run-request!
  "Run one payment request to completion or interrupt. `thread-id` scopes
  checkpointing for resume after human approval."
  [graph request context thread-id]
  (g/run* graph {:request request :context context} {:thread-id thread-id}))

(defn approve!
  "Human-in-the-loop resume: the interrupted `:request-approval` node
  advances to `:commit` (approval is the act of resuming the thread).

  This is the only path by which a payment becomes `:authorised`, and it is
  reachable only from a process a human drives."
  [graph thread-id]
  (g/run* graph nil {:thread-id thread-id :resume? true}))

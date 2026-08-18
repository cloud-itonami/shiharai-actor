# CLAUDE.md — cloud-itonami/shiharai-actor 支払

Not to be confused with `cloud-itonami/shiharai`, which is
`com-etzhayyim-app-shiharai`: a TypeScript/Svelte appview of payment recipes.
That repo is a surface; this one is the governed decision layer. Do not merge
them, and do not add a UI here.

Accounts-payable actor. itonami pattern: advisor ⊣ independent governor ⊣
append-only ledger. Verdict assembly is `kotoba-lang/governor`, IBAN and
double-entry are `kotoba-lang/banking`, tax records are `kotoba-lang/taxlaw`.
This repo is the governed shell.

## Rules that are not negotiable

**Nothing here moves money, and nothing here may acquire the ability to.**
`:release-payment` writes `:payment/status :authorised` and always escalates
to a human. Do not add a bank client, a credential, an outbound HTTP call, or
`kotoba.banking.api`'s payment-initiation builder. If a disbursement needs to
happen, it happens in a different system with its own authority — moving it
here removes the only ceiling this actor has.

**An unknown figure is not zero and not unlimited.** `store/outstanding`
returns nil for a payable whose amount nobody recorded, and
`:unknown-payable-amount` holds. Do not default it, do not take the
proposal's own number, and do not add an approval route — no approval makes
an unknown figure known.

**A three-valued answer stays three-valued.** `kotoba.taxlaw` returns
`:none` / `:not-declared` / `:checked`; `shiharai.law` returns
`:not-declared` / `:undeterminable` / `:checked`. Do not collapse any of them
to a boolean at the call site. Where the governor deliberately does not hold,
the verdict must still SAY what was not checked — that is what `:tax`,
`:preservation`, `:destination`, `:payment-terms` and `:escalations` are for.

**Only enforce a statute whose text is in this repo.** 取適法 第三条 is in
`src/shiharai/law.cljc` with its e-Gov revision id, both paragraphs verbatim,
and the API call that retrieved it. Do not add a rule grounded in a URL
nobody opened, and do not widen an article's own scope: 第三条 reaches
製造委託等 between a 委託事業者 and a 中小受託事業者, not invoices in general.

**Do not resolve the 起算 boundary.** Exactly 60 days is `:boundary` and goes
to a human. Picking a counting convention here would move a hold by a day in
a direction nobody authorised. Only `> 60` holds.

## Deliberately *not* a hold

An account under a scheme this repo cannot validate (全銀, sort code) —
that is `:unverified`, and it escalates. It is not valid and it is not
invalid. Do not add a mod-97 check for a domestic Japanese account number;
that would refuse every domestic payment for the wrong reason.

A payment scheduled after the payable's due date — no article read here says
that is unlawful, so it escalates and its `:detail` says so.

## Duplicate payment is the failure this actor exists for

`:scheduled` consumes the balance, not only `:authorised`. If it did not, two
schedules for the full amount would both pass and the duplicate would only
surface after a human had approved the second one. `:duplicate-payment` has
no approval route.

## Store and edge

`MemStore` ≡ `DatomicStore` — same protocol, same contract test; **write both
sides of any store change**, and add its assertion to
`test/shiharai/store_contract_test.clj` in the same commit. The contract test
is not decoration here: the committed-payment set is what `:duplicate-payment`
is enforced against, so a backend that answered it differently would not make
the hold fail, it would leave the hold with nothing to be true about.

**Do not call the journalled store durable.** `datomic-store` takes
langchain.db's `{:append :read}` persistence port; the host on the other side
of it is what is or is not durable, and this repo cannot see that host. The
edge reports `:persistence :delegated`, never `:durable`. A journal held in
memory by a worker about to be evicted is still a journal.

**A multi-value read is sorted.** `accounts` / `payables-of` / `payments` /
`payments-for` sort by id on both backends. A read whose order is whatever the
backend's map iteration produced is a read two deployments answer differently.

The surface is four functions and **no fifth**: register a payable, propose a
payment, read a verdict, read the ledger. Releasing, approving and resuming
have no HTTP representation and must not acquire one. Concretely, do not
change these:

- the op in `propose-payment-core!` is a **constant**, not a field. Read it
  out of the body and a request can reach the release path;
- the graph is built **per request**, so no interrupted thread outlives the
  response. Hoist the checkpointer and approval becomes an HTTP call;
- an escalation is **202**, which is neither of its neighbours;
- `caller-did` arrives already verified. Do not add a verifier — that means a
  key — and do not add a Cloudflare/`js/Response` entry point here.

An absent allow-list serves **503**. An unset `SHIHARAI_STORE` serves **503**
too — refusing beats returning `:no-supplier` and blaming the caller for a
storeless deployment.

The supplier comes from the DID, always. A body that names one is a **400**,
never a silent substitution: ignoring it is how a caller comes to believe they
registered an invoice against a supplier they never touched.

An existing payable id is **refused, not overwritten**. Upsert is right for an
operator correcting a record and wrong for an endpoint — it moves the ceiling
`:overpayment` and `:duplicate-payment` are measured against, under payments
that may already be scheduled.

## The ceiling is a test, not a sentence

`test/shiharai/ceiling_test.clj` reads every `ns` form under `src/` and
compares its requires against an allow-list, then scans for host escapes that
need no dependency. **Adding a dependency means adding it to
`permitted-requires` with the sentence saying why it cannot reach a network.**
`kotoba.banking.api` is not on that list and must not be added.

Both halves assert a floor on how many files they read. Do not remove those
assertions: a scanner pointed at an empty directory reports the same clean
result as a clean repository.

## deps.edn

**Zero `:local/root`, ever.** Git coordinates only, transitively. Verify by
**reading the file as EDN**, not by grepping it — `langchain-store`'s own
`deps.edn` discusses a `:local/root` it no longer has, so `grep` answers yes
and the reader answers no. Then run the suite from a fresh clone with no
sibling checkouts, which is both what a fork gets and what a murakumo fleet
gate ships.

## Test — including that the tests can fail

    clojure -M:test && clojure -M:lint && nbb tools/mutate.cljs

`tools/mutate.cljs` must report **0 survived**. A survivor is a finding about
the suite, not about the mutation: fix the code or the test, do not delete
the mutation. Two survivors on the first run were real defects and both were
repaired (see README).

When adding an invariant, add its mutation to `tools/mutations.edn` in the
same change. The harness refuses a `:find` that does not occur exactly once —
a mutation landing in a comment produces a red suite that proves nothing.

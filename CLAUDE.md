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

## deps.edn

**Zero `:local/root`, ever.** Git coordinates only. Verify by running the
suite from a directory with no sibling checkouts — that is both what a fork
gets and what a murakumo fleet gate ships.

## Test — including that the tests can fail

    clojure -M:test && clojure -M:lint && nbb tools/mutate.cljs

`tools/mutate.cljs` must report **0 survived**. A survivor is a finding about
the suite, not about the mutation: fix the code or the test, do not delete
the mutation. Two survivors on the first run were real defects and both were
repaired (see README).

When adding an invariant, add its mutation to `tools/mutations.edn` in the
same change. The harness refuses a `:find` that does not occur exactly once —
a mutation landing in a comment produces a red suite that proves nothing.

# shiharai-actor 支払

**An accounts-payable actor that cannot pay anything.**

> **Why the `-actor` suffix.** `cloud-itonami/shiharai` already exists and is
> something else: `com-etzhayyim-app-shiharai`, a TypeScript/Svelte appview of
> payment *recipes* (Paidy, Fly.io, 東京都水道局, …) — a surface a person uses
> to set a payment up. This repo is the governed decision layer that says
> whether a payment may be scheduled or released at all. Two faces of the same
> subject is allowed; two repos on the same face is not, so the short name
> stays with the incumbent and the role suffix names this one
> (ADR-2607102200 addendum 14). The Clojure namespaces are `shiharai.*` — the
> neighbour has none.

A supplier invoice arrives. This actor decides whether a payment against it
may be *scheduled*, and whether a scheduled payment may be *released* — and
that is the end of its reach. It is the itonami actor pattern applied to the
outgoing-money side: an advisor node that may only propose, an independent
Governor that can refuse, and an append-only ledger of both.

Sibling of [`cloud-itonami/tehai`](https://github.com/cloud-itonami/tehai),
which drafts the invoices. This one pays them, which is the side where a
mistake does not get a second try.

```
:intake -> :advise -> :govern -> :decide -+-> :commit            (:ok? true)
                                          +-> :request-approval  (:escalate?, interrupt)
                                          +-> :hold              (:hard?)
```

---

## The ceiling

**This repository contains no capability to move funds, and that is a design
constraint rather than a stage of development.**

Concretely, and checkably:

| | |
|---|---|
| network calls | none — `grep -r "http\|fetch\|slurp" src/` finds nothing |
| credentials, keys, tokens | none |
| bank API client | none. `kotoba.banking` is a dependency for **IBAN validation and double-entry arithmetic**; `kotoba.banking.api`, which builds Berlin Group payment-initiation requests, is deliberately **not** required |
| the strongest thing `:commit` writes | a map with `:payment/status :authorised` |
| `:effect` the advisor may emit | `:propose`, and only that — `governor.core/no-actuation` holds anything else |

An `:authorised` record is **a statement that a human approved a
disbursement. It is not the disbursement.** Whatever performs one is a
separate system with its own authority, and moving it into this repository
would remove the ceiling this actor exists to hold.

`:release-payment` therefore **always** escalates, at any confidence, with no
configuration that turns it off.

---

## What it consumes rather than reinvents

| library | what it answers here |
|---|---|
| [`kotoba-lang/governor`](https://github.com/kotoba-lang/governor) | the verdict itself. `gov/verdict` assembles it, `gov/missing-subject` / `no-actuation` / `unknown-scope` / `scope-owner-mismatch` are the four shared provenance rules, and `gov/conformance-failures` is asserted over **every verdict this actor can emit** |
| [`kotoba-lang/taxlaw`](https://github.com/kotoba-lang/taxlaw) | 仕入税額控除 support and 電子帳簿保存法 第七条 preservation — **in three values**, all three of which this actor keeps |
| [`kotoba-lang/banking`](https://github.com/kotoba-lang/banking) | IBAN (ISO 13616) mod-97 identification, and `balanced?` / `posting` for the double-entry the payment produces |
| [`kotoba-lang/langgraph`](https://github.com/kotoba-lang/langgraph) | the StateGraph and the checkpoint that makes `:request-approval` a real interrupt |

**`deps.edn` contains zero `:local/root`.** Every dependency is a git
coordinate. The suite below was run from `/tmp/shiharai`, a directory with no
sibling checkouts on disk — which is both what a fork gets and what a
murakumo fleet gate ships.

---

## The rules

### 24 HARD invariants — `:hold`, no approval route

Provenance (from `kotoba-lang/governor`): unregistered supplier · `:effect`
other than `:propose` · unregistered payable · **a payable belonging to a
different supplier**.

Amounts: **an unknown payable amount** · a non-positive or non-integer amount
· a payment/payable disagreement · a currency mismatch.

Duplication: **a second payment against a payable already covered** · an
overpayment · a reused payment id · a release of something never scheduled ·
a release that alters what the human is about to approve.

Accounts (via `kotoba.banking`): no destination account · an IBAN that fails
mod-97 · an unregistered funding account · a funding account in the wrong
currency · an unbalanced posting · a posting that differs from the
governor's own redraft.

Tax (via `kotoba.taxlaw`): a credit claimed in an **uncatalogued**
jurisdiction · a credit claimed without a valid 登録番号 · an electronic
transaction preserved only on paper.

Statute: a payment term **beyond** 60 days · a payable that asserts the
statute applies and does not carry the dates to check it.

### 5 escalations — a human decides

`:release-payment` (always) · an account under a scheme this repo has no
validator for · a payment term at exactly 60 days · a payment scheduled after
the payable's own due date · confidence below 0.6.

---

## Three things this actor refuses to round off

### 1. An unknown figure is not zero and is not unlimited

`store/outstanding` returns **nil** for a payable whose amount nobody
recorded — not 0, which would refuse every payment against it forever, and
not the proposal's own figure, which would let the proposal set its own
ceiling. The governor holds on it (`:unknown-payable-amount`), and no
approval makes an unknown figure known.

This is `cloud.itonami.app.funding`'s rule for bank balances, applied to the
other side of the same transaction.

### 2. A three-valued answer stays three-valued

`kotoba.taxlaw` answers `:none` / `:not-declared` / `:checked` and this actor
keeps all three, plus its own `:not-claimed`. They are not the same fact and
they do not get the same treatment:

| | |
|---|---|
| the payable **claims** 仕入税額控除 in a jurisdiction nobody catalogued | **HOLD**. An affirmative claim needs support, and `:none` is not support |
| the payable does not say how the transaction happened | **not held**, but the verdict carries `:preservation {:taxlaw/coverage :not-declared}`. Nothing was claimed, so nothing was checked |

The asymmetry is deliberate, and it is stated on the verdict rather than
buried: `:tax`, `:preservation`, `:destination`, `:payment-terms`,
`:outstanding` and `:escalations` ride along on every verdict, so **a green
verdict that skipped a question shows the question**.

### 3. A statute is not enforced from a URL

The one statutory payment-term rule here is 取適法（中小受託取引適正化法）
**第三条**, and its text is in `src/shiharai/law.cljc` because it was
retrieved and read:

```
GET https://laws.e-gov.go.jp/api/2/law_data/331AC0000000120?law_full_text_format=json
revision 331AC0000000120_20260101_507AC0000000041   (in force 2026-01-01)
```

> 製造委託等代金の支払期日は、委託事業者が中小受託事業者の給付の内容について検査を
> するかどうかを問わず、委託事業者が中小受託事業者の給付を受領した日…から起算して、
> 六十日の期間内において、かつ、できる限り短い期間内において、定められなければ
> ならない。

Two things follow that a summary of this article would have got wrong.

**Scope is not widened.** 第三条 binds a 委託事業者 paying a 中小受託事業者
for 製造委託等. It is not a rule about invoices. A payable is checked only
when it says it arises from 製造委託等 *and* the supplier is recorded as a
中小受託事業者; otherwise `payment-term` answers `:not-declared`, which is
neither a pass nor a refusal. A 200-day term on an ordinary purchase does not
hold here, and inventing that hold would be enforcing a rule nobody wrote.

**The 起算 boundary is not resolved.** 第一項 counts 60 days
「受領した日から起算して」; 第二項 deems a violating term to be
「六十日を経過した日の前日」. Whether 「六十日を経過した日」 is the 60th day or
the 61st is a question of Japanese legal counting convention, and picking one
would move a hold by a day in a direction nobody authorised. So the answer has
three values on that axis:

| days between receipt and due date | verdict |
|---|---|
| < 60 | `:conforming` — passes |
| **= 60** | **`:boundary`** — outside the limit on one reading, inside it on the other. Neither a violation nor compliance: **escalated to a human** |
| > 60 | `:exceeded` — beyond the limit on every reading. **HOLD** |

Only the unambiguous direction holds.

One further honesty note: `:payment-after-due-date` escalates, and its own
`:detail` string says *「これは商慣行上の判断であって、この repo が読んだ条文が
違法と述べているものではない」*. No article in this repository says late payment
is unlawful, so nothing here claims it is. (`kotoba.taxlaw` supplies the
other statute this actor uses, 電子帳簿保存法 第七条, and that library did the
same reading for it.)

---

## Measured

Run 2026-08-17 from `/tmp/shiharai`, no sibling checkouts on disk:

```
$ clojure -M:test
Ran 68 tests containing 373 assertions.
0 failures, 0 errors.

$ clojure -M:lint
linting took 4994ms, errors: 0, warnings: 0
```

### The tests can fail — 33 mutations, 33 killed, 0 survived

A test that has never gone red is a test nobody has measured. `tools/mutate.cljs`
applies one single-token mutation from `tools/mutations.edn`, runs the suite,
records which tests reddened, and restores the file. It refuses a `:find`
string that does not occur **exactly once**, because a mutation that lands in
a comment produces a red suite that proves nothing.

```
$ nbb tools/mutate.cljs
baseline: Ran 68 tests containing 373 assertions. GREEN
...
=== 33 mutations, 33 killed, 0 survived
```

| mutation | invariant broken | tests reddened |
|---|---|---|
| `:no-actuation` | the advisor may only propose | 5 — `an-effect-other-than-propose-holds`, `the-advisor-cannot-write-past-the-governor`, … |
| `:no-supplier` | supplier must be registered | 1 — `unregistered-supplier-holds` |
| `:unknown-payable` | a cited payable must exist | 3 |
| `:payable-wrong-supplier` | cross-supplier payment | 3 |
| `:unknown-payable-amount` | unknown ≠ 0 | 3 |
| `:outstanding-defaults-to-zero` | the store's own refusal | 3 — incl. `outstanding-is-nil-for-an-unknown-amount-never-zero` |
| `:invalid-amount` | positive integer minor units | 1 |
| `:duplicate-payment` | **the classic AP failure** | 5 |
| `:scheduled-consumes-balance` | `:scheduled` counts, not only `:authorised` | **9** |
| `:overpayment` | amount ≤ outstanding | 1 |
| `:payment-id-reused` | idempotency | 1 |
| `:release-of-unscheduled` | release cites a scheduled payment | 1 |
| `:release-alters-payment` | the human approves what was scheduled | 1 |
| `:currency-mismatch` | no FX guessing | 1 |
| `:no-destination-account` | a payment needs a destination | 1 |
| `:invalid-iban` | mod-97 refusal | 1 |
| `:iban-checksum` | `kotoba.banking` is what is consulted | 2 |
| `:unknown-funding-account` | funding account registered | 3 |
| `:funding-currency` | funding currency matches | 1 |
| `:unbalanced-posting` | double-entry balances | 1 |
| `:posting-mismatch` | governor redrafts, does not trust | 3 |
| `:unchecked-credit-jurisdiction` | taxlaw `:none` is not a pass | 1 |
| `:credit-unsupported` | 登録番号 must be valid | 3 |
| `:electronic-preservation` | 電帳法 第七条 | 3 |
| `:statutory-term-exceeded` | 取適法 第三条, unambiguous direction | 3 |
| `:statutory-sixty-day-limit` | the 60 itself | 4 |
| `:statutory-boundary` | exactly 60 goes to a human | 1 |
| `:statutory-undeterminable` | applicable-and-unevaluable ≠ compliant | 3 |
| `:calendar-rejects-impossible-days` | 2026-02-30 is not a date | 1 |
| `:release-escalates` | release always reaches a human | 4 |
| `:unverified-destination-escalates` | unvalidatable scheme → human | 3 |
| `:confidence-floor` | below 0.6 escalates | 3 |
| `:router-checks-hard-first` | the router fails closed | 1 |

**The harness changed the code twice.** Its first run had two survivors, and
both were real:

1. `:unbalanced-posting` guarded the *governor's own* redraft — which is a
   matched debit and credit by construction and so could never trip it. A
   check that cannot fail is theatre. The rule now guards the posting the
   **advisor** supplied, which is the one that can be wrong, and
   `an-advisor-supplied-posting-that-does-not-balance-holds` kills the
   mutation.
2. `:router-checks-hard-first` survived because `gov/verdict` never emits both
   `:hard?` and `:escalate?`, so through the graph the order is unobservable.
   The routing is now the named `actor/route`, and
   `the-router-fails-closed-on-a-malformed-verdict` feeds it the malformed
   verdict the one drifted governor in this fleet actually produced.

---

## Use

```clojure
(require '[shiharai.actor :as actor] '[shiharai.store :as store])

(def st (store/mem-store))
;; … register-account! / register-supplier! / register-payable! …

(def g (actor/build-graph {:store st}))

(actor/run-request! g {:supplier-id "s-1" :op :schedule-payment
                       :payable "pbl-1" :payment-id "pay-1"
                       :from-account "FUND-EUR" :payment-date "2026-01-20"}
                    {} "thread-1")
;; => :commit, and (store/payment st "pay-1") is :scheduled

(actor/run-request! g {:supplier-id "s-1" :op :release-payment
                       :payable "pbl-1" :payment-id "pay-1"}
                    {} "thread-2")
;; => interrupted at :request-approval. Nothing is :authorised.

(actor/approve! g "thread-2")
;; => :authorised. A human resumed the thread; no money moved.
```

## Known gaps

- **One store backend.** `MemStore` only. tehai carries a `DatomicStore` over
  `langchain.db` with a `MemStore ≡ DatomicStore` contract test; this repo does
  not, so the ledger and the payment set live only as long as the process. For
  an AP actor that matters more than for most — the committed-payment set is
  what makes `:duplicate-payment` a hard hold rather than an awkward
  conversation. Adding it is the next change, and it must arrive with the
  contract test, not without it.
- **No HTTP surface.** tehai serves one route; this actor serves none. That is
  not an accident but it is also not a decision anyone has written down for
  this repo yet.
- **One statute, one jurisdiction.** 取適法 第三条 for JP, plus whatever
  `kotoba.taxlaw` covers. Nothing here is tax or legal advice.

## Test

    clojure -M:test && clojure -M:lint && nbb tools/mutate.cljs

## License

AGPL-3.0-or-later, matching `cloud-itonami/tehai`.

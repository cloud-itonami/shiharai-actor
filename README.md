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
| network calls | none, and this is now a **test** rather than a sentence — `test/shiharai/ceiling_test.clj` reads every `ns` form under `src/` and compares its requires against an allow-list, then scans for the host escapes that need no dependency (`js/fetch`, `slurp`, …). Both halves assert a floor on how much they read, because a scanner pointed at nothing reports the same clean result as a clean repository |
| credentials, keys, tokens | none. The HTTP surface takes an **already-verified** caller DID; shipping a verifier would mean shipping a key |
| bank API client | none. `kotoba.banking` is a dependency for **IBAN validation and double-entry arithmetic**; `kotoba.banking.api`, which builds Berlin Group payment-initiation requests, is deliberately **not** required, and is not on the allow-list the ceiling test enforces. The two names are one character apart, so adding it would not look like a change of policy in a diff — it would look like a typo |
| the strongest thing `:commit` writes | a map with `:payment/status :authorised` |
| `:effect` the advisor may emit | `:propose`, and only that — `governor.core/no-actuation` holds anything else |
| the hand-off to the books | `shiharai.shiwake` **returns a value**. It has no client and no transport, and its `:require` list is pinned to `#{clojure.string}` by a test — a call arrives either as a host escape or as a dependency, and both doors are shut. See "仕訳 — the hand-off to the books" |
| the answer coming back | `shiharai.handoff` is handed a reply somebody else obtained and turns it into a ledger fact. Same pinned `:require` list, same scan. Recording what the ledger did does not require being the thing that asked it. See "受領確認 — what the ledger did with the entry" |
| what the HTTP surface can reach | scheduling, and nothing past it. The op is a **constant** in `propose-payment-core!`, not a field, so no body asks for a release; an escalation returns 202 and stops; and there is no function on that surface that resumes an interrupted thread |

> An earlier version of this table claimed `grep -r "http\|fetch\|slurp" src/`
> finds nothing. Run it and it finds one line: the e-Gov URL that 取適法 第三条
> was retrieved from, in `src/shiharai/law.cljc`. That is a citation and not a
> call, and it was always harmless — but a check whose stated form does not
> actually pass is a check nobody runs. The suite now names that one hit, so a
> second one fails the build instead of quietly joining a claim that was
> already off by one.

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
| [`kotoba-lang/langchain-store`](https://github.com/kotoba-lang/langchain-store) | the EDN-blob codec, identity schema and seq-keyed event streams under `DatomicStore` — the seam ~190 itonami actors were hand-rolling identically |

**`deps.edn` contains zero `:local/root`.** Every dependency is a git
coordinate, transitively: `langchain-store` pulls `langchain-clj`, which pulls
`kotoba-lang/json`, and none of the four carries a `:local/root`.

That was checked by **reading each `deps.edn` as EDN**, not by grepping it.
`langchain-store`'s own `deps.edn` contains a comment explaining the
`:local/root` it used to have and no longer does — so `grep :local/root`
answers yes and `clojure.edn/read-string` answers no. The reader is right.
This exact mistake has been made twice in this workspace in one week.

The suite below was run from a fresh `git clone` into `/tmp` with no sibling
checkouts on disk — which is both what a fork gets and what a murakumo fleet
gate ships.

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

## Where the committed payments live

`:duplicate-payment` is a HARD hold with no approval route, and what it is
enforced against is the set of committed payments. Until this store had a
second backend, that set lived exactly as long as the process — **restart the
actor and every payment it had ever made became payable again**, silently,
with a green verdict. The hold would not have failed. It would have stopped
having anything to be true about, which is worse, because a failure is
visible.

There are now two backends behind one protocol:

| | `MemStore` | `DatomicStore` |
|---|---|---|
| where the records are | one process-local atom | a `langchain.db` connection |
| what crosses a restart | nothing | whatever the journal's host wrote |
| dependencies | none | `langchain-store` → `langchain.db` |

`datomic-store` optionally takes langchain.db's persistence port — an
`{:append :read}` pair over an append-only log it replays on open.
**That is the seam, not the durability**, and this repository says so rather
than calling itself durable: the events are plain EDN, so a host that can
write bytes (a file, B2, a kotobase pod) supplies those two functions and
nothing here changes. A journal held in memory by a worker about to be
evicted is still a journal. The edge reports `:persistence :delegated` for
that mode and never the word *durable*, because vouching for a host this repo
cannot see is not something it is in a position to do.

`test/shiharai/store_contract_test.clj` runs **every assertion against both
backends**, and the one it exists for is this: commit a payment, throw the
store away, open another one over the same backing, and the duplicate is
still refused —

```
(let [st2 (reopen)]                     ; new record; old one dropped
  (is (= 0 (store/outstanding st2 "pbl-1")))
  (is (= :hold (disposition (schedule-again st2))))
  (is (contains? (rules ...) :duplicate-payment))
  (is (false? (:escalate? verdict))))   ; no approval route out of it
```

For `MemStore` the backing is the atom, so this shows the payment set is not
in the store *record* — it does not show it survives a process, and the test
says so in as many words. For `DatomicStore` the journal is **round-tripped
through `pr-str` and `read-string` before the reopen**, so the only thing
crossing from before to after is serialised bytes.

## The surface

Four functions in `src/shiharai/edge/endpoints.cljc`, portable `.cljc`, plain
data in and `{:status :body}` out. No framework, no router, no host: whoever
mounts them owns the transport.

| | |
|---|---|
| `register-payable-core!` | record an invoice — against the **caller's own** supplier, derived from the verified DID. A body that names a supplier is a 400, not a silent substitution |
| `propose-payment-core!` | ask whether a payment may be **scheduled**. 200 scheduled · **202 a person must decide, nothing written** · 409 held |
| `verdict-core!` | read back what was decided about a payment id. **404 `:verdict :unknown`** when there is nothing on record |
| `ledger-core!` | the caller's own entries, `:scope :supplier`, plus a count of anything unattributable |

**There is no fifth function, and that is the point.** No `release`, no
`approve`, no `resume` — checked by a test over `ns-publics`. The op in
`propose-payment-core!` is a constant rather than a field, so a body carrying
`:op :release-payment` gets scheduled like any other; when a proposal
escalates, the graph interrupts and the response is 202; and the graph is
built per request, so the checkpoint an approval would resume does not
outlive the response. A payment becomes `:authorised` because a person drove
`shiharai.actor/approve!` in a process that is not this one.

Three-valued answers stay three-valued across the boundary:

- **202 is not 200 and not 409.** Collapsing "a person must look at this"
  into either neighbour is the two-valued rounding this actor refuses.
- **An unknown outstanding travels as `:unknown`, never `nil`.** A nil
  crossing a JSON boundary becomes `null`, and a reader who treats `null` as
  0 has turned "nobody recorded this amount" into "this invoice is settled".
- **404 `:verdict :unknown` is not an empty 200.** One says the actor refused
  nothing; the other says the actor was never asked. A console rendering an
  empty success as *clear* would show a payment nobody proposed as one that
  passed.
- **A malformed payment id is 400, not 404.** Answering 404 tells the caller
  their id is fine and merely unknown. (This one is not hypothetical: the
  first version of `non-blank` returned `false` rather than `nil` for a
  non-string, `(nil? id)` was therefore false, and a numeric id sailed past
  the 400 to be looked up as `false`. The test that feeds it a `nil` found
  it.)

Two refusals in `register-payable-core!` worth naming. **An existing payable
id is refused rather than overwritten** — the protocol's `register-payable!`
upserts, which is right for an operator correcting a record and wrong for an
endpoint, because moving `:payable/amount-minor` moves the ceiling that
`:overpayment` and `:duplicate-payment` are measured against, under payments
that may already be scheduled. And **an absent amount registers as unknown
and says so**, rather than defaulting to 0 — the same lie the store refuses
to tell.

---

## 仕訳 — the hand-off to the books

**A payment that was authorised and never became a journal entry is money
nobody's books show.** Deciding is not bookkeeping, and until
`src/shiharai/shiwake.cljc` this repository stopped at the decision: it could
say a disbursement was approved and had no way to say so to the ledger that
has to carry it. `cloud-itonami-isco-4311` owns that ledger.
`cloud-itonami/keihi` landed the same seam on the expense-claim side; this is
its counterpart, and the two are not mirror images.

`shiwake/entry-request` turns one `shiharai.store/ledger` fact into the
`:draft-entry` request 4311 accepts. Two public functions, no third.

### It produces a value; it makes no call

No client, no transport, no reference to the ledger actor. The value goes out
of this repository the way every other decision does — carried by something
else. Two reasons, and the second is the load-bearing one:

1. This actor's ceiling is that it proposes. Writing into another actor's
   ledger would be the actuation the whole design refuses.
2. **A call would make the accounts this actor's business, and they are not.**
   Which 買掛金 account a supplier sits in and which 現金預金 account the
   money left are the client's chart. `kotoba-lang/shohyo` refuses to guess
   what an account is precisely because a statement that guessed still
   balances, so the mapping is an argument here too.

Checked rather than claimed: `this-namespace-reaches-nothing` scans the file
for call shapes **and pins its `:require` list to exactly
`#{clojure.string}`**, because a call arrives either as a host escape or as a
dependency and the pin closes the second door. The token `"post"` is
deliberately not in that scan — in an accounts-payable repository it is
domain vocabulary (`postable-statuses`, `redraft-posting`), so scanning for
it reddens on the subject matter. `keihi` could afford that token; this repo
found out it cannot.

### The direction, and why it is the other way round from an expense claim

```
Dr 買掛金 / 未払金   the liability this payable is, goes down
Cr 現金預金          the funding account the money left, goes down
```

An expense claim **recognises** a cost that was not on the books before. A
payable was already recognised when the invoice was booked — this actor's own
sibling `cloud-itonami/tehai` drafts them — so a payment that debited an
expense would book the same cost twice, once on arrival and once on payment.
What a payment changes is which side of the balance sheet the money sits on,
not how much of it there is.

It is the same direction `governor/redraft-posting` already takes. What is
**not** reused from that redraft is its account names: `"AP"` is
`governor/default-ap-account`, a constant this repository chose, and
`"FUND-EUR"` is a row in this actor's own registry. Neither is an account in
anybody's chart. The redraft proves the payment balances; the mapping says
what it balances between.

### A scheduled payment is not a payment

`postable-statuses` is `#{:authorised}` and `store/settled-statuses` is
`#{:scheduled :authorised}`, and **the difference between those two sets is
the distinction this namespace exists to hold**. `:scheduled` consumes a
payable's balance because a second payment against an invoice already
promised is a duplicate; it does not enter the books, because nothing has
left the account. One set answers 「もう払う約束をしたか」, the other
「もう出て行ったか」.

### Every way the hand-off can lose a payment is a named status

| | |
|---|---|
| `:not-authorised` | held, or nobody approved it. Its own value and never nil, because a caller treating "no entry" as "nothing to do" would skip exactly the payments somebody has to look at |
| `:not-released` | scheduled; the cash has not moved. Separate from the above because the action it calls for — approve it, or decide not to — is a different action |
| `:unknown-payment-status` | a status this repo cannot read. Folding it into `:not-released` would say *a human still has to approve this* about a record nobody understood |
| `:no-mapping` | the supplier or the funding account is unmapped. **No suspense-account fallback**: that posts the entry and makes the missing decision invisible, which is worse than not posting, because a hole in the books can be found and a plausible wrong account cannot. **A half-filled mapping is no mapping** — an entry missing one line balances by having lost it |
| `:unusable-payment` | the amount is not a positive integer of minor units, or there is no currency, or no invoice is cited. 4311's own body parser accepts any `number?`, so a fractional minor unit would cross it — this side is the narrower one on purpose |

`entry-requests` returns `{:ok [...] :skipped [...]}` and each refusal carries
`:shiwake/record`, the fact it refused. A batch that filtered would report a
clean run and leave the unconvertible payments invisible, which on this side
of the books means a disbursement with no entry against it.

### Three things this seam is honest about

**Partial payments post at the payment's amount.** The residue stays an open
買掛金 balance and needs no entry, because nothing about it changed. The
figure comes from `:payment/amount-minor` and this namespace never sees the
payable's face value — so there is nothing here to reach for by mistake.

**The input is the ledger fact, not the graph's final state**, and the two
are not interchangeable. A released payment routes through
`:request-approval`, so `run-request!` reports *that* disposition for a run
that committed; the `:commit` node writes `:disposition :commit` onto the fact
it appends. One value records a route and the other an outcome. Folding the
state would call an authorised payment `:not-authorised`, which is asserted
in `the-graphs-final-state-is-a-route-and-the-ledger-fact-is-an-outcome`
rather than left as a warning.

**An entry cannot be traced back to the payment that produced it, except
through the payable it cites.** `draft-entry-core!` reads `:source-doc` and
`:lines` out of a body and drops everything else, so the payment id is
reported on the wrapper instead of being smuggled into a field that would
vanish. Naming the gap beats shipping a field that looks like it arrived.

And the citation itself is the right way round: the payable is the source
document, and **the registry that decides whether that citation is good is
4311's, not this one's** — that actor holds `:unknown-source-doc` for a
document its own store never registered. A payable this actor knows is not
thereby a 原始証憑 the ledger knows.

---

## 受領確認 — what the ledger did with the entry

**Converted is not posted.** `shiwake` produces the request; nothing recorded
what happened to it. 4311 can commit the entry, find it already there, hold it
against a rule, park it for a human, or refuse the body — and from this side
all five arrived as the same nothing.

That is the defect one layer up from the one `shiwake` exists for, and it has
the same shape: no error message, both actors reporting a clean run, the books
short by one disbursement. A payment converted, submitted and *refused* was
indistinguishable from one that posted.

`handoff/fact` turns one reply into a `shiharai.store/ledger` fact the actor
appends with the `append-ledger!` it already has — no new store call, no new
schema, beside the graph's own `:commit` and `:hold` facts.

| reply | `:handoff/outcome` |
|---|---|
| 200, `:duplicate? false` | `:posted` |
| 200, `:duplicate? true` | `:duplicate` — **not** `:posted`. One call wrote the posting; the other found it already there, and on this side of the books that is the difference between a disbursement recorded once and one recorded twice |
| 202 | `:awaiting-approval`, with the escalation reason |
| 409 | `:held`, with the violations |
| 400 | `:rejected` — the body this actor emitted |
| 403 | `:not-permitted` — who it authenticated as |
| 503 | `:unavailable` — a ledger deployment with no store and no allow-list |
| anything else, a body that is not a map, or a 200 that does not say whether it duplicated | `:unreadable`, carrying the whole reply |

**The good outcome is recorded too.** A ledger holding only refusals cannot
answer 「これは計上されたか」, which is the one question the hand-off exists to
close.

400/403/503 stay three answers where 4311's own batch collapses them into
`:rejected`, because they send a reader to three different places. That is the
argument the ledger actor already makes about its own 503: misattributed blame
sends an operator to look at their own registration while the fault is
elsewhere.

**Every fact names the payable, the payment id and the supplier**, plus the
ledger's posting id where there is one. The payable is the join key the entry
itself carries; **the payment id is the one the entry cannot carry**, because
`draft-entry-core!` drops everything but `:source-doc` and `:lines` — so this
side keeps it, at the only point in the hand-off where nothing drops it.
`:handoff/posting` is present even when nil.

**The status is what says whether something was submitted**, not the presence
of a request-shaped map: a `shiwake` refusal carrying a stale request scores
`:not-submitted`, never an outcome.

`handoff/facts` does the batch. Results join to entries **by position only**,
so a length mismatch is refused rather than zipped — one missing result
misattributes every outcome after it while the entries that fall off the end
get none at all. A result echoing a different `:source-doc` is
`:misattributed` for the same reason, and a batch result whose status and
`:outcome` disagree is `:unreadable`: reading `:outcome` alone would let a 500
arrive labelled `:posted`, and reading the status alone would lose the
posted/duplicate distinction, which a batch result carries nowhere else.

**A refused batch still returns one fact.** Returning none would mean a caller
looping over them wrote nothing, and a failed hand-off would look exactly like
a hand-off nobody attempted — the defect this namespace exists to remove,
reproduced by the namespace itself.

Same ceiling as `shiwake`: two public functions and two vocabularies, requires
pinned to `#{clojure.string}`, scanned for call shapes by a test that reads its
own source, and on `ceiling_test.clj`'s allow-list like everything else.

---

## Measured

Run 2026-08-18 from a fresh `git clone` into `/tmp`, with an empty dependency
cache **and an empty `GITLIBS`** — so every dependency was fetched from its
git remote during the run, and no sibling checkout on this disk could have
satisfied one:

```
$ git clone …/shiharai-actor.git /tmp/shiharai-fresh && cd /tmp/shiharai-fresh
$ CLJ_CACHE=…/cache GITLIBS=…/gitlibs clojure -M:test
Ran 165 tests containing 1116 assertions.
0 failures, 0 errors.

$ CLJ_CACHE=…/cache GITLIBS=…/gitlibs clojure -M:lint
linting took 673ms, errors: 0, warnings: 0

$ ls …/gitlibs/libs
io.github.cognitect-labs  io.github.com-junkawasaki  io.github.kotoba-lang
```

The iteration that added the second store backend and the surface took the
suite from **68 tests / 373 assertions** to 130 / 792; the 仕訳 hand-off took
it to 146 / 899; recording what the ledger did with the entry took it to
**165 / 1116**.

### The tests can fail — 92 mutations, 92 killed, 0 survived, 0 unmeasured

A test that has never gone red is a test nobody has measured. `tools/mutate.cljs`
applies one single-token mutation from `tools/mutations.edn`, runs the suite,
records which tests reddened, and restores the file. It refuses a `:find`
string that does not occur **exactly once**, because a mutation that lands in
a comment produces a red suite that proves nothing.

```
$ nbb tools/check-mutations.cljs
SCANNED	92 mutations
all find strings occur exactly once

$ nbb tools/mutate.cljs
baseline: Ran 165 tests containing 1116 assertions. GREEN
...
=== 92 mutations, 92 killed, 0 survived, 0 unmeasured
```

**`0 unmeasured` is a new column, and it is there because the harness scored
57/57 once while measuring only 56.** `:stream-seq-advances`'s first form
inserted an unbalanced paren: the file stopped *reading*, the suite printed no
summary and exited non-zero, and the harness — which treated any non-zero exit
as a kill — counted it. Zero tests had reddened. A mutation that breaks the
reader demonstrates the reader.

So a run that produces no summary is now its own outcome, `UNMEASURED`, and
fails the harness exactly as a survivor does. The mutation itself was
rewritten to pin the sequence number at 0 instead, which reddens six tests
about append ordering. The pair is worth stating: *the thing broken and the
thing reported have to be the same thing*, and a tally cannot tell you that.

| mutation | invariant broken | tests reddened |
|---|---|---|
| `:no-actuation` | the advisor may only propose | 5 — `an-effect-other-than-propose-holds`, `the-advisor-cannot-write-past-the-governor`, … |
| `:no-supplier` | supplier must be registered | 1 — `unregistered-supplier-holds` |
| `:unknown-payable` | a cited payable must exist | 3 |
| `:payable-wrong-supplier` | cross-supplier payment | 4 |
| `:unknown-payable-amount` | unknown ≠ 0 | 5 |
| `:outstanding-defaults-to-zero` | the store's own refusal | 7 — incl. `outstanding-is-nil-for-an-unknown-amount-never-zero` |
| `:invalid-amount` | positive integer minor units | 3 |
| `:duplicate-payment` | **the classic AP failure** | **10** |
| `:scheduled-consumes-balance` | `:scheduled` counts, not only `:authorised` | **22** |
| `:overpayment` | amount ≤ outstanding | 1 |
| `:payment-id-reused` | idempotency | 1 |
| `:release-of-unscheduled` | release cites a scheduled payment | 1 |
| `:release-alters-payment` | the human approves what was scheduled | 1 |
| `:currency-mismatch` | no FX guessing | 1 |
| `:no-destination-account` | a payment needs a destination | 1 |
| `:invalid-iban` | mod-97 refusal | 1 |
| `:iban-checksum` | `kotoba.banking` is what is consulted | 3 |
| `:unknown-funding-account` | funding account registered | 3 |
| `:funding-currency` | funding currency matches | 1 |
| `:unbalanced-posting` | double-entry balances | 1 |
| `:posting-mismatch` | governor redrafts, does not trust | 3 |
| `:unchecked-credit-jurisdiction` | taxlaw `:none` is not a pass | 1 |
| `:credit-unsupported` | 登録番号 must be valid | 3 |
| `:electronic-preservation` | 電帳法 第七条 | 3 |
| `:statutory-term-exceeded` | 取適法 第三条, unambiguous direction | 4 |
| `:statutory-sixty-day-limit` | the 60 itself | 4 |
| `:statutory-boundary` | exactly 60 goes to a human | 1 |
| `:statutory-undeterminable` | applicable-and-unevaluable ≠ compliant | 3 |
| `:calendar-rejects-impossible-days` | 2026-02-30 is not a date | 1 |
| `:release-escalates` | release always reaches a human | 5 |
| `:unverified-destination-escalates` | unvalidatable scheme → human | 5 |
| `:confidence-floor` | below 0.6 escalates | 3 |
| `:router-checks-hard-first` | the router fails closed | 1 |
| **the store, and the contract between its two backends** | | |
| `:payment-identity-in-the-durable-store` | re-committing an id upserts, not forks | 2 |
| `:payable-identity-in-the-durable-store` | re-registering a payable replaces it | 2 |
| `:journal-is-replayed-on-open` | a store opened over a journal rebuilds from it | 5 |
| `:journal-is-appended-to` | a write reaches the journal, not only the conn | 5 |
| `:stream-seq-advances` | an append goes to the NEXT seq | 6 |
| `:collection-reads-are-ordered` | not the backend's map-iteration accident | 1 |
| `:a-hold-is-attributed` | a refusal records whose it was | 3 |
| **the surface** | | |
| `:edge-absent-allowlist-is-503` | it refuses; it does not open | 1 |
| `:edge-unlisted-caller-is-403` | verified is not the same as permitted | 1 |
| `:edge-body-may-not-name-a-supplier` | the supplier comes from the DID | 2 |
| `:edge-op-is-a-constant` | **no body reaches the release path** | 1 |
| `:edge-escalation-is-not-a-success` | 202 is neither of its neighbours | 1 |
| `:edge-payable-is-not-overwritten` | an endpoint does not move the ceiling | 2 |
| `:edge-supplier-must-be-registered` | no payable for a supplier nobody registered | 1 |
| `:edge-amount-must-be-a-positive-integer` | refused, not coerced | 1 |
| `:edge-unknown-outstanding-is-not-zero` | `:unknown`, never `null` | 1 |
| `:edge-unknown-verdict-is-not-a-pass` | 404 `:unknown`, not an empty 200 | 2 |
| `:edge-malformed-id-is-not-a-miss` | 400, not 404 | 2 |
| `:edge-verdict-is-scoped-to-the-caller` | another supplier's id is not readable | 1 |
| `:edge-ledger-is-scoped-to-the-caller` | this supplier's entries and no others | 2 |
| `:edge-reports-what-it-could-not-attribute` | a filtered view says it is filtered | 1 |
| `:edge-store-mode-rejects-a-typo` | a misspelt variable selects no mode | 1 |
| `:edge-journalled-is-not-called-durable` | no vouching for an unseen host | 1 |
| **the ceiling** | | |
| `:ceiling-permits-only-known-dependencies` | a dependency that could reach a host fails the build | 1 |
| **仕訳 — the hand-off to the books** | | |
| `:shiwake-unauthorised-is-its-own-status` | "no entry" ≠ "nothing to do" | 4 |
| `:shiwake-only-an-authorised-payment-posts` | **a scheduled payment is a commitment, not a settlement** | 3 |
| `:shiwake-scheduled-has-its-own-name` | "wait for approval" ≠ "look at this" | 3 |
| `:shiwake-unknown-status-is-not-approval-pending` | an unreadable status is not a cautious one | 1 |
| `:shiwake-no-suspense-account` | a suspense fallback makes the missing decision invisible | 2 |
| `:shiwake-half-a-mapping-is-no-mapping` | an entry missing one line balances by having lost it | 2 |
| `:shiwake-amount-is-a-positive-integer-of-minor-units` | narrower than the ledger's own parser | 1 |
| `:shiwake-currency-must-be-declared` | an amount with no unit is not an amount | 1 |
| `:shiwake-a-document-must-be-cited` | 取引の捏造禁止, from this side | 1 |
| `:shiwake-the-payment-discharges-a-liability` | **Dr 買掛金 / Cr 現金預金, not an expense** | 3 |
| `:shiwake-every-line-carries-its-currency` | 4311 groups by currency before comparing | 1 |
| `:shiwake-the-payable-is-the-source-document` | the invoice, not the payment that settled it | 4 |
| `:shiwake-request-carries-only-what-the-ledger-reads` | no field that endpoint silently drops | 1 |
| `:shiwake-batch-keeps-what-it-could-not-convert` | a batch that filtered reports a clean run | 2 |
| `:shiwake-batch-ok-is-only-the-ok` | a refusal does not travel in the `:ok` half | 2 |
| `:shiwake-a-refusal-carries-the-record-it-refused` | a refusal nobody can attribute is one nobody can act on | 2 |
| `:shiwake-reaches-nothing` | it produces a value; it makes no call | 2 |
| **受領確認 — what the ledger did with the entry** | | |
| `:handoff-good-outcome-recorded` | a posted entry is recorded, not lost | 5 |
| `:handoff-duplicate-is-not-posted` | **one call wrote it; the other found it already there** | 2 |
| `:handoff-silent-200-is-not-a-post` | a 200 that will not say which is not a claim that it wrote | 1 |
| `:handoff-unknown-status-is-not-a-success` | an unreadable reply never becomes a success | 1 |
| `:handoff-deployment-refusals-stay-three` | 400/403/503 send a reader to three different places | 2 |
| `:handoff-unreadable-carries-the-reply` | an unreadable reply keeps enough of itself to be diagnosed | 1 |
| `:handoff-fact-names-the-payable` | a record that cannot be joined back is not a reconciliation | 4 |
| `:handoff-fact-names-the-payment` | **the id the entry body cannot carry is kept here** | 3 |
| `:handoff-fact-names-the-supplier` | the actor's own ledger stamps it; so does this | 2 |
| `:handoff-posting-key-always-present` | a key that vanishes when the answer is interesting | 3 |
| `:handoff-unsubmitted-is-not-scored` | the STATUS says whether it was submitted, not a stale request | 1 |
| `:handoff-unsubmitted-is-not-scored-in-a-batch` | the same, on the batch side | 1 |
| `:handoff-length-mismatch-refused` | **results join by position; a mismatch is refused, not zipped** | 3 |
| `:handoff-refusal-is-itself-a-fact` | a failed hand-off must not look like one nobody attempted | 4 |
| `:handoff-misattribution-refused` | an outcome on the wrong payable reads as an answer | 1 |
| `:handoff-batch-status-and-outcome-must-agree` | `:outcome` alone lets a 500 arrive labelled `:posted` | 1 |
| `:handoff-missing-results-is-not-an-empty-batch` | "none answered" ≠ "zero submitted" | 1 |
| `:handoff-reaches-nothing` | it records a value; it makes no call | 2 |

`:scheduled-consumes-balance` reddening 22 tests is not padding: `:scheduled`
ceasing to consume the balance is the single change that would let the same
invoice be paid twice, and the contract test now asks that question on two
backends and across a reopen, so it is asked from more directions than before.

**The harness changed the code four times.** Its first run had two survivors,
and both were real:

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

**And a fourth, in the 仕訳 hand-off — where the harness was right and the
mutation was wrong.** `:shiwake-only-an-authorised-payment-posts` widened
the postable set to include `:scheduled`, and **nothing went red**. The
survivor was correct: an earlier `(= :scheduled status)` branch already
caught it, so the widened check was unreachable and nothing had actually
been broken. Reading the pair — *the thing broken and the thing reported
have to be the same thing* — is what caught it; the tally alone said
"survivor" and would have been read as a gap in the tests. `postable-payment`
now tests `postable-statuses` **first**, which is what makes that set the
only thing deciding what posts, and therefore the only thing that can be
widened wrongly. It reddens three tests.

The third was the surface's own, and the test found it rather than a review:
`non-blank` returned `false` for a non-string instead of `nil`, every caller
guarded with `(nil? …)`, and a numeric payment id therefore skipped its 400
and was looked up as `false` — answering 404, which tells the caller their id
is merely unknown. The test that feeds `verdict-core!` a `nil` is what went
red.

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

Over a journalled store, so that the payment set outlives the connection:

```clojure
(def log (atom []))
(def st (store/datomic-store (store/journal log)))
;; … register / schedule …

;; a later process, given the same events back by whatever wrote them:
(def st' (store/datomic-store (store/journal (atom @log))))
(store/outstanding st' "pbl-1")   ;; => 0. The duplicate is still refused.
```

And the authorised payment as an entry the ledger will take — a value, which
something else carries:

```clojure
(require '[shiharai.shiwake :as shiwake])

(shiwake/entry-requests
 (store/ledger st)
 {:suppliers {"s-1" "買掛金"}          ; the client's chart, not this actor's
  :accounts  {"FUND-EUR" "普通預金"}})
;; => {:ok      [{:shiwake/status :ok :shiwake/payment-id "pay-1"
;;                :shiwake/request {:op :draft-entry :source-doc "pbl-1"
;;                                  :lines [{:side :dr :account "買掛金"   …}
;;                                          {:side :cr :account "普通預金" …}]}
;;                :shiwake/record  {…}}]
;;     :skipped [{:shiwake/status :not-released …}]}   ; kept, not filtered
```

## Known gaps

- **No durable HOST is wired up.** `DatomicStore` takes the `{:append :read}`
  port and replays it; what nobody here has done is point it at a file, a
  bucket or a kotobase pod. Until someone does, `:persistence :delegated` is
  delegated to a journal that is itself in memory. The seam is the part that
  was missing; the host is a deployment decision this repository should not
  make on an operator's behalf, but it is also not done.
- **A release writes the posting twice.** `:commit` runs once when a payment
  is scheduled and again when it is released, and each run appends the
  governor's redraft — so the postings stream carries two entries with the
  same `:ledger/posting` id for one payment. As an audit stream of commit
  events that is arguably right; as accounting postings it double-counts, and
  anything summing the stream must dedupe by that id. Measured and asserted in
  the contract test rather than left to be discovered by whoever sums it
  first. Not changed here: it is behaviour older than this iteration, and
  quietly altering what the ledger records is not a side effect of adding a
  backend.
- **The HTTP surface has no mounted transport.** The four functions are
  portable and tested; no Worker, route table or CACAO verifier ships with
  them, and the verifier deliberately never will. That is a real gap in
  deployability and a deliberate one in authority.
- **Nothing carries the 仕訳 request to the ledger.** That is the design —
  this actor returns a value and something with its own authority delivers
  it — but it means the seam is proved and not yet used. Until an operator
  wires a carrier, an authorised payment still becomes a journal entry only
  because somebody moved the map.
- **Nothing here reconciles the two directions.** `shiwake` converts what
  the ledger fact says; it does not go back and check that 4311 accepted it.
  A request refused there (`:unknown-source-doc`, `:unbalanced-entry`) is
  invisible from this side, so "converted" is not "posted". Naming it
  because the whole point of the namespace is that a gap between a decision
  and an entry is silent.
- **One statute, one jurisdiction.** 取適法 第三条 for JP, plus whatever
  `kotoba.taxlaw` covers. Nothing here is tax or legal advice.

## Test

    clojure -M:test && clojure -M:lint && nbb tools/mutate.cljs

`nbb tools/check-mutations.cljs` is the pre-flight: it verifies every `:find`
occurs exactly once before the harness spends half an hour discovering that
one of them does not.

## License

AGPL-3.0-or-later, matching `cloud-itonami/tehai`.

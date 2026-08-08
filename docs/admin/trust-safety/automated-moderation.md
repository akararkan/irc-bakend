# Automated Moderation — Admin Dashboard Section

The dashboard surface over the platform's **automated text moderation**: every
piece of user text is scored by a fine-tuned toxicity classifier before it
becomes visible to anyone but its author. This document is the admin's view —
the review queue, the sensitivity dials, teaching the model, and shipping a new
model version.

> **Scope.** This is the dashboard slice. The whole subsystem — design rationale,
> implementation architecture, the two Python containers, what end users
> experience — is [`../../moderation/`](../../moderation/README.md).
> Endpoint request/response JSON: [`../api/automated-moderation.md`](../api/automated-moderation.md).

**Status: [EXISTS]** — built 2026-08-08. 30 endpoints across three controllers
(including the CSV bulk training import, §4). The one thing that is *not*
production-ready is the **data**: see
[§8 Before you trust it](#8-before-you-trust-it).

---

## 1. Purpose & scope

| In scope | Out of scope (see) |
|---|---|
| The proactive review queue — content the classifier could not settle | Reports on **live** content → [content-moderation.md](content-moderation.md) |
| Per-label threshold tuning, hold durations, fallback policy | Strikes / appeals / the reporter relationship → [safety-reports.md](safety-reports.md) |
| The training-data manager ("teach it a word or sentence") | Image/video moderation — **not built**, text only |
| The golden regression suite | The keyword blocklist CRUD itself → [content-moderation.md](content-moderation.md) §2.7 |
| Model registry: retrain → evaluate → gate → promote → rollback | Container ops/runbook → [`../../moderation/operations.md`](../../moderation/operations.md) |
| Volume / band / SLA / model-health metrics | |

---

## 2. Dashboard views

### 2.1 Review queue (landing view) **[EXISTS]**

`GET /api/v1/admin/moderation/review?status=IN_REVIEW`

The queue of content the model landed in the uncertain middle band, or could not
score at all. **Content here has never been seen by another user** — deciding it
*releases or buries*, it does not "take down".

| Widget | Content | Source |
|---|---|---|
| Queue table | One row per case; default sort **riskiest-first** (`maxScore` desc). `?sort=oldest` for FIFO when draining rather than triaging | `items[]` |
| Depth tiles | `inReview` / `pending` / `slaBreached` — independent of the current filter, so they don't move as the operator filters | `counts` |
| SLA-breach rail | `?slaBreached=true` — the hold window expired before a verdict. Longest-waiting authors, and the system admitting it could not decide | `items[]` |
| Entity-type filter | `?entityType=post|post_comment|story|research|qna_answer|chat_message|…` | 13 types |
| Repeat-offender badge | `authorPriorRejections` per row — one borderline comment from an account with fourteen prior rejections is a different decision | `items[].authorPriorRejections` |

**A `pending` count that is large and not draining means the inference container
is down.** That is the loudest symptom this dashboard has of an ops problem —
see [`../platform/README.md`](../platform/README.md).

### 2.2 Case detail / review screen **[EXISTS]**

`GET /api/v1/admin/moderation/review/{caseId}`

| Widget | Content |
|---|---|
| Field list | Every text field **scored independently** — `title`, `body`, `tag[0]`, `alt_text[2]` — so the screen says *which* field tripped, not just "the post" |
| Per-label score bars | The six labels with the **threshold band drawn on them**. This is the whole point: a 0.42 `insult` inside a `0.30/0.80` band is a genuine coin-flip; a 0.42 `threat` inside `0.15/0.50` is nearly a block |
| Blocklist hit | The matched normalised term, when a deny-list rule fired |
| Verdict reason | `MODEL` / `BLOCKLIST` / `SLA_BREACH` / `ADMIN` / `INFERENCE_UNAVAILABLE` |
| Model version | Which artifact produced these numbers — matters when comparing against a promotion |
| Author history | `authorPriorRejections` |

> **Chat and live-chat cases show no text.** DM and message bodies are redacted
> in this queue — the screen gets scores, field names and bands, never the
> message. See [§7 Privacy](#7-privacy).

### 2.3 Decisions **[EXISTS]**

| Action | Endpoint | Step-up |
|---|---|---|
| Approve / reject one case | `POST /review/{caseId}/decide` | **no** — deliberate |
| Approve / reject up to 100 | `POST /review/bulk` | **yes** |
| Re-run the classifier | `POST /review/{caseId}/rescore` | no |

Single decisions skip step-up on purpose: a moderator works this queue
continuously and re-authenticating per item would make the tool unusable. The
bulk endpoint, which can move a hundred pieces of content in one call, requires
it.

**What a decision actually does.** Approving runs the publication side effects
the create path deliberately deferred — follower fan-out, search indexing, tag
extraction, the author's notification. Rejecting hides the content and tells the
author. Reversing an already-published approval works too: the case's applied
marker is cleared so the applier genuinely re-runs (a reversal that silently
changed nothing would be worse than no reversal button).

### 2.4 `teachModel` — the highest-leverage control **[EXISTS]**

Every decision takes an optional `"teachModel": true`. It writes the reviewed
text into the training dataset with labels derived from your decision, tagged:

- **`ADMIN_CORRECTION`** when you disagreed with the model — the single
  highest-quality label the system can get
- **`REVIEW_PROMOTION`** when you confirmed what it already thought — worth far
  less, but still signal

**When to use it:** whenever the model was clearly wrong.

**When *not* to:** when you approved something for a reason the text alone does
not explain — context, the author's history, a private agreement. The model will
only ever see the text; teaching it "this sentence is fine" when it was fine
*for other reasons* actively makes it worse.

Labels from a decision are coarse by design — a decision cannot say *which* of
the six labels applied, so a rejection is recorded as `toxic` only. Refine it in
the dataset browser (§4) if you know better.

### 2.5 Metrics panel **[EXISTS]**

`GET /api/v1/admin/moderation/review/metrics?windowHours=24`

| Tile / chart | Field | What a bad value means |
|---|---|---|
| **Auto-decided %** | `bands.autoDecidedPercent` | The headline number. A healthy system settles the large majority without a human. A sudden drop = drift, or a bad promote |
| Band breakdown | `bands.{autoApproved,autoRejected,sentToReview,decidedByHuman}` | `sentToReview` creeping up = thresholds too tight, or the model degraded |
| Label distribution | `labels[]` (count + avg score) | Tells you *which* label is driving the review volume — the threshold-tuning signal |
| SLA per entity type | `sla[]` `withinSlaPercent` | Sustained breaches on one type = its inline budget is too tight for its text length, or the container is under-provisioned |
| Volume by type | `volume.byEntityType` | |
| Model health | `model.*` | `circuit: OPEN` = failing fast, content on fallback. `registryInSync: false` = the container serves a different artifact than the registry calls active |
| Dataset | `dataset.untrained` | The retrain trigger — how many examples aren't in a completed run yet |

---

## 3. Sensitivity — the settings surface **[EXISTS]**

`/api/v1/admin/moderation/settings`

This is what makes the architecture worth its split: the model container knows
nothing about thresholds, so changing platform strictness is a database row —
**no redeploy of either service, no retrain.**

### 3.1 Read what's actually in force

`GET /settings` returns two things and both matter:

- `overrides` — what an admin has changed
- `effective` — what is **actually being enforced** right now (overrides layered
  over the yaml bootstrap). Display this one.

### 3.2 Dry-run before you save **[EXISTS]**

`POST /settings/dry-run` replays proposed bands against **already-stored score
vectors** from past cases. Writes nothing. Calls no model. Returns exactly which
decisions would flip and in which direction.

> **Wire this into the threshold editor before the save button.** A threshold
> slider without it means every tune is a guess whose blast radius you discover
> in production. This is the single most important UI detail in this section.

### 3.3 Thresholds

`PUT /settings/thresholds` — two cut points per label create three bands:

| Band | Condition | Outcome |
|---|---|---|
| Auto-approve | all labels below `low` | published |
| Needs review | any label in `[low, high)` | this queue |
| Auto-block | any label ≥ `high` | rejected |

Omit `entityType` for the global default; name one to scope the override.
A per-type override wins — that is how "a research abstract legitimately uses
sharper language than a public comment" gets expressed **without loosening
`threat` anywhere**.

**Keep `threat` and `identity_hate` strict everywhere.** Their defaults sit
lower than the rest (`0.15/0.50` and `0.15/0.55`) because a false negative on
either costs far more than a false positive. If you are raising them, be sure
you know why.

### 3.4 Hold durations & fallback

`PUT /settings/hold-durations`

`holdMs` is a **maximum wait, not a delay** — clean content clears inline in
well under a second and never approaches it. The ceiling exists so a slow or
failed check has a bounded, defined outcome instead of an indefinite one.

`fallback` decides what happens when the ceiling is hit with no verdict:

| Policy | Behaviour | Use for |
|---|---|---|
| `FAIL_CLOSED` | hidden, top of this queue, SLA-breached flag | the right default — everything except stories |
| `FAIL_OPEN_SHADOW` | published **and** flagged for priority review | short-lived, low-risk types. Stories ship with this because a 24-hour story that spends its life in a queue has effectively been deleted |

### 3.5 On/off switches — global and per-surface **[EXISTS]**

Moderation can be switched off **entirely**, or **per surface** — so "turn chat
moderation off, people can say anything there" is one setting write while posts
stay fully moderated. All of it is `ADMIN` only + step-up, and every flip writes
an audit row. Turning a whole surface off is a different order of decision from
nudging a cut point, which is why the raw-key surface is narrower than the
threshold surface.

**Global kill switch** — the "model crashed, let the platform run" lever:

```
PUT /settings/raw   { "key": "enabled", "value": "false" }
```

Everything publishes immediately: no scoring, no holds, no new cases. Before
reaching for it, know that a *crashed* inference container is already a bounded
failure — the circuit breaker fails fast and each surface's `fallback` policy
(`FAIL_CLOSED` queues, `FAIL_OPEN_SHADOW` publishes-and-flags) decides the
outcome. The kill switch is for when you decide you'd rather run **unmoderated**
than degraded; it is a policy choice, not the only recovery path. While it's
off, `GET /settings` returns `WARN_MODERATION_DISABLED` — surface that as a
persistent banner in the dashboard.

**Per-surface switches** — either the raw key `enabled.<type>` or the friendlier
`PUT /settings/hold-durations` with `{"entityType": "...", "enabled": false}`.
The dashboard should present these as one toggle board, grouped the way admins
think about the platform:

| Dashboard group | Entity-type keys to flip together |
|---|---|
| **Chats (DMs & groups)** | `chat_message` |
| **Channels** | `channel` |
| **Posts** | `post`, `post_comment` |
| **Stories** | `story`, `story_poll` |
| **Research** | `research`, `research_comment` |
| **Q&A** | `qna_question`, `qna_answer` |
| **Live streaming** | `stream_meta`, `live_chat` |
| **Annotations** | `content_annotation` |

A group toggle is a frontend composition — the backend switch is per entity
type, so "Posts off" is two setting writes. That granularity is a feature: you
can silence comment moderation while keeping post bodies moderated.

**Three things to know before flipping anything off:**

1. **The keyword blocklist keeps running.** It is an independent system —
   disabling model scoring does not disable exact-word blocking (and that is
   usually what you want: even with the model off, the words you explicitly
   banned stay banned). To genuinely allow *everything* on a surface you would
   also have to clear that surface from the blocklist scopes — see
   [content-moderation.md §2.7](content-moderation.md).
2. **Nothing is retro-scored.** Content submitted while a switch was off is
   already published; re-enabling only affects new submissions.
3. **Cases already in the queue stay there.** Turning a surface off stops new
   cases; it does not release held ones — drain the queue deliberately.

---

## 4. Teaching the model **[EXISTS]**

`/api/v1/admin/moderation/model/training-examples`

### The two levers, and their timescales

| | Blocklist | Training data → retrain |
|---|---|---|
| Effect | immediate, next request | after a training run **and** a human promote |
| Precision | exact / normalised string match | learned, generalises to phrasings you didn't list |
| Use for | a slur trending **right now** | durable improvement |

They are independent. Adding a word to the blocklist does not add it to the
training set, and vice versa. **Do both** when you want the ban now and the
generalisation later.

### Exact words: the certainty guarantee

When the requirement is *"this exact word must never get through — ever"*, that
is the **blocklist's** job, not the model's. The distinction matters:

- The **model** returns probabilities. It generalises — it will catch phrasings
  and variants you never taught it — but it can never *promise* that one
  specific string is always caught. Training it on a word raises the odds; it
  does not make a guarantee.
- The **blocklist** is deterministic. A listed term at severity `BLOCK` is
  rejected at create time, every time, on every scoped surface. This holds for
  real profanity (*fuck*) and equally for **invented or obfuscated strings**
  (*furbhbdjbwjck*) — a platform-specific coinage the model has never seen and
  would score near zero is still a guaranteed reject the moment it's listed.

Matching runs through `KeywordNormalizer` (case-folding, diacritics stripping,
Arabic/Kurdish letter-variant unification), so trivial dodges like `FuCk` or
tashkeel-stuffed variants don't slip past — but a *creatively new* obfuscation
is a new string, and the model is what catches those between blocklist updates.
Hence the standing rule: **blocklist for certainty now, training example for
generalisation later — do both.** Blocklist CRUD and the paste-text test box
live in [content-moderation.md §2.7](content-moderation.md).

### Dataset manager

| Action | Endpoint | Notes |
|---|---|---|
| Add a sentence | `POST /training-examples` | The highest-quality input. Re-posting the same text **updates its labels** rather than duplicating |
| Add a word | `POST /training-examples/word` | Expanded server-side into template sentences — a bare token teaches a sentence-level classifier almost nothing. The response lists every row created |
| Browse / filter | `GET /training-examples?source=` | Filter by provenance; `summary.untrained` is the retrain trigger |
| Delete a bad row | `DELETE /training-examples/{id}` | Fix wrong labels *before* they get trained on |
| Probe the live model | `POST /score-probe` | The fastest answer to "why did this get through?" |

### Bulk import — CSV / Excel **[EXISTS]**

`POST /model/training-examples/import` (multipart) — one-at-a-time entry does
not scale past a few dozen examples. The import surface takes a whole file: an
admin curates it in Excel or Google Sheets, exports it, uploads it in the
dashboard, and the dataset (and optionally the blocklist) is updated in one
pass.

> **CSV is the wire format.** Author in Excel freely, then
> **File → Save As → “CSV UTF-8”**. Native `.xlsx` parsing needs a new library
> dependency, which the offline build rules out for now — if that constraint
> lifts, `.xlsx` becomes accepted directly with the **first sheet** read and
> the same column contract. Quoted fields, embedded commas/newlines and the
> Excel BOM are all handled.

Two file kinds, distinguished at upload:

**1. Sentences file** — full labeled sentences, the highest-quality training
input. One row per sentence:

```csv
text,toxic,severe_toxic,obscene,threat,insult,identity_hate,note
"You are a complete waste of everyone's time.",1,0,0,0,1,0,"common insult phrasing"
"I will find you and hurt you.",1,0,0,1,0,0,"unambiguous threat"
"This methodology section is weak but fixable.",0,0,0,0,0,0,"hard negative — critique is not abuse"
```

**2. Words file** — single words/terms. Each row is template-expanded
server-side into training sentences (exactly like the single-word endpoint),
and can *simultaneously* be pushed to the blocklist for the instant ban:

```csv
word,toxic,severe_toxic,obscene,threat,insult,identity_hate,blocklist,severity,note
fuck,1,0,1,0,0,0,yes,BLOCK,"standard profanity"
furbhbdjbwjck,1,0,1,0,0,0,yes,BLOCK,"invented obfuscation seen in the wild"
damn,1,0,1,0,0,0,no,,"mild — teach the model, don't hard-ban"
```

**File contract (both kinds):**

| Rule | Detail |
|---|---|
| Encoding | UTF-8 (Excel's plain "CSV" export mangles Arabic/Kurdish — use **CSV UTF-8**) |
| Header row | Required; columns matched **by name**, order free; unknown columns rejected with a clear error |
| `text` / `word` | Required, non-blank; `text` ≤ 5 000 chars; quote fields containing commas |
| Label columns | The six labels, each `0` or `1`; blank = `0`; at least the six names present in the header |
| `blocklist` (words only) | `yes`/`no` (blank = `no`); `yes` also inserts the term into the platform blocklist |
| `severity` (words only) | `BLOCK` or `FLAG`; only read when `blocklist=yes`; blank defaults to `BLOCK` |
| `note` | Optional, ≤ 300 chars; stored as provenance on every created row |
| Size | ≤ 5 000 data rows per file — split larger corpora |
| Dedup | Same normalised-text dedup as single entry: a re-imported row **updates labels**, never duplicates — safe to re-upload a corrected file |
| Provenance | Rows land as source `ADMIN_IMPORT` — filter the dataset browser by it (`?source=ADMIN_IMPORT`) to find and remove a bad batch |

**Import flow the dashboard should implement:** upload with `dryRun=true` →
the server validates the whole file and returns a per-row error report
(nothing written) → admin confirms → re-send with `dryRun=false`.
All-or-nothing by default: any row error means nothing is applied. The
`allowPartial` option applies clean rows and returns the rejects row-by-row
(render them as a downloadable errors file). Import is `ADMIN` or `MODERATOR`
+ **step-up** (it can move thousands of rows and touch the blocklist in one
call), and every non-dry-run writes an `ADMIN_MODERATION_IMPORT` audit row.

Two content-quality rules that decide whether an import helps or hurts:

1. **Include hard negatives.** A file that is 100 % toxic rows teaches the
   model that *everything* is toxic. Aim for roughly a third all-zero rows —
   sharp critique, medical/anatomical terms, reclaimed usage you decided is
   acceptable.
2. **Never import your golden cases.** The regression suite must stay
   untrained-on, or it stops measuring anything (see below).

After a large import, `dataset.untrained` on the metrics panel jumps — that is
the retrain trigger. The file only changes the *dataset*; the live model
changes when you retrain and promote (§5). Words with `blocklist=yes` are the
exception: those bans are live immediately.

### Golden regression set

`/model/golden-cases` — a fixed, hand-reviewed suite every candidate model must
still get right before promotion.

**These are never trained on.** Training on them would make the suite measure
memorisation instead of generalisation, and it would stop catching the thing it
exists to catch. Good golden cases are the ones that were genuinely hard:
leetspeak evasion, reclaimed slurs used correctly, medical terminology, sarcasm
you decided was fine.

---

## 5. Model registry **[EXISTS]**

`/api/v1/admin/moderation/model`

```
TRAINING → EVALUATING → READY → (SHADOW) → ACTIVE → RETIRED
                          ↓
                       FAILED
```

### Retraining

Retrain **on demand**, when there is a meaningful batch of new examples —
`dataset.untrained` on the metrics endpoint tells you how many. Fifty-odd is a
reasonable trigger; a small platform will not generate enough daily labeled data
to justify a nightly schedule.

Requires the training container to be up:
`docker compose --profile on-demand up -d model-training`.

### The promotion gate

`READY` versions carry `gatePassed` and `gateDetail`. The gate fails when:

- any **golden regression case** fails, or
- any **per-label F1** dropped more than `retrain.max-f1-drop` (default 0.02)
  versus the currently active version.

Labels the candidate never evaluated — no positives in the validation fold — are
skipped rather than scored zero. Comparing against a metric that was never
measured would fail every gate on a small dataset for no real reason.

A version whose gate failed is still `READY`, not `FAILED`: the run worked, the
numbers are the evidence, and a human decides. `{"force": true}` promotes anyway
and the override is recorded in the audit trail with your user id on it.

**A retrain never auto-promotes.** That is deliberate and not configurable
downward.

### Promote & rollback

`promote` calls `POST /v1/reload` on the inference container **before** flipping
the registry — so a bad artifact leaves the running model untouched and the
database never claims something is live that isn't.

`rollback` re-promotes the most recently retired version. Because every artifact
stays in the shared volume, this never depends on re-running training. It is the
safety valve for a regression the gate did not catch.

**After any promote**, watch `bands.autoDecidedPercent` and `queue.inReview` for
an hour. A promotion that quietly doubled the review rate is exactly the failure
mode the gate cannot see.

---

## 6. Permissions

| | Review queue | Thresholds | Training examples | Golden set | Retrain | Promote / rollback | Kill switch |
|---|---|---|---|---|---|---|---|
| **MODERATOR** | ✅ | ✅ | ✅ | read | ❌ | ❌ | ❌ |
| **ANALYST** | metrics + versions (read) | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **ADMIN** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

**Step-up required for:** bulk decisions, threshold changes, hold-duration
changes, raw setting writes, settings reset, retrain, promote, rollback.

Every mutation writes an `ADMIN_MODERATION_*` audit row with the acting user's
id. There are no anonymous moderation actions.

---

## 7. Privacy

**Chat, DM and live-stream-chat bodies are never rendered to staff.** In the
review queue and case detail, those cases come back with their text replaced by
`"[private message — body withheld from staff by policy]"`. Scores, field names
and threshold bands are still returned, so a moderator can act on a held message
without ever reading it.

For the same reason `teachModel` is **silently ignored** for those types: a
private message entering the training set would leave the platform entirely —
shipped to the training container and baked into model weights — which is a far
larger disclosure than a screen.

This is the platform's absolute content-privacy boundary, not a
moderation-specific carve-out. See [`../communication/README.md`](../communication/README.md).

Submitted text for every other type **is** retained in `moderation_case_fields`
so a moderator can review what was actually written even after the content
itself is deleted or its Cassandra TTL expires. Treat it as user content for
retention and erasure purposes.

Retraining on real user-submitted content is a legitimate and expected use of
moderation data, but it should be **stated explicitly in the data-use policy**
rather than assumed. Get sign-off from whoever owns privacy policy before wiring
flagged user content into the training set at scale — the dashboard makes that
one click, which is exactly why it is worth deciding deliberately.

---

## 8. Before you trust it

Being direct, because this section is otherwise easy to over-read as
"production-grade AI moderation":

1. **The seed training dataset is proof-of-concept size** — on the order of two
   dozen hand-written examples. Enough to validate the pipeline end to end. Not
   enough to trust for real auto-decisions.
2. **The model is English-only**, inherited from the base checkpoint. For
   non-English content it will *under*-detect — a false-negative risk, not a
   false-positive one. The keyword blocklist, which is Arabic/Kurdish
   normalisation-aware, still applies in every language.
3. **The default thresholds are bootstrap values**, not values derived from a
   validation set.

### The sequence to get there

1. Expand the training set with an established labeled corpus using the same six
   labels (e.g. Jigsaw Toxic Comment Classification), plus your own corrections.
2. Retrain, and read the **per-label** metrics — not just macro-F1.
3. Set thresholds from that validation set, using the dry-run to see the impact.
4. Build a golden set from the cases that were genuinely hard.
5. Run for a while with `low` thresholds deliberately tight so humans see most
   borderline traffic, and watch the admin agreement rate before loosening.

Tracked as row **Q** in [../known-issues.md](../known-issues.md).

---

## 9. Related

| Doc | For |
|---|---|
| [`../api/automated-moderation.md`](../api/automated-moderation.md) | Every endpoint's request/response JSON |
| [`../../moderation/README.md`](../../moderation/README.md) | The whole subsystem |
| [`../../moderation/architecture.md`](../../moderation/architecture.md) | How it's implemented, and the deviations from the design |
| [`../../moderation/operations.md`](../../moderation/operations.md) | Running the containers, failure modes, safe promotion |
| [`../../moderation/user-guide/`](../../moderation/user-guide/README.md) | What end users experience — useful for support macros |
| [content-moderation.md](content-moderation.md) | The *other* queue — reports on live content |

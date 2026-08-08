# Moderation — admin & moderator guide

What the dashboard does, how to use it, and what each lever actually changes.

Base path for everything here: `/api/v1/admin/moderation`. Full request/response
shapes in [api.md](api.md).

---

## 1. Two queues, not one

They look similar and are not.

| | **Review queue** `/moderation/review` | **Reports inbox** `/moderation/queue` |
|---|---|---|
| Fed by | the classifier, when it could not decide | users reporting content, failed media scans, keyword hits |
| Question it asks | "is this over the line?" | "was this report valid?" |
| What you see | full text, per-label scores, the threshold bands, author history | reporter counts, report reasons |
| Side effect of deciding | publishes or hides content that is currently invisible | takes down content that is already live |

Content in the review queue **has not been seen by anyone but its author.**
Deciding it is not a takedown; it is a release.

---

## 2. Working the review queue

`GET /moderation/review?status=IN_REVIEW`

Default order is riskiest-first — highest score the model produced. That is where
your time is worth most. `?sort=oldest` switches to FIFO when you are draining a
backlog rather than triaging one.

Filters worth knowing:

- `?slaBreached=true` — the hold window expired before the model answered. These
  are at the top for a reason: the author has been waiting the longest and the
  system has admitted it could not decide.
- `?entityType=post` — one surface at a time. `post`, `post_comment`, `story`,
  `research`, `qna_question`, `qna_answer`, `chat_message`, `channel`,
  `stream_meta`, `live_chat`, `content_annotation`.
- `?status=PENDING` — still inside its hold window. Usually you should not need
  this; if it is large and not draining, the inference container is down.

### The detail screen

`GET /moderation/review/{caseId}` gives you every field separately, each with its
own scores. Fields are scored independently on purpose — "the third tag", not
"the post" — so a clean 2,000-word article body cannot dilute one abusive tag
below every threshold.

The `thresholds` block shows the bands **in force for that entity type**, so you
can see why the item landed in the middle instead of being decided automatically.
A score of 0.42 `insult` against a band of `low 0.30 / high 0.80` is a genuine
"could go either way"; the same 0.42 against `low 0.15 / high 0.50` for `threat`
is nearly a block.

Also on the row: `authorPriorRejections`. One borderline comment from an account
with fourteen prior rejections is a different decision from the same comment by a
first-time poster.

**Chat and live-chat cases show no text.** DM and message bodies are redacted in
this queue — you get the scores, the field name and the band, never the message.
That is the platform's content-privacy boundary, and these types are meant to
auto-decide; they only reach you when the model was unsure or unavailable.
`teachModel` is ignored for them for the same reason.

### Deciding

```
POST /moderation/review/{caseId}/decide
{ "action": "APPROVE" | "REJECT", "reason": "…", "teachModel": true }
```

No step-up re-auth — you work this queue continuously and re-authenticating per
item would make the tool unusable. The **bulk** endpoint does require step-up,
because it can move a hundred pieces of content in one call.

`teachModel: true` is the single highest-leverage thing in this whole system.
It writes the reviewed text into the training dataset with labels derived from
your decision, tagged `ADMIN_CORRECTION` when you disagreed with the model and
`REVIEW_PROMOTION` when you confirmed it. Use it whenever the model was clearly
wrong. Do not use it when you approved something for a reason the text alone does
not explain (context, the author's history, a private agreement) — the model will
only ever see the text, and teaching it "this sentence is fine" when it was fine
*for other reasons* makes it worse.

Labels from a decision are coarse by design: a rejection is recorded as `toxic`
only, because a decision cannot say *which* of the six labels applied. Refine it
in the dataset browser if you know better.

### Bulk

```
POST /moderation/review/bulk
{ "action": "APPROVE", "caseIds": [...], "reason": "…", "teachModel": false }
```

§12.1's intended use is "approve everything with max score below 0.4" after
you have spot-checked a sample. Every target is attempted independently and the
response reports per-case outcome — one failure does not abort the batch.

---

## 3. The blocklist: the fast lever

`/api/v1/admin/content/blocklist` (pre-existing, unchanged).

Two levers exist and they operate on different timescales:

| | Blocklist | Model retrain |
|---|---|---|
| Effect | immediate, next request | after a training run and a human promote |
| Precision | exact/normalized string match | learned, generalises |
| Use for | a new slur trending *right now* | durable improvement |

`hard_block` rejects the content and the model is never called for that field —
saving latency and inference load. `soft_flag` forces review even when the model
would have approved: right for context-dependent words (medical terms, reclaimed
slurs) where a human should look but a block would be wrong.

Adding a word to the blocklist does **not** add it to the training set, and
vice-versa. Do both when you want the ban now and the generalisation later.

---

## 4. Tuning thresholds

`GET /moderation/settings` shows overrides, the fully resolved effective policy,
and model health in one call. Read the `effective` block — it is what is actually
being enforced, which is not necessarily what anyone configured.

### Before you change anything: dry-run it

```
POST /moderation/settings/dry-run
{ "entityType": "post",
  "labels": { "insult": { "low": 0.40 } },
  "caseIds": [ … recent cases … ] }
```

This replays the proposed bands against score vectors already stored on past
cases. Nothing is written and no model call is made. You get back exactly which
decisions would flip, and in which direction. Tuning a threshold without this is
a guess whose blast radius you discover in production.

### Then apply

```
PUT /moderation/settings/thresholds
{ "entityType": "research", "labels": { "insult": { "low": 0.45, "high": 0.85 } } }
```

Omit `entityType` for the global default. A per-type override wins over the
global one for that label — that is how §8.1's "academic critique legitimately
uses sharper language" gets expressed without loosening `threat` anywhere.

**Keep `threat` and `identity_hate` strict everywhere.** Their defaults are lower
than the rest (`0.15/0.50` and `0.15/0.55`) because a false negative on either
costs far more than a false positive. If you are raising them, be sure you know
why.

Threshold changes are live within 30 seconds on every node, immediately on the one
that served the request. Step-up re-auth is required.

### Hold durations and fallback

```
PUT /moderation/settings/hold-durations
{ "entityType": "story", "holdMs": 15000, "fallback": "FAIL_OPEN_SHADOW" }
```

`holdMs` is a **maximum wait, not a delay**. Clean content clears in well under a
second and never waits this long. The ceiling exists so a slow or failed check
has a bounded, defined outcome instead of an indefinite one.

`fallback` decides what happens when the ceiling is hit with no verdict:

- `FAIL_CLOSED` — hidden, top of the queue, SLA-breached flag. The right default.
- `FAIL_OPEN_SHADOW` — published and flagged. Only for short-lived, low-risk
  types. Stories ship with this because a 24-hour story that spends its life in a
  queue has effectively been deleted.

---

## 5. Teaching the model

`/moderation/model/training-examples`

**Add a sentence** — text plus whichever of the six labels apply. This is the
highest-quality input the dataset takes.

**Add a word** — `POST /training-examples/word`. The word is expanded server-side
into a few template sentences, because a bare token teaches a sentence-level
classifier almost nothing. The response lists every row created, so you can see
what was actually stored. For an instant ban, add it to the blocklist too.

**Bulk import (CSV)** — `POST /training-examples/import`. Curate sentences or
a word list in Excel, export **CSV UTF-8**, upload. Word rows can carry
`blocklist=yes` to get the instant ban and the training signal in one pass.
Validate first with `dryRun=true`; the column contract is in
[`../admin/trust-safety/automated-moderation.md`](../admin/trust-safety/automated-moderation.md) §4.

**Golden cases** — `/moderation/model/golden-cases`. A fixed, hand-reviewed set
every candidate model must still get right before promotion. These are **never
trained on**: training on them would make the suite measure memorisation instead
of generalisation, and it would stop catching the thing it exists to catch. Good
golden cases are the ones that were hard: leetspeak evasion, reclaimed slurs used
correctly, medical terminology, sarcasm you decided was fine.

**Probe the live model** — `POST /moderation/model/score-probe {"text": "…"}`.
The fastest answer to "why did this get through?" without digging through cases.

---

## 6. Retraining and promotion

```
POST /moderation/model/retrain   { "notes": "…" }     (step-up)
GET  /moderation/model/versions
POST /moderation/model/versions/{id}/promote          (step-up)
POST /moderation/model/rollback                       (step-up)
```

Retrain **on demand**, when there is a meaningful batch of new examples — the
`dataset.untrained` counter on the metrics endpoint tells you how many. Fifty-odd
is a reasonable trigger; a small platform will not generate enough daily labeled
data to justify a nightly schedule.

The training container must be running:
`docker compose --profile on-demand up model-training`.

A run goes `TRAINING → EVALUATING → READY`. Then a human promotes it. Never
automatically — that is §12.4 and it is not negotiable.

### The promotion gate

`READY` versions carry `gatePassed` and `gateDetail`. The gate fails when:

- any golden regression case fails, or
- any per-label F1 dropped more than `retrain.max-f1-drop` (default 0.02) versus
  the currently active version.

Labels the candidate never evaluated — no positives in the validation fold — are
skipped rather than scored zero. Comparing against a metric that was never
measured would fail every gate on a small dataset for no real reason.

A version whose gate failed is still `READY`, not `FAILED`: the run worked, the
numbers are the evidence, and a human decides. `{"force": true}` promotes anyway
and the override is recorded in the audit trail with your id on it.

Promotion calls `POST /v1/reload` on the inference container **before** flipping
the registry. If the container refuses the artifact, the database never claims it
is live.

### Rollback

`POST /moderation/model/rollback` re-promotes the most recently retired version.
Because every artifact stays in the shared volume, this never depends on
re-running training. It is the safety valve for a retrain that regressed on
something the gate did not catch.

---

## 7. Reading the metrics

`GET /moderation/review/metrics?windowHours=24`

The number to watch is `bands.autoDecidedPercent`. A healthy system settles the
large majority of traffic without a human. If `sentToReview` is creeping up,
either the thresholds are too tight or the model has drifted — and
`labels` tells you which label is doing it.

`sla` per entity type shows what fraction cleared inside the hold window.
Sustained breaches on one type mean either the inference container is
under-provisioned or that type's inline budget is too tight for its text length.

`model.registryInSync: false` means the artifact the container is serving is not
the one the registry calls active. Someone rolled the container by hand, or a
promote never reached it. Re-promote to fix.

---

## 8. Permissions

Per §12.6:

| | Review queue | Blocklist | Add training examples | Retrain | Promote / rollback | Thresholds | Kill switch |
|---|---|---|---|---|---|---|---|
| MODERATOR | ✅ | ✅ | ✅ | ❌ | ❌ | ✅ | ❌ |
| ANALYST | read metrics only | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| ADMIN | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

Step-up re-auth is required for: bulk decisions, threshold and hold changes,
retrain, promote, rollback, raw setting writes, and reset.

Every mutation writes an `ADMIN_MODERATION_*` audit row with your user id. There
are no anonymous moderation actions.

---

## 9. Before this goes live for real users

Roadmap §18 is blunt about this and so is this guide: **the seed dataset is on
the order of two dozen hand-written examples.** That is enough to prove the
pipeline end to end. It is not enough to trust for real auto-moderation.

Before relying on automated decisions:

1. Expand the training set with an established labeled corpus using the same six
   labels (e.g. Jigsaw Toxic Comment Classification), plus your own corrections.
2. Retrain, and read the per-label metrics — not just macro-F1.
3. Set the thresholds from that validation set, not from the bootstrap defaults.
4. Build a golden set from the cases that were genuinely hard.
5. Run for a while with `IN_REVIEW` volume deliberately high (tight `low`
   thresholds) so humans see most of the borderline traffic, and watch the
   agreement rate before loosening.

The model is **English-only**, inherited from the base checkpoint. For
non-English content it will under-detect, and that is a false-negative risk, not
a false-positive one. Flag it before launching in a non-English market.

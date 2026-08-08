# Admin API Reference — Automated Moderation

Complete request/response reference for the three controllers of the automated
text-moderation system:

| Controller | Base path | Endpoints | Concepts |
|---|---|---|---|
| `AdminAutoModerationController` | `/api/v1/admin/moderation/review` | 6 | [Review queue](../trust-safety/automated-moderation.md) |
| `AdminModerationSettingsController` | `/api/v1/admin/moderation/settings` | 8 | [Threshold tuning](../trust-safety/automated-moderation.md) |
| `AdminModerationModelController` | `/api/v1/admin/moderation/model` | 16 | [Dataset & model registry](../trust-safety/automated-moderation.md) |

**30 endpoints.** The reactive reports inbox (`AdminModerationController`,
3 endpoints on `/api/v1/admin/moderation/{queue,bulk}`) is a *different* surface
and lives in [content-moderation.md](content-moderation.md) — see
[Two queues, not one](../trust-safety/README.md#two-queues-not-one).

Subsystem design: [`../../moderation/`](../../moderation/README.md).
UI wiring: [`../frontend/README.md`](../frontend/README.md).

**Conventions used throughout:**

- **Auth.** Bearer JWT. Review + settings accept `ADMIN` **or** `MODERATOR`;
  model/registry mutations are `ADMIN`-only, with the dataset reads/writes
  widened to `MODERATOR` (a moderator's corrections are the highest-value
  training signal). Per-endpoint overrides are stated inline.
- **Step-up.** Endpoints marked **step-up** need a fresh re-auth marker armed by
  `POST /api/v1/security/step-up`; absent/expired → `403 STEP_UP_REQUIRED`.
  Single-case decisions deliberately do **not** require it — a moderator works
  this queue continuously and re-auth per item would make it unusable.
- **Serialization.** `QueueRow`, `FieldView`, `CaseDetail`, `VersionView` and
  `ExampleView` are `@JsonInclude(NON_NULL)` — null fields are **omitted**.
  Timestamps are `LocalDateTime` (`"2026-08-08T14:30:00"`).
- **Page sizes** clamp to 1–100 (`Pages.clamp`).
- **Errors** use the canonical envelope; codes are catalogued in
  [`../../errors/user-facing-messages.md`](../../errors/user-facing-messages.md) §1.103a.

---

## Shared shapes

### `QueueRow`

Returned by the queue list, the detail summary, and every decision response.

```json
{
  "caseId": "6b1f0e2a-3c4d-4e5f-8a9b-0c1d2e3f4a5b",
  "entityType": "POST",
  "entityLabel": "post",
  "entityRef": "9f2a4c1e-8b7d-4a6f-9c3e-1d2b3a4c5d6e",
  "parentRef": null,
  "authorId": "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed",
  "status": "IN_REVIEW",
  "reasonCode": "MODEL",
  "topLabel": "insult",
  "topScore": 0.4213,
  "blocklistHit": null,
  "slaBreached": false,
  "modelVersion": "v3",
  "submittedAt": "2026-08-08T14:30:00",
  "holdDeadline": "2026-08-08T14:30:30",
  "decidedAt": null,
  "preview": "first 200 chars of the first field…",
  "authorPriorRejections": 2
}
```

| Field | Notes |
|---|---|
| `entityType` | `POST`, `POST_COMMENT`, `STORY`, `STORY_POLL`, `RESEARCH`, `RESEARCH_COMMENT`, `QNA_QUESTION`, `QNA_ANSWER`, `CHAT_MESSAGE`, `CHANNEL`, `STREAM_META`, `LIVE_CHAT`, `CONTENT_ANNOTATION` |
| `entityLabel` | Human label for the same thing (`"post"`, `"comment"`, `"research paper"`, …) — safe to render directly |
| `entityRef` | The content's own id. A UUID string for most types, a 64-bit snowflake for `CHAT_MESSAGE` — **treat as an opaque string** |
| `status` | `PENDING` \| `APPROVED` \| `REJECTED` \| `IN_REVIEW` |
| `reasonCode` | `MODEL` \| `BLOCKLIST` \| `SLA_BREACH` \| `ADMIN` \| `INFERENCE_UNAVAILABLE` \| `DISABLED` \| `NO_TEXT` \| `SUPERSEDED` |
| `topLabel` | The label that **drove the verdict**, not simply the highest number — a `threat` at 0.55 crossing its 0.50 bar outranks an `insult` at 0.70 that did not cross its 0.80 bar |
| `slaBreached` | The hold window expired before an automated verdict. Sort these first |
| `preview` | First 200 chars of the first field. **Redacted** to `"[private message — body withheld from staff by policy]"` for `CHAT_MESSAGE` and `LIVE_CHAT` |
| `authorPriorRejections` | Count of this author's previously rejected cases — the repeat-offender signal |

### `FieldView`

One text field of a case, with the raw scores that decided it.

```json
{
  "fieldName": "body",
  "text": "the full submitted text",
  "verdict": "REVIEW",
  "topLabel": "insult",
  "topScore": 0.4213,
  "scores": {
    "toxic": 0.31, "severe_toxic": 0.01, "obscene": 0.04,
    "threat": 0.00, "insult": 0.42, "identity_hate": 0.01
  },
  "blocklistHit": null
}
```

Fields are scored **independently** so the queue can say *which* field tripped —
repeated fields carry an index suffix (`tag[0]`, `tag[1]`, `alt_text[2]`). The
entity-level verdict is the worst of them.

`verdict` ∈ `APPROVE | REVIEW | REJECT`. `text` is **redacted** for
`CHAT_MESSAGE`/`LIVE_CHAT` — see [Privacy](#privacy) below.

---

## Review queue

### GET /api/v1/admin/moderation/review

The proactive queue: content the classifier could not settle on its own.

**Access**: `ADMIN` or `MODERATOR`.

**Params**

| Param | Type | Default | Notes |
|---|---|---|---|
| `status` | `ModerationStatus` | `IN_REVIEW` | `PENDING` \| `APPROVED` \| `REJECTED` \| `IN_REVIEW` |
| `entityType` | string | all | Lowercase key, e.g. `post`, `post_comment`, `chat_message` |
| `slaBreached` | boolean | — | `true` filters to hold-window breaches; overrides `entityType` |
| `sort` | string | `risk` | `risk` = `maxScore` desc (default — spend attention where the model was least sure) · `oldest` = FIFO for draining a backlog |
| `page` / `pageSize` | int | `0` / `50` | Clamped 1–100 |

**Response**: `200`

```json
{
  "items": [ { "caseId": "…", "entityType": "POST", "…": "…" } ],
  "page": 0,
  "pageSize": 50,
  "totalElements": 137,
  "totalPages": 3,
  "counts": { "inReview": 137, "pending": 4, "slaBreached": 11 }
}
```

`counts` is live queue depth, independent of the current filter — use it for the
header tiles so they don't change as the operator filters.

**Errors**
- `INVALID_MODERATION_SETTING` — 400 — unknown `status` or `entityType`.

### GET /api/v1/admin/moderation/review/{caseId}

The review screen: every field, its raw scores, and the bands in force.

**Access**: `ADMIN` or `MODERATOR`.

**Response**: `200` — `CaseDetail`

```json
{
  "summary": { "caseId": "…", "…": "… a QueueRow …" },
  "fields": [ { "fieldName": "body", "…": "… a FieldView …" } ],
  "thresholds": {
    "entityType": "post",
    "bands": {
      "toxic":         { "low": 0.30, "high": 0.80 },
      "severe_toxic":  { "low": 0.20, "high": 0.60 },
      "obscene":       { "low": 0.30, "high": 0.80 },
      "threat":        { "low": 0.15, "high": 0.50 },
      "insult":        { "low": 0.30, "high": 0.80 },
      "identity_hate": { "low": 0.15, "high": 0.55 }
    },
    "holdMs": 30000,
    "fallback": "FAIL_CLOSED"
  }
}
```

`thresholds.bands` are the cut points **actually applied to this entity type**
(a per-type override wins over the global band). Render each score against its
band — a 0.42 `insult` inside `0.30/0.80` reads completely differently from a
0.42 `threat` inside `0.15/0.50`, and the band is the only thing that makes the
number meaningful.

**Errors**
- `MODERATION_CASE_NOT_FOUND` — 400 — unknown case id.

### POST /api/v1/admin/moderation/review/{caseId}/decide

Approve or reject one case.

**Access**: `ADMIN` or `MODERATOR`. **No step-up** — deliberate; see conventions.

**Request body** (`DecisionRequest`, `@Valid`)

```json
{
  "action": "APPROVE",
  "reason": "Academic critique, not abuse. ≤500 chars.",
  "teachModel": true
}
```

| Field | Notes |
|---|---|
| `action` | `APPROVE` \| `REJECT` (case-insensitive). Anything else → `INVALID_MODERATION_ACTION` |
| `reason` | Optional, ≤500. Stored on the case and in the decision log |
| `teachModel` | Optional. `true` writes the reviewed text into `moderation_training_examples`, sourced `ADMIN_CORRECTION` when the decision contradicted the model and `REVIEW_PROMOTION` when it confirmed it. **Ignored for `CHAT_MESSAGE`/`LIVE_CHAT`** |

**Response**: `200` — the updated `QueueRow`.

**What actually happens.** The verdict is applied to the real content: an
approval publishes it (fan-out, search indexing, notifications — everything the
create path deferred), a rejection hides it and notifies the author. Reversing an
inline-approved decision works too — the case's applied marker is cleared so the
applier re-runs.

**Errors**
- `INVALID_MODERATION_ACTION` — 400 — action other than APPROVE/REJECT.
- `MODERATION_CASE_NOT_FOUND` — 400 — unknown case id.

### POST /api/v1/admin/moderation/review/bulk

Bulk approve/reject — §12.1's "approve everything with max score below 0.4"
after spot-checking a sample.

**Access**: `ADMIN` or `MODERATOR` + **step-up**. This is the one endpoint that
can publish or bury a hundred pieces of content in a single call.

**Request body** (`BulkDecisionRequest`, `@Valid`)

```json
{
  "action": "APPROVE",
  "caseIds": ["6b1f0e2a-…", "7c2a1f3b-…"],
  "reason": "Batch cleared after sampling 10.",
  "teachModel": false
}
```

`caseIds` is 1–100 (`@Size`). Each target is attempted independently.

**Response**: `200` — per-case outcome; one failure does not abort the batch.

```json
[
  { "caseId": "6b1f0e2a-…", "outcome": "ok" },
  { "caseId": "7c2a1f3b-…", "outcome": "error", "error": "Moderation case not found: 7c2a1f3b-…" }
]
```

**Errors**
- `INVALID_MODERATION_ACTION` — 400 — bad `action` (top-level, aborts).
- `STEP_UP_REQUIRED` — 403 — no fresh step-up marker.

### POST /api/v1/admin/moderation/review/{caseId}/rescore

Re-runs the classifier against the stored text. Useful right after promoting a
model version, or when the inference container was down when the case was filed.

**Access**: `ADMIN` or `MODERATOR`.

**Response**: `200`

```json
{ "caseId": "6b1f0e2a-…", "status": "APPROVED" }
```

`status` is `"GONE"` when the case no longer exists. A case still inside its
two-second write-grace window returns `PENDING` unchanged — that guard stops a
verdict being applied before the content row it targets has been written.

### GET /api/v1/admin/moderation/review/metrics

The §12.5 analytics panel in one call.

**Access**: `ADMIN`, `MODERATOR` **or** `ANALYST` (observability is read-only for analysts).

**Params**: `windowHours` (int, default `24`).

**Response**: `200`

```json
{
  "windowHours": 24,
  "enabled": true,
  "queue":  { "inReview": 137, "pending": 4, "slaBreached": 11 },
  "volume": {
    "submitted": 8421,
    "byEntityType": { "post": { "APPROVED": 3100, "IN_REVIEW": 44 } }
  },
  "bands": {
    "autoApproved": 7900, "autoRejected": 190, "sentToReview": 300,
    "decidedByHuman": 31, "autoDecidedPercent": 96.1
  },
  "labels": [ { "label": "insult", "count": 210, "avgScore": 0.4412 } ],
  "sla": [ { "entityType": "post", "total": 3400, "breached": 12, "withinSlaPercent": 99.6 } ],
  "model": {
    "inferenceUp": true, "inferenceError": null, "residentVersion": "v3",
    "circuit": "CLOSED", "calls": 8421, "failures": 3, "avgLatencyMs": 41,
    "lastError": null, "trainingUp": false, "registryInSync": true,
    "activeVersion": { "version": "v3", "macroF1": 0.82, "promotedAt": "2026-08-07T09:00:00", "trainingExamples": 1780 }
  },
  "dataset": { "examples": 1840, "untrained": 62, "goldenCases": 45 }
}
```

**The numbers that matter operationally:**

| Signal | Healthy | What a bad value means |
|---|---|---|
| `bands.autoDecidedPercent` | high and stable | a sudden drop = drift, or a bad model promote |
| `queue.inReview` | flat | sustained growth = thresholds too tight, or the model is down |
| `queue.slaBreached` | near zero | the hold window is expiring before verdicts arrive |
| `sla[].withinSlaPercent` | ~100 | that entity type's inline budget is too tight for its text length |
| `model.circuit` | `CLOSED` | `OPEN` = the client is failing fast; content is on fallback policy |
| `model.registryInSync` | `true` | `false` = the container serves a different artifact than the registry calls active |

---

## Settings — runtime policy

This is the surface that makes the whole architecture worth its split: the model
container knows nothing about thresholds, so changing platform strictness is one
row in `moderation_settings` — no redeploy of either service, no retrain.

### GET /api/v1/admin/moderation/settings

**Access**: `ADMIN` or `MODERATOR`.

**Response**: `200`

```json
{
  "overrides": { "threshold.threat.high": "0.45" },
  "effective": {
    "enabled": true,
    "livechat.buffer.ms": 3000,
    "livechat.borderline.hidden": true,
    "retrain.max-f1-drop": 0.02,
    "retrain.require-human-promote": true,
    "entityTypes": {
      "post": {
        "enabled": true, "holdMs": 30000, "inlineMs": 2000,
        "fallback": "FAIL_CLOSED", "ephemeral": false,
        "thresholds": { "toxic": { "low": 0.30, "high": 0.80 } }
      }
    }
  },
  "model": { "inferenceUp": true, "…": "… same shape as metrics.model …" },
  "warning": "optional — present only when something needs attention"
}
```

`overrides` = what an admin changed. `effective` = what is **actually being
enforced** right now (overrides layered on the yaml bootstrap). Read `effective`
when displaying policy; read `overrides` when showing "what's been customised".

`warning` appears when moderation is disabled (`WARN_MODERATION_DISABLED`) or the
inference container is unreachable (`WARN_INFERENCE_DOWN`).

### GET /api/v1/admin/moderation/settings/thresholds

**Access**: `ADMIN` or `MODERATOR`. **Params**: `entityType` (default `post`).

```json
{
  "entityType": "post",
  "bands": { "toxic": { "low": 0.30, "high": 0.80 }, "…": "…" }
}
```

### PUT /api/v1/admin/moderation/settings/thresholds

**Access**: `ADMIN` or `MODERATOR` + **step-up**.

**Request body** (`ThresholdPatch`, `@Valid`) — partial; omit `entityType` for
the global default, or name one to scope the override to that type only.

```json
{
  "entityType": "research",
  "labels": {
    "insult": { "low": 0.45, "high": 0.85 }
  }
}
```

`low` and `high` are independently optional. A per-type override wins over the
global band for that label — that is how "academic critique legitimately uses
sharper language" gets expressed without loosening `threat` anywhere.

**Response**: `200` — `{ "applied": { …written keys… }, "effective": { … } }`

**Errors**
- `INVALID_THRESHOLD` — 400 — outside `[0,1]`, NaN/Infinite, or `high < low`
  (which would collapse the review band and turn every borderline case into a block).
- `INVALID_MODERATION_SETTING` — 400 — unknown label or entity type.
- `STEP_UP_REQUIRED` — 403.

### PUT /api/v1/admin/moderation/settings/hold-durations

Hold ceilings, inline budgets, fallback policy and per-type enablement.

**Access**: `ADMIN` or `MODERATOR` + **step-up**.

```json
{
  "entityType": "story",
  "holdMs": 15000,
  "inlineMs": 1000,
  "fallback": "FAIL_OPEN_SHADOW",
  "enabled": true
}
```

| Field | Notes |
|---|---|
| `holdMs` | **Maximum wait, not a delay** — clean content clears in well under a second and never reaches it. Must be 500–600 000 |
| `inlineMs` | Synchronous budget on the request thread. Clamped server-side below half the hold ceiling so the sweeper can't fire while a request is still waiting |
| `fallback` | `FAIL_CLOSED` (hidden + queued + SLA flag — the right default) or `FAIL_OPEN_SHADOW` (published + flagged; only for short-lived low-risk types like stories) |
| `enabled` | Per-type kill switch |

Every field optional, but the patch must not be empty.

**Response**: `200` — `{ "applied": …, "effective": … }`

**Errors**: `INVALID_MODERATION_SETTING` (bad type/fallback/empty patch), `STEP_UP_REQUIRED`.

### POST /api/v1/admin/moderation/settings/dry-run

**Replays proposed thresholds against already-stored scores.** Writes nothing,
calls no model — the raw score vectors are already on `moderation_case_fields`,
which is exactly why they are stored.

**Access**: `ADMIN` or `MODERATOR`. No step-up (it changes nothing).

```json
{
  "entityType": "post",
  "labels": { "insult": { "low": 0.40 } },
  "caseIds": ["6b1f0e2a-…", "7c2a1f3b-…"]
}
```

`caseIds` ≤ 500. Labels you omit keep their current band.

**Response**: `200`

```json
{
  "entityType": "post",
  "evaluated": 340,
  "unchanged": 312,
  "changed": [
    {
      "caseId": "6b1f0e2a-…",
      "field": "body",
      "before": "REVIEW",
      "after": "APPROVE",
      "topLabel": "insult",
      "topScore": 0.37
    }
  ]
}
```

**Wire this into the threshold editor before the save button.** Shipping a
threshold slider without it means every tune is a guess with a production blast
radius.

### PUT /api/v1/admin/moderation/settings/raw

Escape hatch for any key in the namespace, including the master `enabled` switch.

**Access**: `ADMIN` only + **step-up**. (Turning moderation off for a whole
entity type is a different order of decision from nudging a cut point.)

```json
{ "key": "enabled.chat_message", "value": "false" }
```

Key namespace: `enabled`, `enabled.<type>`, `threshold.<label>.low|high`,
`threshold.<type>.<label>.low|high`, `hold.<type>.ms`, `inline.<type>.ms`,
`fallback.<type>`, `livechat.buffer.ms`, `livechat.borderline.hidden`,
`retrain.max-f1-drop`, `retrain.require-human-promote`.

**Response**: `200` — `{ "overrides": { … } }`

### DELETE /api/v1/admin/moderation/settings/raw/{key}

Removes one override, reverting that key to its yaml bootstrap value.
**Access**: `ADMIN` + **step-up**. **Response**: 204.

### POST /api/v1/admin/moderation/settings/reset

Drops **every** override, returning the platform to the `application.yaml`
bootstrap policy. **Access**: `ADMIN` + **step-up**.

**Response**: `200` — `{ "effective": { … } }`

---

## Training data

The "teach it new words and sentences" surface — §12.3, and the reason the
system gets better over time instead of staying frozen at its seed dataset.

### GET /api/v1/admin/moderation/model/training-examples

**Access**: `ADMIN` or `MODERATOR`.

**Params**: `source` (optional — `SEED_DATASET` \| `ADMIN_MANUAL` \|
`ADMIN_CORRECTION` \| `REVIEW_PROMOTION` \| `USER_REPORT_CONFIRMED` \|
`ADMIN_IMPORT`), `page`, `pageSize`.

```json
{
  "items": [
    {
      "id": "2c3d4e5f-…",
      "text": "…",
      "labels": {
        "toxic": 1, "severe_toxic": 0, "obscene": 0,
        "threat": 0, "insult": 1, "identity_hate": 0
      },
      "source": "ADMIN_CORRECTION",
      "note": "from review of POST",
      "trainedInVersion": "v3",
      "addedAt": "2026-08-08T11:04:00"
    }
  ],
  "page": 0, "pageSize": 50, "totalElements": 1840,
  "summary": {
    "total": 1840,
    "untrained": 62,
    "goldenCases": 45,
    "labelTotals": { "toxic": 640, "severe_toxic": 88, "obscene": 210, "threat": 47, "insult": 502, "identity_hate": 61 },
    "bySource": { "ADMIN_MANUAL": 300, "ADMIN_CORRECTION": 190, "REVIEW_PROMOTION": 1350, "SEED_DATASET": 0, "USER_REPORT_CONFIRMED": 0 }
  }
}
```

`summary.untrained` is the retrain trigger — it counts rows not yet folded into
a completed run.

### POST /api/v1/admin/moderation/model/training-examples

Add one labeled sentence — the highest-quality input the dataset takes.

**Access**: `ADMIN` or `MODERATOR`.

```json
{
  "text": "You are a complete waste of everyone's time.",
  "labels": { "toxic": 1, "insult": 1 },
  "note": "Common phrasing the model was missing."
}
```

Absent labels default to 0. Re-posting the **same text updates its labels**
rather than duplicating — dedup is a normalised SHA-256, so an accidentally
duplicated example can't silently double its own training weight.

**Response**: `201` — `ExampleView`.

**Errors**: `INVALID_TRAINING_EXAMPLE` — 400 — blank text.

### POST /api/v1/admin/moderation/model/training-examples/word

Add a single word. Expanded **server-side** into template sentences, because a
bare token teaches a sentence-level classifier almost nothing.

**Access**: `ADMIN` or `MODERATOR`.

```json
{ "word": "…", "labels": { "insult": 1 }, "note": "trending slur" }
```

**Response**: `201`

```json
{
  "word": "…",
  "created": [ { "id": "…", "text": "You are a ….", "…": "…" } ],
  "note": "The word was expanded into template sentences. For an instant, no-retrain ban add it to the blocklist as well."
}
```

The response lists every row created so the admin can see what was actually
stored rather than guessing. **For an immediate ban, add it to the blocklist
too** — that's the fast lever; this is the slow one.

**Errors**: `INVALID_TRAINING_EXAMPLE` — 400 — blank word.

### DELETE /api/v1/admin/moderation/model/training-examples/{id}

Remove a bad row before it gets trained on. **Access**: `ADMIN`. **Response**: 204.

### POST /api/v1/admin/moderation/model/training-examples/import

Bulk import of training data from an admin-curated file. CSV UTF-8 is the
wire format (author in Excel, export as **CSV UTF-8**; native `.xlsx` is
blocked on the offline-build dependency rule). File-format contract — columns,
dedup, quality rules:
[../trust-safety/automated-moderation.md §4](../trust-safety/automated-moderation.md).

**Access**: `ADMIN` or `MODERATOR` + **step-up**.

**Request**: `multipart/form-data`

| Part | Type | Notes |
|---|---|---|
| `file` | file | The CSV; ≤ 5 000 data rows |
| `kind` | string | `sentences` \| `words` |
| `dryRun` | boolean | `true` = validate only, write nothing (default `false`) |
| `allowPartial` | boolean | `false` (default) = all-or-nothing; `true` = apply clean rows, report rejects |

**Response**: `200`

```json
{
  "kind": "words",
  "totalRows": 412,
  "applied": 409,
  "updated": 31,
  "blocklistAdded": 87,
  "trainingRowsCreated": 2045,
  "dryRun": false,
  "errors": [
    { "row": 17, "error": "labels must be 0 or 1, got 'x' in column 'insult'" },
    { "row": 203, "error": "word is blank" }
  ]
}
```

`updated` counts rows whose normalised text already existed (labels updated,
not duplicated). For `kind=words`, `trainingRowsCreated` reflects the
server-side template expansion, and `blocklistAdded` the rows that carried
`blocklist=yes` (those bans are live immediately — no retrain needed).
Imported rows are tagged source `ADMIN_IMPORT` so a bad batch can be filtered
and deleted from the dataset browser.

Row-level problems never fail the call — they come back in `errors` with a
1-based data-row number. With the default all-or-nothing mode, any error means
`applied: 0` and nothing was written; with `allowPartial: true` the clean rows
are applied anyway. Validation always covers the entire file, so `dryRun=true`
is the safe preview to wire behind the upload button.

**Errors**: `INVALID_IMPORT_FILE` — 400 — unreadable/empty file, unknown or
missing column, unknown `kind`, > 5 000 rows, or an unterminated quoted field;
`STEP_UP_REQUIRED` — 403.

---

## Golden regression set

A fixed, hand-reviewed suite every candidate model must still get right before
promotion (§17). **Never trained on** — training on them would make the suite
measure memorisation instead of generalisation, and it would stop catching the
thing it exists to catch.

Good golden cases are the ones that were genuinely hard: leetspeak evasion,
reclaimed slurs used correctly, medical terminology, sarcasm you decided was fine.

### GET /api/v1/admin/moderation/model/golden-cases

**Access**: `ADMIN` or `MODERATOR`. **Params**: `page`, `pageSize`.
**Response**: `200` — array of `ModerationGoldenCase`
(`id`, `text`, the six label shorts, `note`, `addedBy`, `addedAt`).

### POST /api/v1/admin/moderation/model/golden-cases

**Access**: `ADMIN`. Same body shape as a training example.
**Response**: `201` — the saved case.

### DELETE /api/v1/admin/moderation/model/golden-cases/{id}

**Access**: `ADMIN`. **Response**: 204.

---

## Model registry

### GET /api/v1/admin/moderation/model/versions

**Access**: `ADMIN` or `ANALYST`. **Params**: `page`, `pageSize` (default 20).

```json
{
  "items": [
    {
      "id": "8a7b6c5d-…",
      "version": "v4",
      "status": "READY",
      "jobId": "3f9c1a2b4d5e6f70",
      "baseCheckpoint": "v3",
      "trainingExamples": 1780,
      "validationCount": 60,
      "macroF1": 0.84,
      "gatePassed": true,
      "gateDetail": "no per-label F1 drop beyond 0.020",
      "artifactPath": "/app/model/v4",
      "notes": "adds 190 corrections from the Aug backlog",
      "error": null,
      "trainedAt": "2026-08-08T10:00:00",
      "completedAt": "2026-08-08T10:41:12",
      "promotedAt": null
    }
  ],
  "totalElements": 4,
  "health": { "…": "… same shape as metrics.model …" }
}
```

`status` lifecycle: `TRAINING` → `EVALUATING` → `READY` → (`SHADOW`) → `ACTIVE`
→ `RETIRED`; `FAILED` on error.

### POST /api/v1/admin/moderation/model/retrain

Starts a fine-tune. Returns immediately — the job runs in the training container.

**Access**: `ADMIN` + **step-up**.

```json
{ "baseVersion": "v3", "notes": "adds the Aug correction backlog" }
```

Omit `baseVersion` to start from the currently active version.
**Requires the `model-training` container to be running**
(`docker compose --profile on-demand up -d model-training`).

**Response**: `202` — the registry row at `status: "TRAINING"`.

**Errors**
- `TRAINING_ALREADY_RUNNING` — 400 — a job is `TRAINING`/`EVALUATING`.
- `TRAINING_DATASET_TOO_SMALL` — 400 — below `app.moderation.retrain.min-examples`.
- `TRAINING_SERVICE_UNAVAILABLE` — 400 — `:8001` unreachable.
- `STEP_UP_REQUIRED` — 403.

### POST /api/v1/admin/moderation/model/retrain/refresh

Forces a status poll instead of waiting for the 15 s scheduler.

**Access**: `ADMIN`. **Response**: `200` — `{ "settled": 1 }` (how many jobs
reached a terminal state on this call).

### POST /api/v1/admin/moderation/model/train-callback

**Not staff-facing.** The training container's own completion webhook —
`permitAll()` at the method level, authenticated by the `X-Training-Token`
header against `app.moderation.training.callback-token`.

Polling covers the same ground, so a dropped webhook degrades to "settled a few
seconds later" rather than a stuck row.

**Response**: 204. **Errors**: `INVALID_CALLBACK_TOKEN` — 401.

### POST /api/v1/admin/moderation/model/versions/{id}/promote

Point the live inference container at this artifact.

**Access**: `ADMIN` + **step-up**.

```json
{ "force": false }
```

**Order of operations matters:** `POST /v1/reload` is called on the container
**before** the registry is flipped. If the container refuses the artifact, the
database never claims it is live.

The previously active version is moved to `RETIRED` (not deleted) — that is what
makes rollback a config flip rather than a retrain.

`force: true` overrides a failed promotion gate and is recorded in the audit
trail against your user id.

**Response**: `200` — the updated `VersionView`.

**Errors**
- `MODEL_NOT_PROMOTABLE` — 400 — status is not `READY`/`SHADOW`/`RETIRED`.
- `MODEL_GATE_FAILED` — 400 — golden regression failure or a per-label F1 drop
  beyond the limit, and `force` was not set.
- `INFERENCE_UNAVAILABLE` — 400 — the container refused or is unreachable.
- `MODEL_VERSION_NOT_FOUND` — 400.
- `STEP_UP_REQUIRED` — 403.

### POST /api/v1/admin/moderation/model/versions/{id}/shadow

Marks a candidate `SHADOW` — scored but not enforced. **Access**: `ADMIN`.
**Response**: `200` — the updated `VersionView`.

> Running an actual shadow replica pool is a deployment topology change, not
> application code. The status and the promote path are ready for it; the second
> pool is not wired. See [`../../moderation/architecture.md`](../../moderation/architecture.md) §6.

### POST /api/v1/admin/moderation/model/rollback

Re-promotes the most recently retired version — the safety valve for a retrain
that regressed on something the gate did not catch. Never depends on re-running
training, because every artifact stays in the shared volume.

**Access**: `ADMIN` + **step-up**. **Response**: `200` — the restored `VersionView`.

**Errors**: `MODEL_VERSION_NOT_FOUND` — 400 — no retired version exists.

### POST /api/v1/admin/moderation/model/score-probe

Score arbitrary text against the live model. The fastest answer to "why did this
get through?" without digging through cases, and the only place raw scores are
exposed for text that was never submitted.

**Access**: `ADMIN` or `MODERATOR`.

```json
{ "text": "any string, ≤5000 chars" }
```

**Response**: `200`

```json
{
  "modelVersion": "v3",
  "inferenceMs": 18.4,
  "scores": { "toxic": 0.04, "severe_toxic": 0.00, "obscene": 0.01, "threat": 0.00, "insult": 0.02, "identity_hate": 0.00 }
}
```

**Errors**: `INFERENCE_UNAVAILABLE` — 400 — `:8000` unreachable.

---

## Privacy

**Chat, DM and live-stream-chat bodies are never rendered to staff.** In every
response above, `FieldView.text` and `QueueRow.preview` come back as
`"[private message — body withheld from staff by policy]"` when
`entityType` is `CHAT_MESSAGE` or `LIVE_CHAT`. The scores, field names and
threshold bands are still returned, so a moderator can act on a held message
without ever reading it.

For the same reason `teachModel` is **silently ignored** for those types: a
private message entering the training set would leave the platform entirely —
shipped to the training container and baked into model weights — which is a far
larger disclosure than a screen.

This is the platform's absolute content-privacy boundary, not a
moderation-specific choice. See
[`../communication/README.md`](../communication/README.md).

---

## The two Python contracts

Internal-network only — **never expose these ports publicly**. Documented here
because the admin surface is what drives them.

**Inference** (`:8000`), `X-API-Key` when configured:

```
POST /v1/score          { "text": "…" }
                        → { "scores": {...}, "model_version": "v3", "inference_ms": 18 }
POST /v1/score/batch    { "items": [ { "id": "body", "text": "…" } ] }
                        → { "results": [ { "id": "body", "scores": {...} } ], "model_version": "v3" }
POST /v1/reload         { "version": "v4" }
                        → { "model_version": "v4", "previous_version": "v3", "source": "/app/model/v4" }
GET  /v1/model          → resident artifact info
GET  /healthz /readyz
```

**Training** (`:8001`), `X-API-Key` when configured:

```
POST /v1/train          { "examples": [...], "golden_set": [...],
                          "base_checkpoint": "v3", "callback_url": "…" }
                        → { "job_id": "…", "status": "queued" }
GET  /v1/train/{job_id} → queued | training | evaluating | done | failed
                          (+ per-label metrics, golden report)
GET  /v1/versions       → artifacts present in the shared volume
GET  /healthz /readyz
```

Container operations, failure modes and the safe-promotion checklist:
[`../../moderation/operations.md`](../../moderation/operations.md).

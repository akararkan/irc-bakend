# Moderation — API reference

Everything under `/api/v1/admin/moderation`. Errors use the platform's standard
envelope (`docs/errors/error-handling.md`); codes are listed in §7 below.

Conventions across the whole admin surface: no response envelope — endpoints
return the DTO directly. Pagination is `?page=0&pageSize=50`, clamped to 100.

---

## 1. Review queue (§12.1)

`@PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")`

### `GET /moderation/review`

| Param | Default | Notes |
|---|---|---|
| `status` | `IN_REVIEW` | `PENDING` \| `APPROVED` \| `REJECTED` \| `IN_REVIEW` |
| `entityType` | all | `post`, `post_comment`, `story`, `story_poll`, `research`, `research_comment`, `qna_question`, `qna_answer`, `chat_message`, `channel`, `stream_meta`, `live_chat`, `content_annotation` |
| `slaBreached` | — | `true` filters to hold-window breaches |
| `sort` | `risk` | `risk` (maxScore desc) \| `oldest` (FIFO) |
| `page` / `pageSize` | `0` / `50` | |

```json
{
  "items": [{
    "caseId": "…", "entityType": "POST", "entityLabel": "post",
    "entityRef": "9f2a…", "parentRef": null, "authorId": "…",
    "status": "IN_REVIEW", "reasonCode": "MODEL",
    "topLabel": "insult", "topScore": 0.4213, "blocklistHit": null,
    "slaBreached": false, "modelVersion": "v3",
    "submittedAt": "…", "holdDeadline": "…", "decidedAt": null,
    "preview": "first 200 chars of the first field…",
    "authorPriorRejections": 2
  }],
  "page": 0, "pageSize": 50, "totalElements": 137, "totalPages": 3,
  "counts": { "inReview": 137, "pending": 4, "slaBreached": 11 }
}
```

`reasonCode` is one of `MODEL`, `BLOCKLIST`, `SLA_BREACH`, `ADMIN`,
`INFERENCE_UNAVAILABLE`, `DISABLED`, `NO_TEXT`.

### `GET /moderation/review/{caseId}`

```json
{
  "summary": { … same shape as a queue row … },
  "fields": [{
    "fieldName": "body", "text": "the full submitted text",
    "verdict": "REVIEW", "topLabel": "insult", "topScore": 0.4213,
    "scores": { "toxic": 0.31, "severe_toxic": 0.01, "obscene": 0.04,
                "threat": 0.00, "insult": 0.42, "identity_hate": 0.01 },
    "blocklistHit": null
  }],
  "thresholds": {
    "entityType": "post",
    "bands": { "insult": { "low": 0.30, "high": 0.80 }, … },
    "holdMs": 30000, "fallback": "FAIL_CLOSED"
  }
}
```

Fields are scored independently (§5.4) — repeated fields get an index suffix
(`tag[0]`, `tag[1]`).

> **`text` and `preview` are redacted for `CHAT_MESSAGE` and `LIVE_CHAT`** —
> both render `"[private message — body withheld from staff by policy]"`. Scores,
> field names and the threshold band are still returned, so a moderator can act
> without reading private correspondence. `teachModel` is ignored for these types.

### `POST /moderation/review/{caseId}/decide`

```json
{ "action": "APPROVE" | "REJECT", "reason": "≤500 chars", "teachModel": true }
```

Returns the updated queue row. No step-up. `teachModel` writes the reviewed text
into `moderation_training_examples`, sourced `ADMIN_CORRECTION` when the decision
contradicted the model and `REVIEW_PROMOTION` when it confirmed it.

### `POST /moderation/review/bulk` — **step-up required**

```json
{ "action": "APPROVE", "caseIds": ["…", "…"], "reason": "…", "teachModel": false }
```

1–100 ids. Each target is attempted independently:

```json
[{ "caseId": "…", "outcome": "ok" },
 { "caseId": "…", "outcome": "error", "error": "…" }]
```

### `POST /moderation/review/{caseId}/rescore`

Re-runs the classifier on a stored case. Useful right after promoting a model.

```json
{ "caseId": "…", "status": "APPROVED" }
```

### `GET /moderation/review/metrics?windowHours=24`

`ADMIN` \| `MODERATOR` \| `ANALYST`. See [admin-guide.md §7](admin-guide.md).

```json
{
  "windowHours": 24, "enabled": true,
  "queue":  { "inReview": 137, "pending": 4, "slaBreached": 11 },
  "volume": { "submitted": 8421, "byEntityType": { "post": { "APPROVED": 3100, … } } },
  "bands":  { "autoApproved": 7900, "autoRejected": 190, "sentToReview": 300,
              "decidedByHuman": 31, "autoDecidedPercent": 96.1 },
  "labels": [{ "label": "insult", "count": 210, "avgScore": 0.4412 }],
  "sla":    [{ "entityType": "post", "total": 3400, "breached": 12,
               "withinSlaPercent": 99.6 }],
  "model":  { "inferenceUp": true, "residentVersion": "v3", "circuit": "CLOSED",
              "calls": 8421, "failures": 3, "avgLatencyMs": 41,
              "trainingUp": false, "registryInSync": true,
              "activeVersion": { "version": "v3", "macroF1": 0.82, … } },
  "dataset": { "examples": 1840, "untrained": 62, "goldenCases": 45 }
}
```

---

## 2. Settings (§8.1, §10.4)

`@PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")` unless noted.

### `GET /moderation/settings`

```json
{
  "overrides": { "threshold.threat.high": "0.45" },
  "effective": {
    "enabled": true,
    "livechat.buffer.ms": 3000, "livechat.borderline.hidden": true,
    "retrain.max-f1-drop": 0.02, "retrain.require-human-promote": true,
    "entityTypes": {
      "post": { "enabled": true, "holdMs": 30000, "inlineMs": 2000,
                "fallback": "FAIL_CLOSED", "ephemeral": false,
                "thresholds": { "toxic": { "low": 0.30, "high": 0.80 }, … } }
    }
  },
  "model": { … },
  "warning": "optional — set when moderation is disabled or the model is down"
}
```

### `GET /moderation/settings/thresholds?entityType=post`

### `PUT /moderation/settings/thresholds` — **step-up**

```json
{ "entityType": "research", "labels": { "insult": { "low": 0.45, "high": 0.85 } } }
```

Omit `entityType` for the global default. `low` and `high` are independently
optional. Validation: both in `[0,1]`, `high >= low`.

### `PUT /moderation/settings/hold-durations` — **step-up**

```json
{ "entityType": "story", "holdMs": 15000, "inlineMs": 1000,
  "fallback": "FAIL_OPEN_SHADOW", "enabled": true }
```

`holdMs` must be 500–600000. Every field is optional but the patch must not be
empty.

### `POST /moderation/settings/dry-run`

```json
{ "entityType": "post",
  "labels": { "insult": { "low": 0.40 } },
  "caseIds": ["…"] }
```

Replays proposed bands against **already-stored** score vectors. Writes nothing,
calls no model.

```json
{ "entityType": "post", "evaluated": 340, "unchanged": 312,
  "changed": [{ "caseId": "…", "field": "body",
                "before": "REVIEW", "after": "APPROVE",
                "topLabel": "insult", "topScore": 0.37 }] }
```

### `PUT /moderation/settings/raw` — **ADMIN, step-up**

Any key in the namespace, including `enabled`.

```json
{ "key": "enabled.chat_message", "value": "false" }
```

### `DELETE /moderation/settings/raw/{key}` — **ADMIN, step-up**
### `POST /moderation/settings/reset` — **ADMIN, step-up**

Drops every override, returning to the `application.yaml` bootstrap policy.

---

## 3. Training data (§12.3)

### `GET /moderation/model/training-examples`

`ADMIN` \| `MODERATOR`. `?source=ADMIN_MANUAL|ADMIN_CORRECTION|REVIEW_PROMOTION|SEED_DATASET|USER_REPORT_CONFIRMED|ADMIN_IMPORT`

```json
{
  "items": [{ "id": "…", "text": "…",
              "labels": { "toxic": 1, "severe_toxic": 0, "obscene": 0,
                          "threat": 0, "insult": 1, "identity_hate": 0 },
              "source": "ADMIN_CORRECTION", "note": "…",
              "trainedInVersion": "v3", "addedAt": "…" }],
  "totalElements": 1840,
  "summary": { "total": 1840, "untrained": 62, "goldenCases": 45,
               "labelTotals": { … }, "bySource": { … } }
}
```

### `POST /moderation/model/training-examples`

```json
{ "text": "…", "labels": { "toxic": 1, "insult": 1 }, "note": "…" }
```

Absent labels default to 0. Re-posting the same text updates its labels rather
than duplicating (dedup is on a normalized SHA-256 of the text).

### `POST /moderation/model/training-examples/word`

```json
{ "word": "…", "labels": { "insult": 1 }, "note": "…" }
```

Expanded server-side into template sentences; the response lists every row
created. For an instant ban add it to the blocklist as well.

### `DELETE /moderation/model/training-examples/{id}`

### `POST /moderation/model/training-examples/import` — **step-up**

`ADMIN` \| `MODERATOR`. Multipart: `file` (CSV UTF-8), `kind=sentences|words`,
`dryRun`, `allowPartial`. Bulk import of an admin-curated spreadsheet export;
`kind=words` rows with `blocklist=yes` also land on the platform blocklist in
the same pass (instant ban). Rows are tagged source `ADMIN_IMPORT`. Returns a
per-row error report; all-or-nothing unless `allowPartial`. Full column
contract: [`../admin/trust-safety/automated-moderation.md`](../admin/trust-safety/automated-moderation.md) §4,
wire shapes: [`../admin/api/automated-moderation.md`](../admin/api/automated-moderation.md).

### Golden set (§17) — `ADMIN` for writes

- `GET /moderation/model/golden-cases`
- `POST /moderation/model/golden-cases` — same body as a training example
- `DELETE /moderation/model/golden-cases/{id}`

---

## 4. Model registry (§12.4)

`@PreAuthorize("hasRole('ADMIN')")` unless noted.

### `GET /moderation/model/versions` — `ADMIN` \| `ANALYST`

```json
{
  "items": [{
    "id": "…", "version": "v4", "status": "READY", "jobId": "…",
    "baseCheckpoint": "v3", "trainingExamples": 1780, "validationCount": 60,
    "macroF1": 0.84, "gatePassed": true,
    "gateDetail": "no per-label F1 drop beyond 0.020",
    "artifactPath": "/app/model/v4", "notes": "…", "error": null,
    "trainedAt": "…", "completedAt": "…", "promotedAt": null
  }],
  "totalElements": 4,
  "health": { … same as metrics.model … }
}
```

Statuses: `TRAINING` → `EVALUATING` → `READY` → (`SHADOW`) → `ACTIVE` →
`RETIRED`; `FAILED` on error.

### `POST /moderation/model/retrain` — **step-up**

```json
{ "baseVersion": "v3", "notes": "…" }
```

`202` with the registry row. `baseVersion` omitted starts from the active
version. Refuses with `TRAINING_ALREADY_RUNNING` when one is in flight, and with
`TRAINING_DATASET_TOO_SMALL` below `app.moderation.retrain.min-examples`.

### `POST /moderation/model/retrain/refresh`

Forces a status poll instead of waiting for the scheduler.

### `POST /moderation/model/train-callback` — *container-authenticated*

Completion webhook from `model-training`. `permitAll` at the method level and
gated by `X-Training-Token` against `app.moderation.training.callback-token`.
Polling covers the same ground, so a dropped webhook degrades rather than stalls.

### `POST /moderation/model/versions/{id}/promote` — **step-up**

```json
{ "force": false }
```

Calls `POST /v1/reload` on the inference container **before** flipping the
registry. `force: true` overrides a failed promotion gate and is recorded in the
audit trail.

### `POST /moderation/model/versions/{id}/shadow`
### `POST /moderation/model/rollback` — **step-up**

Re-promotes the most recently retired version.

### `POST /moderation/model/score-probe` — `ADMIN` \| `MODERATOR`

```json
{ "text": "…" }
→ { "modelVersion": "v3", "inferenceMs": 18.4,
    "scores": { "toxic": 0.04, … } }
```

---

## 5. Pre-existing endpoints (unchanged)

Still there, still doing what they did:

- `GET /moderation/queue` — the reports/media/keyword inbox
- `POST /moderation/queue/keywords/{hitId}/resolve`
- `POST /moderation/bulk` — takedown/restore/delete over live content
- `/api/v1/admin/content/blocklist` — the deny-list manager

---

## 6. The Python contracts

Internal-network only — never expose these ports publicly (§15).

**Inference** (`:8000`), `X-API-Key` when configured:

```
POST /v1/score          { "text": "…" }
                        → { "scores": {...}, "model_version": "v3", "inference_ms": 18 }
POST /v1/score/batch    { "items": [ { "id": "body", "text": "…" } ] }
                        → { "results": [ { "id": "body", "scores": {...} } ], "model_version": "v3" }
POST /v1/reload         { "version": "v4" } → { "model_version": "v4", "previous_version": "v3" }
GET  /v1/model          → resident artifact info
GET  /healthz /readyz
```

**Training** (`:8001`), `X-API-Key` when configured:

```
POST /v1/train          { "examples": [...], "golden_set": [...],
                          "base_checkpoint": "v3", "callback_url": "…" }
                        → { "job_id": "…", "status": "queued" }
GET  /v1/train/{job_id} → queued | training | evaluating | done | failed  (+ metrics, golden)
GET  /v1/versions       → artifacts in the shared volume
GET  /healthz /readyz
```

---

## 7. Error codes

Registered in `ak.dev.irc.app.common.messages.ModerationMessages`.

| Code | Status | When |
|---|---|---|
| `CONTENT_REJECTED` | 400 | Auto-block or admin reject on a create/edit. Copy never quotes the text back or names the label — that would be a free oracle for probing the classifier. |
| `CONTENT_UNDER_REVIEW` | 400 | Re-submitting something already held |
| `MODERATION_CASE_NOT_FOUND` | 400 | Unknown case id |
| `MODERATION_CASE_DECIDED` | 400 | Deciding an already-decided case |
| `INVALID_MODERATION_ACTION` | 400 | Action other than APPROVE/REJECT |
| `INVALID_MODERATION_SETTING` | 400 | Unknown key, entity type, label or source |
| `INVALID_THRESHOLD` | 400 | Outside `[0,1]`, or `high < low` |
| `INVALID_TRAINING_EXAMPLE` | 400 | Empty text |
| `TRAINING_DATASET_TOO_SMALL` | 400 | Below `retrain.min-examples` |
| `TRAINING_ALREADY_RUNNING` | 400 | A job is in flight |
| `TRAINING_SERVICE_UNAVAILABLE` | 400 | `model-training` unreachable |
| `INFERENCE_UNAVAILABLE` | 400 | `model-inference` unreachable (probe/promote only — the content path never surfaces this to a user) |
| `MODEL_VERSION_NOT_FOUND` | 400 | Unknown version |
| `MODEL_NOT_PROMOTABLE` | 400 | Status is not READY/SHADOW/RETIRED |
| `MODEL_GATE_FAILED` | 400 | Promotion gate failed and `force` was not set |
| `INVALID_CALLBACK_TOKEN` | 401 | Bad `X-Training-Token` |

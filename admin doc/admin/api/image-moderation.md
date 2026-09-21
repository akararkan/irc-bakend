# Admin Reference — Image Moderation

How admins and moderators operate the NSFW image gate
([design doc](../../../docs/moderation/image-moderation.md)). **There are no
new controllers** — image moderation deliberately rides the three existing
automated-moderation surfaces documented in
[automated-moderation.md](automated-moderation.md); this page covers only what
is *different* when the case in front of you is an image.

Conventions (auth, step-up, envelope, page clamps) are identical to
[automated-moderation.md](automated-moderation.md#conventions-used-throughout).

---

## 1. The review queue — `MEDIA_IMAGE` cases

`GET /api/v1/admin/moderation/review?entityType=MEDIA_IMAGE` filters the
existing queue to image cases. They arrive already `IN_REVIEW` (there is no
PENDING phase for images — the gate decides inline at upload).

A `QueueRow` for an image case reads:

| Field | Value for images |
|---|---|
| `entityType` | `"MEDIA_IMAGE"` |
| `entityLabel` | `"image"` |
| `entityRef` | the **media asset UUID** (not a post id) |
| `maxLabel` | always `"nsfw"` |
| `maxScore` | the nsfw probability (0.80–0.90 band), or `0.0` for unscored fail-open cases |
| `reasonCode` | `"MODEL"` (scored borderline) or `"MODEL_UNAVAILABLE"` (scorer was down — see §4) |
| `slaBreached` | `true` exactly when `reasonCode = MODEL_UNAVAILABLE` |
| `preview` | the image **URL** — open it to review. Cases carry the URL, not a copy: a dead link means the asset was already deleted (author or cascade); approve it as a no-op. |

The case detail (`GET …/review/{caseId}`) shows one field named `image` whose
`text` is that URL and whose `scores` are `{"nsfw": …, "normal": …}`.

**What you will NOT see here:** chat images. Private correspondence is exempt
from the human queue by the same policy that redacts `CHAT_MESSAGE` text —
confidently explicit chat images are still blocked at upload, but the
uncertain middle in DMs is never surfaced to staff.

### Deciding

`POST …/review/{caseId}/decide` with `{"action": "APPROVE" | "REJECT"}`,
exactly like text. Semantics differ:

- **APPROVE** → no-op. The image published at upload; approving simply closes
  the case.
- **REJECT** → the **entire media asset is deleted** (original + every
  rendition) via the async delete queue, and the author gets the standard
  "your image was removed" notification. The post/story that embedded it keeps
  its text and other media; the URL goes dead. If removing the whole post is
  warranted, do that separately through the content-moderation tools
  ([content-moderation.md](content-moderation.md)).
- Reject is effectively **irreversible** — the bytes are gone. There is no
  "un-reject" that restores the image.

`POST …/review/bulk` works on image cases like any others.

### What is disabled for image cases

| Action | Behavior |
|---|---|
| **Teach** (`teach` on decide) | Silently skipped. Image cases carry a URL, not user text — feeding it to the *text* training set would only pollute it. There is no image-retraining loop; the checkpoint is used as-is. |
| **Rescore** (`POST …/{caseId}/rescore`) | Returns the current status unchanged. Rescoring would push the URL through the text model, which would score it clean and quietly auto-approve a flagged image. Only a human decides an image case. |

---

## 2. Settings — the `image.*` keys

Tuned through the existing settings surface
(`/api/v1/admin/moderation/settings`); reads are plain admin, writes are
**step-up** as usual. All keys live in `moderation_settings` and take effect
within ~30s on every node (settings cache TTL), immediately on the node that
served the write.

| Key | Default | Meaning |
|---|---|---|
| `image.enabled` | `true` | Image gate switch. ANDed with the global `enabled` — the master kill switch stops image scoring too. |
| `image.threshold.block` | `0.90` | nsfw at/above → upload **rejected**, nothing stored. Precision-tuned: the target is pornographic/explicit content only — real explicit content scores ≥0.95 on this checkpoint. |
| `image.threshold.review` | `0.80` | nsfw at/above (below block) → publishes + queued here. Kept high (narrow band) so portraits/beach/skin-adjacent benign images never reach the queue. Clamped to ≤ block, so a mistuned pair can never silently disable the queue. |
| `image.fallback` | `FAIL_OPEN_SHADOW` | Scorer-down policy. `FAIL_CLOSED` refuses every image upload with a 503 while the scorer is down — moderation-first, availability-second. |
| `image.video-posters` | `true` | Screen the extracted poster frame of every uploaded video with the same bands. |

Write one:

```
PUT /api/v1/admin/moderation/settings/raw        (step-up)
{"key": "image.threshold.block", "value": "0.92"}
```

`GET /api/v1/admin/moderation/settings` → the effective view now contains an
`image` block (`enabled`, `blockThreshold`, `reviewThreshold`, `fallback`,
`videoPosters`). The `entityTypes` map deliberately omits `media_image` — the
per-label text bands do not apply to images.

**Tuning guidance.** Lower `block` = stricter (more blocks, more false
positives — beach/medical/art photos are the classic ones). Widen the band
(lower `review`) when you want human eyes on more borderline images; narrow it
when this queue drowns out the text queue. After any change, watch the queue
inflow and the block rate for a day before touching it again.

---

## 3. Ops — scorer health

The model panel (`GET /api/v1/admin/moderation/settings` → `model`, also on
the model-registry status endpoint) now carries the image scorer alongside the
text one:

| Key | Meaning |
|---|---|
| `imageInferenceUp` | `GET :8002/healthz` succeeded just now |
| `imageModelVersion` | resident checkpoint (`Falconsai/nsfw_image_detection`) |
| `imageCircuit` | `CLOSED` / `OPEN` — OPEN means calls are failing fast to the fallback policy |
| `imageCalls` / `imageFailures` / `imageAvgLatencyMs` | client counters since boot |
| `imageInferenceError` | last health-probe error, when down |

The container itself: `docker compose up -d image-inference` (first boot
downloads ~350MB — the healthcheck allows 180s). Backend env:
`IMAGE_MODERATION_URL`, `IMAGE_MODERATION_API_KEY` (must match the
container's `IMAGE_INFERENCE_API_KEY`), `IMAGE_MODERATION_ENABLED`.

---

## 4. Runbook

**Scorer down (default `FAIL_OPEN_SHADOW`).** Uploads keep working. Image
cases pile into the queue with `reasonCode: MODEL_UNAVAILABLE`,
`slaBreached: true`, `maxScore: 0`. Triage: fix the container first (the
queue stops growing), then work the unscored backlog newest-first — every one
of those images is live and unseen by any model. Filter:
`GET …/review?entityType=MEDIA_IMAGE&slaBreached=true`.

**Scorer down (`FAIL_CLOSED`).** Every image/video upload platform-wide is
answering `503 MEDIA_MODERATION_UNAVAILABLE`. This is the configured intent,
but treat it as an incident: restart the container, or temporarily
`image.fallback` → `FAIL_OPEN_SHADOW` (step-up write) and accept the review
backlog instead.

**Queue flooding.** Raise `image.threshold.review` toward `block` (narrower
band). If the flood is false *blocks* (users complaining uploads are refused),
raise `image.threshold.block` a notch — but review a sample of recent blocks
first; the queue metrics endpoint (`…/review/metrics`) splits by entity type.

**A flagged image was actually fine.** Approve the case; nothing else to do.

**An allowed image should have been caught.** There is no teach loop for
images — the lever is the thresholds. If it scored just under the review line,
lower `image.threshold.review`; note the model version in any report (it is on
every case).

**Legal/CSAM.** This gate is *not* CSAM detection and its verdicts discharge
none of those obligations — suspected CSAM follows the platform's legal
escalation path (safety/legal-holds tooling, [safety-audit.md](safety-audit.md)),
never the ordinary reject button.

# model-training

On-demand fine-tuning container for the automated moderation system
(`docs/moderation/MODERATION_ROADMAP.md` §12.4). It is the *slow, high-quality*
lever in the loop — the blocklist is the fast one.

It never connects to the application database. Spring Boot exports the current
`moderation_training_examples` rows and POSTs them, exactly as §9 requires.

## Contract

```
POST /v1/train
{
  "examples":  [ {"text": "...", "labels": {"toxic": 1, "insult": 1, ...}} ],
  "golden_set":[ {"text": "...", "labels": {...}} ],
  "base_checkpoint": "v3",          // artifact under MODEL_ROOT, or a HF id
  "version": "v4",                  // optional; auto-incremented otherwise
  "callback_url": "http://host.docker.internal:8080/api/v1/admin/moderation/model/train-callback",
  "callback_token": "…"
}
202-style response: { "job_id": "…", "status": "queued" }

GET /v1/train/{job_id}   → queued | training | evaluating | done | failed
                           plus per-label precision/recall/F1 and the golden report
GET /v1/versions         → artifacts present in the shared volume
GET /healthz /readyz
```

Training runs in a background task; the HTTP request is never held open for the
duration. Spring Boot polls `GET /v1/train/{job_id}` and *also* accepts the
callback — whichever lands first wins, so a dropped webhook degrades to polling
rather than to a stuck `training` row.

## What the evaluation actually checks

Three things, because a single macro-F1 number hides regressions:

1. **Held-out split** (§18.4) — stratified rarest-label-first, so `threat` and
   `identity_hate` contribute positives to the validation fold instead of
   landing entirely in train and reporting an undefined recall. Labels with zero
   validation positives are marked `"evaluated": false` and excluded from
   macro-F1 rather than silently scored 0.
2. **Golden set** (§17) — a fixed hand-reviewed list that must keep scoring the
   way it always did. Reported case by case, not aggregated.
3. **Promotion gate** — enforced on the *Spring Boot* side
   (`app.moderation.retrain.max-f1-drop`), not here. This service produces and
   measures an artifact; a human promotes it.

A failed run deletes its half-written artifact directory, so the dashboard never
offers an incomplete model as promotable.

## Run

```bash
# In the compose topology (not started by default):
docker compose --profile on-demand up --build model-training

# Standalone:
pip install -r requirements.txt
MODEL_ROOT=./model DATA_ROOT=./data uvicorn app.main:app --port 8001
```

## Tuning

| Env | Default | Notes |
|---|---|---|
| `BASE_CHECKPOINT` | `unitary/toxic-bert` | starting weights when no version is given |
| `TRAIN_EPOCHS` | `3` | |
| `TRAIN_BATCH_SIZE` | `8` | CPU-sized; raise on GPU |
| `TRAIN_LEARNING_RATE` | `2e-5` | |
| `TRAIN_MAX_LENGTH` | `256` | must match the inference container's `MAX_LENGTH` |
| `TRAIN_VALIDATION_FRACTION` | `0.15` | 85/15 per §18.4 |
| `TRAIN_MIN_EXAMPLES` | `20` | refuses to train on a dataset too small to mean anything |
| `EVAL_THRESHOLD` | `0.5` | for metric computation only — the *production* bands are per-label and live in Spring Boot (§8.1) |
| `TRAINING_API_KEY` | *(empty)* | shared secret; must match `app.moderation.training.api-key` |

First run downloads the base checkpoint from Hugging Face. Air-gapped hosts
should bake it into the image or mount a pre-populated `HF_HOME`.

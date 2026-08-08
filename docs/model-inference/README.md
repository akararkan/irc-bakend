# model-inference

Dockerized scoring service for `docs/moderation/MODERATION_ROADMAP.md` (§7).
Wraps the fine-tuned toxic-bert model behind `POST /v1/score` and
`POST /v1/score/batch`. No thresholds, no persistence, no business logic — it
only turns text into six probabilities. Every policy decision (bands,
per-entity overrides, blocklist) lives in the Spring Boot Decision Engine so
sensitivity is a config change, not a container rebuild.

## Contract

```
POST /v1/score          { "text": "..." }
                        → { "scores": {...6 labels...}, "model_version": "v3", "inference_ms": 18 }
POST /v1/score/batch    { "items": [ {"id": "title", "text": "..."} ] }
                        → { "results": [ {"id": "title", "scores": {...}} ], "model_version": "v3" }
POST /v1/reload         { "version": "v4" }   hot-swap without a restart (§12.4 promote/rollback)
GET  /v1/model          which artifact is resident
GET  /healthz /readyz   container health checks
```

`X-API-Key` is required on the `/v1/*` routes when `INFERENCE_API_KEY` is set.
The health routes stay open so orchestrator probes keep working.

## Getting a model artifact in place

The service expects a Hugging Face `save_pretrained` directory at `MODEL_PATH`
(default `/app/model`): `config.json`, `model.safetensors`, `tokenizer.json`,
`tokenizer_config.json`, plus an optional `VERSION` file (single line, e.g. `v1`)
used in API responses and logging.

**If nothing is mounted**, the container loads `BASE_CHECKPOINT`
(`unitary/toxic-bert` by default) so a fresh clone comes up scoring instead of
serving 503 forever, and reports `model_version: "base:unitary/toxic-bert"` so
that state is never mistaken for a promoted artifact. Set
`ALLOW_BASE_FALLBACK=false` in production to force an explicit artifact.

Once a retrain has been promoted, the artifact lives at
`/app/model/<version>/` in the shared `model-artifacts` volume written by
`docs/model-training`, and Spring Boot calls `POST /v1/reload {"version": "v4"}`
to swap it in.

## Long text

Research/article bodies exceed the tokenizer's max sequence length. Chunking
happens **here**, not in Java — it is a property of the model's input limits,
not a business rule (§7.1). Text is split into overlapping windows
(`MAX_LENGTH` / `CHUNK_STRIDE`, capped at `MAX_CHUNKS`), every chunk is scored
in a single batched forward pass, and the **per-label max across chunks** is
returned. One toxic paragraph in a long article still trips the label instead
of being averaged away.

## Run locally

```bash
pip install -r requirements.txt
MODEL_PATH=./model uvicorn app.main:app --reload --port 8000
```

## Run in Docker

```bash
docker compose up --build model-inference          # from the repo root
# or standalone:
docker build -t model-inference docs/model-inference
docker run -p 8000:8000 -v $(pwd)/model:/app/model model-inference
```

## Tuning

| Env | Default | Notes |
|---|---|---|
| `MODEL_ROOT` | `/app/model` | shared artifact volume; versions are subdirectories |
| `MODEL_PATH` | `$MODEL_ROOT` | artifact loaded at boot |
| `BASE_CHECKPOINT` | `unitary/toxic-bert` | bootstrap fallback |
| `ALLOW_BASE_FALLBACK` | `true` | set `false` in production |
| `MAX_LENGTH` | `256` | keep in sync with the training container |
| `CHUNK_STRIDE` | `64` | overlap between long-text windows |
| `MAX_CHUNKS` | `16` | ceiling on chunks per field |
| `MAX_BATCH_ITEMS` | `64` | rejects oversized batches with 413 |
| `TORCH_THREADS` | *(torch default)* | pin when running several replicas per host |
| `INFERENCE_API_KEY` | *(empty)* | must match `app.moderation.inference.api-key` |

Scale by running **more replicas**, not more uvicorn workers — the model is
memory-heavy and one process per container keeps the footprint predictable
(§7.5).

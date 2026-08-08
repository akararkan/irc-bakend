# Moderation — operations runbook

Running the two Python containers, what breaks, and what to do about it.

---

## 1. Topology

```
Spring Boot (host or container)
  ├─ HTTP → model-inference  :8000   always up, in the content path
  └─ HTTP → model-training   :8001   on demand only
                    ↘ shared volume `model-artifacts` ↙
                       /app/model/<version>/
```

Both containers are defined in the repo-root `docker-compose.yml`.
`model-training` sits behind the `on-demand` profile and is not started by
default — a fine-tune saturates the CPU and only needs to exist while a run is in
flight.

```bash
docker compose up -d model-inference
docker compose --profile on-demand up -d model-training     # only when retraining
```

**Never expose `:8000` or `:8001` publicly** (§15). They are internal-network
services with no authorization model beyond the optional shared key. In this
compose file they publish to localhost because the backend runs on the host; once
the app is containerized, drop the `ports:` mapping and address them by service
name.

Set `MODERATION_INFERENCE_API_KEY` / `MODERATION_TRAINING_API_KEY` in both the
container environment and `app.moderation.*.api-key`. They must match.

---

## 2. First boot

The inference container needs a model artifact at `MODEL_PATH`. If the shared
volume is empty it falls back to `BASE_CHECKPOINT` (`unitary/toxic-bert`) and
reports `model_version: "base:unitary/toxic-bert"` — so a fresh clone comes up
scoring instead of serving 503 forever, and that state is never mistaken for a
promoted artifact.

That first boot downloads ~450MB from Hugging Face. On an air-gapped host, bake
the checkpoint into the image or mount a pre-populated `HF_HOME`, and set
`ALLOW_BASE_FALLBACK=false` so a missing artifact fails loudly instead of
silently serving base weights.

Health:

```bash
curl -s localhost:8000/healthz          # {"status":"ok","model_version":"v3"}
curl -s localhost:8000/v1/model         # resident artifact, labels, max_length
curl -s localhost:8001/healthz          # training container, when running
```

From the app side, one call covers both plus the registry:

```
GET /api/v1/admin/moderation/review/metrics   →  .model
```

---

## 3. Failure modes

### Everything holds, but `/healthz` says the model is UP

**Symptom:** every post comes back `PENDING_REVIEW` ("Checking…"), the review
queue fills with `SLA_BREACH` rows carrying **no scores and no `model_version`**,
yet `model.inferenceUp` is `true` and `circuit` is `CLOSED`. Latency looks
absurdly low (single-digit ms) because nothing is actually being scored.

**Cause:** the JDK's `HttpClient` defaults to `HTTP_2`, which on a cleartext
connection opens with an **h2c upgrade handshake**. uvicorn/h11 does not speak
h2c — it logs `Unsupported upgrade request` and the POST body is dropped, so
FastAPI sees no `items` and answers **422**. `GET /healthz` has no body, so it
survives the failed upgrade and keeps reporting healthy. That combination is the
worst possible shape: a total scoring outage hiding behind a green light.

**Fix (already applied):** both moderation clients pin
`.version(HttpClient.Version.HTTP_1_1)`. If you ever add a third client against
a FastAPI/uvicorn service, pin it too.

**How to confirm it in ten seconds** — the container's own access log is
definitive:

```bash
docker logs --since 10m irc-model-inference-1 | grep -v healthz | tail
#  WARNING:  Unsupported upgrade request.
#  INFO:  … "POST /v1/score/batch HTTP/1.1" 422 Unprocessable Entity     ← this
```

And the one-line reproduction, which needs nothing but curl:

```bash
curl -s -o /dev/null -w '%{http_code}\n'         -X POST localhost:8000/v1/score/batch \
     -H 'content-type: application/json' -d '{"items":[{"id":"a","text":"hi"}]}'   # 200
curl -s -o /dev/null -w '%{http_code}\n' --http2 -X POST localhost:8000/v1/score/batch \
     -H 'content-type: application/json' -d '{"items":[{"id":"a","text":"hi"}]}'   # 422
```

**Recovery is automatic.** Once scoring works again, the SLA sweeper's
outage-recovery pass re-scores every case that was parked in review *without ever
being scored* (`reasonCode = SLA_BREACH` and `modelVersion IS NULL`) and settles
it properly — content that was stuck at "Checking…" publishes or is blocked
within a sweep tick. Cases that reached review on a genuine borderline score are
left alone: the model already had its say and the human is the escalation.

### The inference container is down

**Symptom:** `model.inferenceUp: false`, `circuit: OPEN`, and the `PENDING` /
`IN_REVIEW` counts climbing.

**What the app does:** every content submission fails fast on the open circuit
(no per-request timeout is paid), the case stays `PENDING`, a message goes to
`irc.queue.moderation`, and the SLA sweeper applies the configured fallback
policy on the hold deadline. Fail-closed types hold; stories publish and are
flagged.

**This is correct, not broken.** Content is not published unchecked. But the
review queue fills up fast, so it is an urgent ops problem.

**Fix:** bring the container back. Held cases are retried automatically by the
sweeper — nothing needs replaying by hand. To bulk-clear a backlog once the model
is healthy, `POST /moderation/review/{caseId}/rescore` per case, or approve in
bulk after a spot check.

**Emergency valve:** if the container cannot be restored quickly and holding
content is worse than the risk, flip the fallback for specific types rather than
disabling moderation wholesale:

```
PUT /api/v1/admin/moderation/settings/hold-durations
{ "entityType": "post_comment", "fallback": "FAIL_OPEN_SHADOW" }
```

Everything published that way is still flagged and reviewable. Remember to put it
back.

### The circuit breaker is flapping

`circuit: OPEN` with `inferenceUp: true` means the container is answering
`/healthz` but failing or timing out on scoring — usually CPU starvation, or a
`MAX_LENGTH` / chunk configuration producing far more forward passes than
expected on long text.

Check `avgLatencyMs` on the metrics endpoint against the inline budgets. If
research papers are the problem, either raise `inline.research.ms` or lower
`MAX_CHUNKS` on the container.

### Cases stuck in PENDING with the model healthy

Look at `applyError` on the case rows. A verdict that was reached but whose
content flip failed leaves `appliedAt` null, and the sweeper's third pass
re-drives those every 5 seconds. Persistent errors there mean the applier is
hitting something real — a deleted post, a Cassandra timeout — and the message is
in the log line `[MODERATION] apply failed for …`.

### The queue consumer is dead-lettering

Moderation messages that exhaust their retries land in the standard parking lot
(`dead_letters`). That is survivable by design: the case row is still `PENDING`
in Postgres and the sweeper owns it. Investigate the DLQ for a pattern, but do
not treat a dead letter here as lost content.

### Training job stuck at `training`

The poller chases in-flight jobs every 15 seconds while any exist. If a version
sits at `TRAINING` with the container gone, the row will never settle on its own
— the job died with the container. Mark it manually or start a fresh run; the
registry row is informational until it reaches `READY`.

If the webhook is configured but never arrives, check that
`app.moderation.training.callback-url` uses `host.docker.internal` (the container
cannot reach `localhost` on the host). Polling covers the same ground, so a
broken webhook is a cosmetic problem, not a functional one.

---

## 4. Pausing the jobs

Both scheduled jobs are in the standard `JobPauseRegistry`:

```
POST /api/v1/admin/ops/jobs/moderation-sla-sweep/pause
POST /api/v1/admin/ops/jobs/moderation-training-poll/pause
```

Pausing the sweeper stops held content from being force-resolved — useful while
rolling the inference container, so a five-minute restart does not dump the whole
in-flight window into the review queue as SLA breaches. **It also means nothing
leaves `PENDING` until you resume it.** Set a reminder.

---

## 5. Scaling

The inference container runs one uvicorn worker on purpose: PyTorch models are
memory-heavy and one process per container keeps the footprint predictable. Scale
out with **replicas behind a load balancer**, not workers (§7.5). The Java client
is agnostic to how many sit behind `MODERATION_INFERENCE_URL`.

A single ~128-token forward pass on CPU is fast enough for the latency targets at
moderate volume. Move to GPU-backed replicas only once sustained throughput
requires it — or once chunking on long research bodies is multiplying calls per
request enough to show up in `avgLatencyMs`.

Set `TORCH_THREADS` when packing several replicas onto one host; without it each
replica assumes it owns every core and they fight.

---

## 6. Promoting a model safely

1. `POST /moderation/model/retrain` (step-up) with the training container up.
2. Watch `GET /moderation/model/versions` until `READY`.
3. Read `gatePassed` and `gateDetail`, and the golden report. A failed gate is
   information, not a verdict — but overriding it is a deliberate act recorded
   against your user id.
4. `POST /moderation/model/versions/{id}/promote` (step-up). This calls
   `/v1/reload` on the container **before** flipping the registry, so a bad
   artifact leaves the running model untouched and the database never claims
   something is live that is not.
5. Verify `model.registryInSync: true` and `residentVersion` matches.
6. Watch `bands.autoDecidedPercent` and `queue.inReview` for the next hour. A
   promotion that quietly doubled the review rate is the failure the gate cannot
   catch.
7. If it regressed: `POST /moderation/model/rollback`. It re-promotes the most
   recently retired version from the shared volume and never re-runs training.

`/v1/reload` loads the new artifact fully **before** taking the swap lock, so
in-flight requests keep using the old model and never observe a half-swapped
state.

---

## 7. What to watch

| Signal | Where | Healthy |
|---|---|---|
| `bands.autoDecidedPercent` | metrics | high and stable; a sudden drop means drift or a bad promote |
| `queue.inReview` | metrics | flat; sustained growth means thresholds too tight or the model is down |
| `queue.slaBreached` | metrics | near zero |
| `sla[].withinSlaPercent` | metrics | ~100 per entity type |
| `model.circuit` | metrics | `CLOSED` |
| `model.avgLatencyMs` | metrics | well under the tightest inline budget (300ms for live chat) |
| `model.registryInSync` | metrics | `true` |
| `dataset.untrained` | metrics | growing is fine — it is the retrain trigger |

Log prefixes to grep: `[MODERATION]`, `[MODERATION-SLA]`, `[MODERATION-CB]`,
`[MODERATION-TRAIN]`, `[MODERATION-CASSANDRA]`.

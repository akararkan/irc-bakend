# image-inference — NSFW image scoring container

The image half of the moderation plane. Pure scorer: image bytes in, two
probabilities out (`nsfw` / `normal`, summing to 1). All policy — thresholds,
fallback, per-surface behavior — lives in the Spring Boot backend
(`ImageModerationGate` + `moderation_settings`), same as the text scorer.

**Model:** [Falconsai/nsfw_image_detection](https://huggingface.co/Falconsai/nsfw_image_detection)
— a `vit-base-patch16-224` fine-tuned on ~80k images, two classes, ~98% on its
own eval set, ~350MB. Downloaded once on first boot into the `HF_HOME` cache
volume; fully offline afterwards.

## Run

```bash
docker compose up -d image-inference     # from the repo root
# first boot downloads the checkpoint — watch:  docker compose logs -f image-inference
```

Smoke test:

```bash
curl -s http://localhost:8002/healthz
python3 - <<'EOF'
import base64, json, urllib.request
b64 = base64.b64encode(open("photo.jpg", "rb").read()).decode()
req = urllib.request.Request("http://localhost:8002/v1/score",
    json.dumps({"image_b64": b64}).encode(), {"Content-Type": "application/json"})
print(urllib.request.urlopen(req).read().decode())
EOF
```

## Contract

| Endpoint | Body | Answer |
|---|---|---|
| `POST /v1/score` | `{"image_b64": "…"}` | `{"scores": {"normal": 0.02, "nsfw": 0.98}, "model_version", "inference_ms"}` |
| `POST /v1/score/batch` | `{"items": [{"id", "image_b64"}]}` | one result per item; per-item `error` for undecodable images |
| `GET /v1/model` | — | resident checkpoint + limits |
| `GET /healthz`, `/readyz` | — | 200 when the model is resident, 503 otherwise |

Base64-in-JSON, not multipart — the Java caller is the JDK `HttpClient` and the
contract stays shape-identical to the text scorer's.

**Status semantics matter:** `422` means *this input is not a decodable image*
(the backend lets its own decoders reject the file; the circuit breaker is NOT
tripped). `503`/timeouts mean *the scorer is down* (the backend applies the
configured fallback policy). Conflating the two would let corrupt uploads trip
the breaker.

## Environment

| Var | Default | Meaning |
|---|---|---|
| `MODEL_ID` | `Falconsai/nsfw_image_detection` | HF checkpoint to serve |
| `IMAGE_INFERENCE_API_KEY` | *(empty)* | shared secret; must match `app.moderation.image.api-key` |
| `MAX_IMAGE_BYTES` | 30MB | per-image decoded ceiling (413 above) |
| `MAX_BATCH_ITEMS` | 16 | per-batch ceiling (413 above) |
| `MAX_IMAGE_PIXELS` | 64MP | PIL decompression-bomb guard |
| `TORCH_THREADS` | 0 (auto) | CPU thread cap |

Images are never persisted or logged by this container — only scores leave it.

Full system documentation: `docs/moderation/image-moderation.md`.

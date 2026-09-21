"""
Image Moderation Inference Service (docs/moderation/image-moderation.md).

Pure scorer: image bytes in, two probabilities out (`nsfw` / `normal`). Holds
no thresholds, no business rules, no persistence — that policy lives in the
Spring Boot ImageModerationGate + moderation_settings so admins can retune
sensitivity from the dashboard without rebuilding this container. Exactly the
same philosophy as the text scorer next door (docs/model-inference).

Model: Falconsai/nsfw_image_detection — a ViT (vit-base-patch16-224) fine-tuned
on ~80k images into two classes. ~350MB, downloaded once on first boot and kept
in the HF cache volume. Everything after that runs fully offline.

Endpoints
    POST /v1/score          one image (base64 JSON), low latency
    POST /v1/score/batch    N images, one round-trip
    GET  /v1/model          which checkpoint is resident
    GET  /healthz /readyz   container health checks

The transport is base64-in-JSON rather than multipart, deliberately: the Java
side is the JDK HttpClient (no multipart encoder), and it keeps this contract
shape-identical to the text scorer's — one JSON POST, one JSON reply.

Images are never written to disk and never logged — only the resulting scores.
"""

from __future__ import annotations

import base64
import binascii
import io
import logging
import os
import time
from typing import Dict, List, Optional

import torch
from fastapi import Depends, FastAPI, Header, HTTPException
from PIL import Image, UnidentifiedImageError
from pydantic import BaseModel, Field
from transformers import AutoImageProcessor, AutoModelForImageClassification

logging.basicConfig(
    level=os.environ.get("LOG_LEVEL", "INFO"),
    format="%(asctime)s %(levelname)-5s [image-inference] %(message)s",
)
log = logging.getLogger("image-inference")

# ── configuration ──────────────────────────────────────────────────────────

MODEL_ID = os.environ.get("MODEL_ID", "Falconsai/nsfw_image_detection")
"""Hugging Face checkpoint. Downloaded on first boot (network required once),
then served from the cache volume mounted at HF_HOME."""

MAX_IMAGE_BYTES = int(os.environ.get("MAX_IMAGE_BYTES", str(30 * 1024 * 1024)))
"""Decoded-bytes ceiling per image; anything larger answers 413. The backend
caps uploads well below this — the guard is for direct callers."""

MAX_BATCH_ITEMS = int(os.environ.get("MAX_BATCH_ITEMS", "16"))
TORCH_THREADS = int(os.environ.get("TORCH_THREADS", "0"))

API_KEY = os.environ.get("IMAGE_INFERENCE_API_KEY", "")
"""Optional shared secret, sent as X-API-Key. The service is supposed to sit on
the internal network only; this is belt-and-braces. Empty = no auth."""

# Decompression-bomb guard: PIL refuses absurd pixel counts instead of eating
# all container memory. 64MP is far above any legitimate upload.
Image.MAX_IMAGE_PIXELS = int(os.environ.get("MAX_IMAGE_PIXELS", str(64_000_000)))

CANONICAL_LABELS = ["normal", "nsfw"]

if TORCH_THREADS > 0:
    torch.set_num_threads(TORCH_THREADS)


# ── the resident model ─────────────────────────────────────────────────────

_processor = None
_model = None
_labels: List[str] = CANONICAL_LABELS
_loaded_at: Optional[float] = None

app = FastAPI(title="image-moderation-inference-service", version="1.0.0")


def _resolve_labels(model) -> List[str]:
    """Trust the checkpoint's id2label when it names the two labels we expect
    (Falconsai ships {0: 'normal', 1: 'nsfw'}), otherwise fall back to canonical
    order — a silently swapped label vector would invert every decision."""
    id2label = getattr(model.config, "id2label", None) or {}
    resolved: List[str] = []
    for idx in range(model.config.num_labels):
        raw = str(id2label.get(idx, id2label.get(str(idx), ""))).strip().lower()
        resolved.append(raw)
    if sorted(resolved) == sorted(CANONICAL_LABELS):
        return resolved
    log.warning("checkpoint id2label=%s not recognised — using canonical order", resolved)
    return CANONICAL_LABELS[: model.config.num_labels]


@app.on_event("startup")
def startup() -> None:
    global _processor, _model, _labels, _loaded_at
    try:
        log.info("loading %s … (first boot downloads ~350MB)", MODEL_ID)
        _processor = AutoImageProcessor.from_pretrained(MODEL_ID)
        _model = AutoModelForImageClassification.from_pretrained(MODEL_ID)
        _model.eval()
        _labels = _resolve_labels(_model)
        _loaded_at = time.time()
        log.info("model loaded — id=%s labels=%s", MODEL_ID, _labels)
        try:
            # Warm the graph: the first forward pass pays one-off lazy init
            # (kernel selection, memory pools). Paying it here keeps the first
            # real upload as fast as every later one.
            started = time.perf_counter()
            _score_images([Image.new("RGB", (224, 224))])
            log.info("warmup pass done in %.0fms", (time.perf_counter() - started) * 1000)
        except Exception as exc:  # noqa: BLE001 — warmup is best-effort
            log.warning("warmup pass failed (non-fatal): %s", exc)
    except Exception as exc:  # noqa: BLE001 — stay up and report unhealthy
        # Never crash-loop: /healthz stays 503 so the orchestrator can surface
        # the misconfiguration (usually: no network on first boot).
        log.error("startup load failed (%s) — serving 503 until restart", exc)
        _model = None


def require_api_key(x_api_key: str = Header(default="", alias="X-API-Key")) -> None:
    if API_KEY and x_api_key != API_KEY:
        raise HTTPException(status_code=401, detail="invalid api key")


def _require_model():
    if _model is None or _processor is None:
        raise HTTPException(status_code=503, detail="model not loaded")
    return _model


# ── request/response models ────────────────────────────────────────────────


class ScoreRequest(BaseModel):
    image_b64: str = Field(default="")


class ScoreItem(BaseModel):
    id: str
    image_b64: str = Field(default="")


class BatchScoreRequest(BaseModel):
    items: List[ScoreItem] = Field(default_factory=list)


# ── scoring ────────────────────────────────────────────────────────────────


def _decode(image_b64: str) -> Image.Image:
    """Base64 → RGB PIL image. Answers 422 for anything undecodable — the Java
    client treats 422 as 'unscorable input', NOT as 'scorer down', so a corrupt
    upload never trips the circuit breaker. Animated GIFs decode to their first
    frame via convert('RGB')."""
    if not image_b64:
        raise HTTPException(status_code=422, detail="empty image")
    try:
        raw = base64.b64decode(image_b64, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise HTTPException(status_code=422, detail="invalid base64") from exc
    if len(raw) > MAX_IMAGE_BYTES:
        raise HTTPException(status_code=413, detail=f"image too large: {len(raw)} bytes")
    try:
        return Image.open(io.BytesIO(raw)).convert("RGB")
    except (UnidentifiedImageError, Image.DecompressionBombError, OSError) as exc:
        raise HTTPException(status_code=422, detail="not a decodable image") from exc


def _score_images(images: List[Image.Image]) -> List[Dict[str, float]]:
    """Scores N images in one forward pass. Softmax over the two heads, so the
    scores always sum to 1 per image."""
    model = _require_model()
    inputs = _processor(images=images, return_tensors="pt")
    with torch.inference_mode():
        logits = model(**inputs).logits
    probs = torch.softmax(logits, dim=-1)
    results: List[Dict[str, float]] = []
    for row in probs:
        results.append(
            {label: round(float(value), 4) for label, value in zip(_labels, row)}
        )
    return results


# ── endpoints ──────────────────────────────────────────────────────────────


@app.get("/healthz")
def healthz():
    _require_model()
    return {"status": "ok", "model_version": MODEL_ID}


@app.get("/readyz")
def readyz():
    return healthz()


@app.get("/v1/model")
def model_info(_: None = Depends(require_api_key)):
    _require_model()
    return {
        "model_version": MODEL_ID,
        "labels": _labels,
        "max_image_bytes": MAX_IMAGE_BYTES,
        "max_batch_items": MAX_BATCH_ITEMS,
        "loaded_at_epoch": round(_loaded_at or 0, 3),
    }


@app.post("/v1/score")
def score(req: ScoreRequest, _: None = Depends(require_api_key)):
    _require_model()
    start = time.perf_counter()
    image = _decode(req.image_b64)
    scores = _score_images([image])[0]
    return {
        "scores": scores,
        "model_version": MODEL_ID,
        "inference_ms": round((time.perf_counter() - start) * 1000, 2),
    }


@app.post("/v1/score/batch")
def score_batch(req: BatchScoreRequest, _: None = Depends(require_api_key)):
    if len(req.items) > MAX_BATCH_ITEMS:
        raise HTTPException(
            status_code=413,
            detail=f"batch too large: {len(req.items)} > {MAX_BATCH_ITEMS}",
        )
    _require_model()
    start = time.perf_counter()
    # Per-item decode errors are reported per item rather than failing the whole
    # batch — the contract stays "one result per item", like the text scorer.
    decoded: List[Image.Image] = []
    owner: List[int] = []
    errors: Dict[int, str] = {}
    for index, item in enumerate(req.items):
        try:
            decoded.append(_decode(item.image_b64))
            owner.append(index)
        except HTTPException as exc:
            errors[index] = str(exc.detail)

    scored = _score_images(decoded) if decoded else []
    by_index: Dict[int, Dict[str, float]] = {
        index: scores for index, scores in zip(owner, scored)
    }

    results = []
    for index, item in enumerate(req.items):
        if index in errors:
            results.append({"id": item.id, "error": errors[index]})
        else:
            results.append({"id": item.id, "scores": by_index[index]})
    return {
        "results": results,
        "model_version": MODEL_ID,
        "inference_ms": round((time.perf_counter() - start) * 1000, 2),
    }

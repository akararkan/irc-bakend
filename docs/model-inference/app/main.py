"""
Moderation Inference Service (MODERATION_ROADMAP.md §7).

Pure scorer: text in, six toxicity-label probabilities out. Holds no
thresholds, no business rules, no persistence — that policy lives in the
Spring Boot Decision Engine so admins can retune sensitivity from the
dashboard without rebuilding this container.

Endpoints
    POST /v1/score          single text, low latency (live chat, edit checks)
    POST /v1/score/batch    N texts, one round-trip (queue worker)
    POST /v1/reload         hot-swap the in-memory model to another artifact
    GET  /v1/model          which artifact is currently resident
    GET  /healthz /readyz   container health checks

Long text is chunked here rather than in Java: the max sequence length is a
property of the model's input limits, not a business rule (§7.1). Each chunk
is scored and the per-label MAX across chunks is returned, so one toxic
paragraph inside a long article still trips the label.
"""

from __future__ import annotations

import logging
import os
import threading
import time
from pathlib import Path
from typing import Dict, List, Optional

import torch
from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, Field
from transformers import AutoModelForSequenceClassification, AutoTokenizer

logging.basicConfig(
    level=os.environ.get("LOG_LEVEL", "INFO"),
    format="%(asctime)s %(levelname)-5s [inference] %(message)s",
)
log = logging.getLogger("inference")

# ── configuration ──────────────────────────────────────────────────────────

MODEL_ROOT = Path(os.environ.get("MODEL_ROOT", "/app/model"))
"""Shared artifact volume. Versioned artifacts live at MODEL_ROOT/<version>/;
a flat MODEL_ROOT (weights directly inside) is also supported for the simple
single-version deployment."""

MODEL_PATH = Path(os.environ.get("MODEL_PATH", str(MODEL_ROOT)))
"""Artifact to load at startup. Defaults to MODEL_ROOT."""

BASE_CHECKPOINT = os.environ.get("BASE_CHECKPOINT", "unitary/toxic-bert")
"""Hugging Face checkpoint used when no artifact is mounted. Lets the container
come up usable on a fresh machine instead of serving 503 forever; requires
network access on first boot (it is cached in the image layer afterwards).
Set ALLOW_BASE_FALLBACK=false in production to force an explicit artifact."""

ALLOW_BASE_FALLBACK = os.environ.get("ALLOW_BASE_FALLBACK", "true").lower() == "true"

MAX_LENGTH = int(os.environ.get("MAX_LENGTH", "256"))
CHUNK_STRIDE = int(os.environ.get("CHUNK_STRIDE", "64"))
MAX_CHUNKS = int(os.environ.get("MAX_CHUNKS", "16"))
MAX_BATCH_ITEMS = int(os.environ.get("MAX_BATCH_ITEMS", "64"))
TORCH_THREADS = int(os.environ.get("TORCH_THREADS", "0"))

API_KEY = os.environ.get("INFERENCE_API_KEY", "")
"""Optional shared secret. The service is supposed to sit on the internal
Docker network only (§15); this is belt-and-braces for topologies where that
cannot be guaranteed. Empty = no auth."""

CANONICAL_LABELS = ["toxic", "severe_toxic", "obscene", "threat", "insult", "identity_hate"]

_LABEL_ALIASES = {
    "toxic": "toxic",
    "toxicity": "toxic",
    "severe_toxic": "severe_toxic",
    "severe_toxicity": "severe_toxic",
    "obscene": "obscene",
    "threat": "threat",
    "insult": "insult",
    "identity_hate": "identity_hate",
    "identity_attack": "identity_hate",
}

if TORCH_THREADS > 0:
    torch.set_num_threads(TORCH_THREADS)


# ── the resident model ─────────────────────────────────────────────────────


class LoadedModel:
    """One immutable (tokenizer, model, labels, version) tuple. Reload swaps the
    whole object under a lock so in-flight requests keep using the old one and
    never observe a half-swapped state."""

    def __init__(self, tokenizer, model, labels: List[str], version: str, source: str):
        self.tokenizer = tokenizer
        self.model = model
        self.labels = labels
        self.version = version
        self.source = source
        self.loaded_at = time.time()


_current: Optional[LoadedModel] = None
_swap_lock = threading.Lock()

app = FastAPI(title="moderation-inference-service", version="1.0.0")


def _resolve_labels(model) -> List[str]:
    """Trust the artifact's own id2label when it names the six labels we expect,
    otherwise fall back to canonical order. A silently mis-ordered label vector
    would mean the Decision Engine reads 'threat' scores off the 'insult' head —
    worth the few lines to get right."""
    id2label = getattr(model.config, "id2label", None) or {}
    resolved: List[str] = []
    for idx in range(model.config.num_labels):
        raw = str(id2label.get(idx, id2label.get(str(idx), ""))).strip().lower()
        resolved.append(_LABEL_ALIASES.get(raw, raw))
    if sorted(resolved) == sorted(CANONICAL_LABELS):
        return resolved
    if len(resolved) != len(CANONICAL_LABELS):
        log.warning(
            "artifact exposes %d heads, expected %d — falling back to canonical label order",
            len(resolved),
            len(CANONICAL_LABELS),
        )
        return CANONICAL_LABELS[: model.config.num_labels]
    log.warning("artifact id2label=%s not recognised — using canonical order", resolved)
    return CANONICAL_LABELS


def _has_artifact(path: Path) -> bool:
    return path.is_dir() and (path / "config.json").exists()


def _read_version(path: Path, fallback: str) -> str:
    version_file = path / "VERSION"
    if version_file.exists():
        text = version_file.read_text().strip()
        if text:
            return text
    return fallback


def _load(path: Path) -> LoadedModel:
    """Loads an artifact directory, or the base checkpoint when nothing is
    mounted and the fallback is allowed."""
    if _has_artifact(path):
        source = str(path)
        version = _read_version(path, path.name if path.name != "model" else "v1")
    elif ALLOW_BASE_FALLBACK:
        source = BASE_CHECKPOINT
        version = "base:" + BASE_CHECKPOINT
        log.warning(
            "no artifact at %s — falling back to base checkpoint %s. "
            "This is a bootstrap convenience; mount a fine-tuned artifact for real use.",
            path,
            BASE_CHECKPOINT,
        )
    else:
        raise FileNotFoundError(f"no model artifact at {path} and base fallback disabled")

    tokenizer = AutoTokenizer.from_pretrained(source)
    model = AutoModelForSequenceClassification.from_pretrained(source)
    model.eval()
    labels = _resolve_labels(model)
    log.info("model loaded — version=%s source=%s labels=%s", version, source, labels)
    return LoadedModel(tokenizer, model, labels, version, source)


@app.on_event("startup")
def startup() -> None:
    global _current
    try:
        _current = _load(MODEL_PATH)
    except Exception as exc:  # noqa: BLE001 — stay up and report unhealthy
        # Never crash-loop: /healthz stays 503 so the orchestrator can surface
        # the misconfiguration, and POST /v1/reload can fix it without a redeploy.
        log.error("startup load failed (%s) — serving 503 until an artifact is available", exc)
        _current = None


def require_api_key(x_api_key: str = Header(default="", alias="X-API-Key")) -> None:
    if API_KEY and x_api_key != API_KEY:
        raise HTTPException(status_code=401, detail="invalid api key")


def _require_model() -> LoadedModel:
    model = _current
    if model is None:
        raise HTTPException(status_code=503, detail="model not loaded")
    return model


# ── request/response models ────────────────────────────────────────────────


class ScoreRequest(BaseModel):
    text: str = Field(default="")


class ScoreItem(BaseModel):
    id: str
    text: str = Field(default="")


class BatchScoreRequest(BaseModel):
    items: List[ScoreItem] = Field(default_factory=list)


class ReloadRequest(BaseModel):
    version: Optional[str] = None
    """Artifact directory name under MODEL_ROOT (e.g. "v4"). Omit to reload
    whatever MODEL_PATH points at."""
    path: Optional[str] = None
    """Absolute artifact path. Takes precedence over `version`."""


# ── scoring ────────────────────────────────────────────────────────────────


def _chunk(loaded: LoadedModel, text: str) -> List[List[int]]:
    """Overlapping token windows for text longer than the model's max sequence
    length (§7.1). Overlap keeps a toxic phrase from being split across a
    boundary and diluted in both halves."""
    ids = loaded.tokenizer.encode(text, add_special_tokens=False)
    budget = MAX_LENGTH - loaded.tokenizer.num_special_tokens_to_add(pair=False)
    if len(ids) <= budget:
        return [ids]
    step = max(1, budget - CHUNK_STRIDE)
    windows = [ids[start : start + budget] for start in range(0, len(ids), step)]
    return [w for w in windows if w][:MAX_CHUNKS]


def _forward(loaded: LoadedModel, encodings) -> torch.Tensor:
    with torch.inference_mode():
        logits = loaded.model(**encodings).logits
    return torch.sigmoid(logits)


def _score_texts(loaded: LoadedModel, texts: List[str]) -> List[Dict[str, float]]:
    """Scores N texts in one forward pass. Long texts are expanded into chunks,
    all chunks across all texts are batched together, then each text's chunks
    are collapsed back with a per-label max."""
    flat: List[List[int]] = []
    owner: List[int] = []
    for index, text in enumerate(texts):
        clean = (text or "").strip()
        if not clean:
            continue
        for window in _chunk(loaded, clean):
            flat.append(window)
            owner.append(index)

    results: List[Dict[str, float]] = [
        {label: 0.0 for label in loaded.labels} for _ in texts
    ]
    if not flat:
        return results

    encodings = loaded.tokenizer.pad(
        {"input_ids": [loaded.tokenizer.build_inputs_with_special_tokens(w) for w in flat]},
        return_tensors="pt",
    )
    probs = _forward(loaded, encodings)

    for row, index in zip(probs, owner):
        target = results[index]
        for label, value in zip(loaded.labels, row):
            score = round(float(value), 4)
            if score > target[label]:
                target[label] = score
    return results


# ── endpoints ──────────────────────────────────────────────────────────────


@app.get("/healthz")
def healthz():
    loaded = _require_model()
    return {"status": "ok", "model_version": loaded.version}


@app.get("/readyz")
def readyz():
    return healthz()


@app.get("/v1/model")
def model_info(_: None = Depends(require_api_key)):
    loaded = _require_model()
    return {
        "model_version": loaded.version,
        "source": loaded.source,
        "labels": loaded.labels,
        "max_length": MAX_LENGTH,
        "loaded_at_epoch": round(loaded.loaded_at, 3),
    }


@app.post("/v1/score")
def score(req: ScoreRequest, _: None = Depends(require_api_key)):
    loaded = _require_model()
    start = time.perf_counter()
    scores = _score_texts(loaded, [req.text])[0]
    return {
        "scores": scores,
        "model_version": loaded.version,
        "inference_ms": round((time.perf_counter() - start) * 1000, 2),
    }


@app.post("/v1/score/batch")
def score_batch(req: BatchScoreRequest, _: None = Depends(require_api_key)):
    if len(req.items) > MAX_BATCH_ITEMS:
        raise HTTPException(
            status_code=413,
            detail=f"batch too large: {len(req.items)} > {MAX_BATCH_ITEMS}",
        )
    loaded = _require_model()
    start = time.perf_counter()
    scores = _score_texts(loaded, [item.text for item in req.items])
    return {
        "results": [
            {"id": item.id, "scores": s} for item, s in zip(req.items, scores)
        ],
        "model_version": loaded.version,
        "inference_ms": round((time.perf_counter() - start) * 1000, 2),
    }


@app.post("/v1/reload")
def reload_model(req: ReloadRequest, _: None = Depends(require_api_key)):
    """Hot-swaps the resident artifact (§12.4 promote/rollback) so promoting a
    retrained model does not require rolling the container. Loads the new model
    fully BEFORE taking the swap lock, so a bad artifact leaves the running one
    untouched."""
    global _current
    if req.path:
        target = Path(req.path)
    elif req.version:
        target = MODEL_ROOT / req.version
    else:
        target = MODEL_PATH

    if not _has_artifact(target):
        raise HTTPException(status_code=404, detail=f"no artifact at {target}")

    try:
        fresh = _load(target)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=422, detail=f"load failed: {exc}") from exc

    with _swap_lock:
        previous = _current.version if _current else None
        _current = fresh
    log.info("model swapped %s → %s", previous, fresh.version)
    return {"model_version": fresh.version, "previous_version": previous, "source": fresh.source}

"""
Moderation Training Service (MODERATION_ROADMAP.md §12.4).

On-demand fine-tuning job runner. Spring Boot POSTs the labeled dataset (or a
URL to it), this service fine-tunes the base checkpoint, evaluates the result
on a held-out stratified split plus a golden-set regression suite, writes a new
versioned artifact into the shared model volume, and reports metrics back.

It never connects to the application database — the dataset arrives over HTTP,
exactly like §9 requires, so the Java and Python sides stay decoupled.

Endpoints
    POST /v1/train           start a job; returns immediately with a job id
    GET  /v1/train/{job_id}  job status + eval metrics
    GET  /v1/versions        artifacts present in the shared volume
    GET  /healthz /readyz    container health checks

Promotion is deliberately NOT automatic: this service only produces and scores
an artifact. A human clicks promote in the dashboard (§12.4).
"""

from __future__ import annotations

import json
import logging
import os
import shutil
import threading
import time
import urllib.request
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional

import numpy as np
import torch
from fastapi import BackgroundTasks, Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, Field
from torch.utils.data import Dataset
from transformers import (
    AutoModelForSequenceClassification,
    AutoTokenizer,
    Trainer,
    TrainingArguments,
)

logging.basicConfig(
    level=os.environ.get("LOG_LEVEL", "INFO"),
    format="%(asctime)s %(levelname)-5s [training] %(message)s",
)
log = logging.getLogger("training")

MODEL_ROOT = Path(os.environ.get("MODEL_ROOT", "/app/model"))
DATA_ROOT = Path(os.environ.get("DATA_ROOT", "/app/data"))
BASE_CHECKPOINT = os.environ.get("BASE_CHECKPOINT", "unitary/toxic-bert")
API_KEY = os.environ.get("TRAINING_API_KEY", "")
CALLBACK_TIMEOUT = float(os.environ.get("CALLBACK_TIMEOUT_SECONDS", "10"))

LABELS = ["toxic", "severe_toxic", "obscene", "threat", "insult", "identity_hate"]

DEFAULT_EPOCHS = float(os.environ.get("TRAIN_EPOCHS", "3"))
DEFAULT_BATCH_SIZE = int(os.environ.get("TRAIN_BATCH_SIZE", "8"))
DEFAULT_LR = float(os.environ.get("TRAIN_LEARNING_RATE", "2e-5"))
DEFAULT_MAX_LENGTH = int(os.environ.get("TRAIN_MAX_LENGTH", "256"))
DEFAULT_VALIDATION_FRACTION = float(os.environ.get("TRAIN_VALIDATION_FRACTION", "0.15"))
MIN_EXAMPLES = int(os.environ.get("TRAIN_MIN_EXAMPLES", "20"))
DECISION_THRESHOLD = float(os.environ.get("EVAL_THRESHOLD", "0.5"))

MODEL_ROOT.mkdir(parents=True, exist_ok=True)
DATA_ROOT.mkdir(parents=True, exist_ok=True)

app = FastAPI(title="moderation-training-service", version="1.0.0")


def require_api_key(x_api_key: str = Header(default="", alias="X-API-Key")) -> None:
    if API_KEY and x_api_key != API_KEY:
        raise HTTPException(status_code=401, detail="invalid api key")


# ── job registry ───────────────────────────────────────────────────────────


@dataclass
class Job:
    """One fine-tuning run. Kept in memory: Spring Boot owns the durable record
    in `model_versions`, this is only the live view while the job runs."""

    id: str
    status: str = "queued"  # queued | training | evaluating | done | failed
    version: Optional[str] = None
    base_checkpoint: str = BASE_CHECKPOINT
    examples_count: int = 0
    train_count: int = 0
    validation_count: int = 0
    started_at: float = field(default_factory=time.time)
    finished_at: Optional[float] = None
    metrics: Dict[str, Any] = field(default_factory=dict)
    golden: Dict[str, Any] = field(default_factory=dict)
    artifact_path: Optional[str] = None
    error: Optional[str] = None
    notes: Optional[str] = None

    def as_dict(self) -> Dict[str, Any]:
        return {
            "job_id": self.id,
            "status": self.status,
            "version": self.version,
            "base_checkpoint": self.base_checkpoint,
            "examples_count": self.examples_count,
            "train_count": self.train_count,
            "validation_count": self.validation_count,
            "started_at_epoch": round(self.started_at, 3),
            "finished_at_epoch": round(self.finished_at, 3) if self.finished_at else None,
            "duration_seconds": round((self.finished_at or time.time()) - self.started_at, 2),
            "metrics": self.metrics,
            "golden": self.golden,
            "artifact_path": self.artifact_path,
            "error": self.error,
            "notes": self.notes,
        }


_jobs: Dict[str, Job] = {}
_jobs_lock = threading.Lock()
_train_lock = threading.Lock()
"""Only one fine-tune at a time per container. Two concurrent runs would fight
over CPU and could interleave writes into the shared artifact volume."""


# ── request models ─────────────────────────────────────────────────────────


class TrainingExample(BaseModel):
    text: str
    labels: Dict[str, int] = Field(default_factory=dict)


class GoldenCase(BaseModel):
    text: str
    labels: Dict[str, int] = Field(default_factory=dict)


class TrainRequest(BaseModel):
    examples: List[TrainingExample] = Field(default_factory=list)
    training_data_url: Optional[str] = None
    """Alternative to inline `examples` for larger datasets — a URL this
    container can GET returning the same JSON array shape."""
    golden_set: List[GoldenCase] = Field(default_factory=list)
    base_checkpoint: Optional[str] = None
    """Artifact version under MODEL_ROOT to continue from (e.g. "v3"), or a
    Hugging Face id. Omit for the configured base."""
    version: Optional[str] = None
    """Version id to write. Omit and one is derived from the artifact volume."""
    epochs: Optional[float] = None
    batch_size: Optional[int] = None
    learning_rate: Optional[float] = None
    max_length: Optional[int] = None
    validation_fraction: Optional[float] = None
    callback_url: Optional[str] = None
    """Spring Boot webhook called once with the terminal job payload."""
    callback_token: Optional[str] = None
    notes: Optional[str] = None


# ── dataset ────────────────────────────────────────────────────────────────


class LabeledDataset(Dataset):
    def __init__(self, encodings, labels: np.ndarray):
        self.encodings = encodings
        self.labels = labels

    def __len__(self) -> int:
        return len(self.labels)

    def __getitem__(self, idx: int):
        item = {key: torch.tensor(value[idx]) for key, value in self.encodings.items()}
        item["labels"] = torch.tensor(self.labels[idx], dtype=torch.float)
        return item


def _fetch_examples(req: TrainRequest) -> List[TrainingExample]:
    if req.examples:
        return req.examples
    if not req.training_data_url:
        return []
    log.info("fetching training data from %s", req.training_data_url)
    with urllib.request.urlopen(req.training_data_url, timeout=60) as response:
        payload = json.loads(response.read().decode("utf-8"))
    rows = payload.get("examples", payload) if isinstance(payload, dict) else payload
    return [TrainingExample(**row) for row in rows]


def _to_matrix(examples: List[TrainingExample]) -> tuple[List[str], np.ndarray]:
    texts = [ex.text for ex in examples]
    matrix = np.array(
        [[float(ex.labels.get(label, 0)) for label in LABELS] for ex in examples],
        dtype=np.float32,
    )
    return texts, matrix


def _stratified_split(matrix: np.ndarray, fraction: float) -> tuple[List[int], List[int]]:
    """Label-aware split (§18.4). A plain random split on a small, heavily
    imbalanced multi-label set routinely produces a validation fold with zero
    positives for `threat` — which makes that label's recall undefined and the
    promotion gate meaningless. Walking rarest-label-first guarantees each label
    contributes at least one positive to validation whenever it has two rows."""
    total = len(matrix)
    target = max(1, int(round(total * fraction))) if total > 1 else 0
    if target == 0:
        return list(range(total)), []

    validation: List[int] = []
    taken = set()
    order = np.argsort(matrix.sum(axis=0))  # rarest label first
    for label_index in order:
        positives = [i for i in np.where(matrix[:, label_index] > 0)[0] if i not in taken]
        if len(positives) >= 2 and len(validation) < target:
            pick = positives[len(positives) // 2]
            validation.append(int(pick))
            taken.add(int(pick))

    # Top up with a deterministic stride over what is left, so repeat runs on an
    # unchanged dataset produce the same folds and metrics stay comparable.
    remaining = [i for i in range(total) if i not in taken]
    stride = max(1, len(remaining) // max(1, target - len(validation))) if target > len(validation) else 1
    for i in remaining[::stride]:
        if len(validation) >= target:
            break
        validation.append(int(i))
        taken.add(int(i))

    train = [i for i in range(total) if i not in taken]
    if not train:  # never hand Trainer an empty train fold
        train = [validation.pop()]
    return sorted(train), sorted(validation)


# ── evaluation ─────────────────────────────────────────────────────────────


def _per_label_metrics(probs: np.ndarray, truth: np.ndarray) -> Dict[str, Any]:
    predicted = (probs >= DECISION_THRESHOLD).astype(np.float32)
    per_label: Dict[str, Any] = {}
    f1s: List[float] = []
    for index, label in enumerate(LABELS):
        tp = float(((predicted[:, index] == 1) & (truth[:, index] == 1)).sum())
        fp = float(((predicted[:, index] == 1) & (truth[:, index] == 0)).sum())
        fn = float(((predicted[:, index] == 0) & (truth[:, index] == 1)).sum())
        support = float(truth[:, index].sum())
        precision = tp / (tp + fp) if (tp + fp) else 0.0
        recall = tp / (tp + fn) if (tp + fn) else 0.0
        f1 = 2 * precision * recall / (precision + recall) if (precision + recall) else 0.0
        per_label[label] = {
            "precision": round(precision, 4),
            "recall": round(recall, 4),
            "f1": round(f1, 4),
            "support": int(support),
            # An F1 computed over zero positives is noise, not a score. Flagging
            # it keeps the promotion gate from "passing" on empty labels.
            "evaluated": support > 0,
        }
        if support > 0:
            f1s.append(f1)
    return {
        "per_label": per_label,
        "macro_f1": round(float(np.mean(f1s)) if f1s else 0.0, 4),
        "threshold": DECISION_THRESHOLD,
        "labels_evaluated": len(f1s),
    }


def _predict(model, tokenizer, texts: List[str], max_length: int) -> np.ndarray:
    model.eval()
    outputs: List[np.ndarray] = []
    for start in range(0, len(texts), 16):
        batch = texts[start : start + 16]
        encodings = tokenizer(
            batch, return_tensors="pt", truncation=True, padding=True, max_length=max_length
        )
        with torch.inference_mode():
            logits = model(**encodings).logits
        outputs.append(torch.sigmoid(logits).cpu().numpy())
    return np.vstack(outputs) if outputs else np.zeros((0, len(LABELS)), dtype=np.float32)


def _golden_report(model, tokenizer, cases: List[GoldenCase], max_length: int) -> Dict[str, Any]:
    """Golden-set regression (§17). Aggregate F1 can stay flat while a specific
    known-bad phrase starts scoring clean, so every case is reported by name."""
    if not cases:
        return {"total": 0, "passed": 0, "failed": 0, "cases": []}
    texts = [case.text for case in cases]
    truth = np.array(
        [[float(case.labels.get(label, 0)) for label in LABELS] for case in cases],
        dtype=np.float32,
    )
    probs = _predict(model, tokenizer, texts, max_length)
    predicted = (probs >= DECISION_THRESHOLD).astype(np.float32)

    cases_out = []
    passed = 0
    for index, case in enumerate(cases):
        ok = bool((predicted[index] == truth[index]).all())
        passed += int(ok)
        cases_out.append(
            {
                "text": case.text[:200],
                "passed": ok,
                "expected": {label: int(truth[index][i]) for i, label in enumerate(LABELS)},
                "scores": {label: round(float(probs[index][i]), 4) for i, label in enumerate(LABELS)},
            }
        )
    return {
        "total": len(cases),
        "passed": passed,
        "failed": len(cases) - passed,
        "pass_rate": round(passed / len(cases), 4),
        "cases": cases_out,
    }


# ── the job ────────────────────────────────────────────────────────────────


def _resolve_checkpoint(requested: Optional[str]) -> str:
    if not requested:
        return BASE_CHECKPOINT
    candidate = MODEL_ROOT / requested
    if (candidate / "config.json").exists():
        return str(candidate)
    return requested  # treat as a Hugging Face id


def _next_version(explicit: Optional[str]) -> str:
    if explicit:
        return explicit
    existing = [
        int(path.name[1:])
        for path in MODEL_ROOT.iterdir()
        if path.is_dir() and path.name.startswith("v") and path.name[1:].isdigit()
    ]
    return f"v{(max(existing) + 1) if existing else 1}"


def _post_callback(url: str, token: Optional[str], payload: Dict[str, Any]) -> None:
    try:
        body = json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(url, data=body, method="POST")
        request.add_header("Content-Type", "application/json")
        if token:
            request.add_header("X-Training-Token", token)
        with urllib.request.urlopen(request, timeout=CALLBACK_TIMEOUT) as response:
            log.info("callback %s -> %s", url, response.status)
    except Exception as exc:  # noqa: BLE001 — a dead webhook must not fail the run
        log.error("callback to %s failed: %s — Spring Boot will fall back to polling", url, exc)


def _run_job(job: Job, req: TrainRequest) -> None:
    artifact_dir: Optional[Path] = None
    try:
        with _train_lock:
            job.status = "training"
            examples = _fetch_examples(req)
            job.examples_count = len(examples)
            if len(examples) < MIN_EXAMPLES:
                raise ValueError(
                    f"need at least {MIN_EXAMPLES} training examples, got {len(examples)}"
                )

            max_length = req.max_length or DEFAULT_MAX_LENGTH
            checkpoint = _resolve_checkpoint(req.base_checkpoint)
            job.base_checkpoint = checkpoint

            texts, matrix = _to_matrix(examples)
            train_idx, val_idx = _stratified_split(
                matrix, req.validation_fraction or DEFAULT_VALIDATION_FRACTION
            )
            job.train_count = len(train_idx)
            job.validation_count = len(val_idx)
            log.info(
                "job %s — %d examples (%d train / %d validation) from %s",
                job.id, len(examples), len(train_idx), len(val_idx), checkpoint,
            )

            tokenizer = AutoTokenizer.from_pretrained(checkpoint)
            model = AutoModelForSequenceClassification.from_pretrained(
                checkpoint,
                num_labels=len(LABELS),
                problem_type="multi_label_classification",
                id2label={i: label for i, label in enumerate(LABELS)},
                label2id={label: i for i, label in enumerate(LABELS)},
                ignore_mismatched_sizes=True,
            )

            def encode(indices: List[int]):
                return tokenizer(
                    [texts[i] for i in indices],
                    truncation=True,
                    padding="max_length",
                    max_length=max_length,
                )

            train_ds = LabeledDataset(encode(train_idx), matrix[train_idx])

            output_dir = DATA_ROOT / "runs" / job.id
            args = TrainingArguments(
                output_dir=str(output_dir),
                num_train_epochs=req.epochs or DEFAULT_EPOCHS,
                per_device_train_batch_size=req.batch_size or DEFAULT_BATCH_SIZE,
                learning_rate=req.learning_rate or DEFAULT_LR,
                logging_steps=10,
                save_strategy="no",
                report_to=[],
                use_cpu=not torch.cuda.is_available(),
            )
            Trainer(model=model, args=args, train_dataset=train_ds).train()

            job.status = "evaluating"
            if val_idx:
                probs = _predict(model, tokenizer, [texts[i] for i in val_idx], max_length)
                job.metrics = _per_label_metrics(probs, matrix[val_idx])
            else:
                job.metrics = {
                    "per_label": {},
                    "macro_f1": 0.0,
                    "labels_evaluated": 0,
                    "warning": "dataset too small to hold out a validation split",
                }
            job.golden = _golden_report(model, tokenizer, req.golden_set, max_length)

            version = _next_version(req.version)
            artifact_dir = MODEL_ROOT / version
            artifact_dir.mkdir(parents=True, exist_ok=True)
            model.save_pretrained(artifact_dir)
            tokenizer.save_pretrained(artifact_dir)
            (artifact_dir / "VERSION").write_text(version + "\n")
            (artifact_dir / "training_metadata.json").write_text(
                json.dumps(
                    {
                        "version": version,
                        "job_id": job.id,
                        "base_checkpoint": checkpoint,
                        "examples_count": job.examples_count,
                        "train_count": job.train_count,
                        "validation_count": job.validation_count,
                        "metrics": job.metrics,
                        "golden": {k: v for k, v in job.golden.items() if k != "cases"},
                        "labels": LABELS,
                        "notes": req.notes,
                    },
                    indent=2,
                )
            )
            shutil.rmtree(output_dir, ignore_errors=True)

            job.version = version
            job.artifact_path = str(artifact_dir)
            job.status = "done"
            log.info("job %s done — artifact %s macro_f1=%s",
                     job.id, artifact_dir, job.metrics.get("macro_f1"))

    except Exception as exc:  # noqa: BLE001
        job.status = "failed"
        job.error = str(exc)
        log.exception("job %s failed", job.id)
        # A half-written artifact directory would look promotable to the
        # dashboard. Remove it so only complete runs are ever selectable.
        if artifact_dir and job.status == "failed":
            shutil.rmtree(artifact_dir, ignore_errors=True)
    finally:
        job.finished_at = time.time()
        if req.callback_url:
            _post_callback(req.callback_url, req.callback_token, job.as_dict())


# ── endpoints ──────────────────────────────────────────────────────────────


@app.get("/healthz")
def healthz():
    return {"status": "ok", "model_root": str(MODEL_ROOT), "labels": LABELS}


@app.get("/readyz")
def readyz():
    running = any(job.status in ("queued", "training", "evaluating") for job in _jobs.values())
    return {"status": "busy" if running else "ok"}


@app.post("/v1/train")
def train(req: TrainRequest, background: BackgroundTasks, _: None = Depends(require_api_key)):
    job = Job(id=uuid.uuid4().hex[:16], notes=req.notes)
    with _jobs_lock:
        _jobs[job.id] = job
    background.add_task(_run_job, job, req)
    return {"job_id": job.id, "status": job.status}


@app.get("/v1/train/{job_id}")
def train_status(job_id: str, _: None = Depends(require_api_key)):
    job = _jobs.get(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail="unknown job")
    return job.as_dict()


@app.get("/v1/versions")
def versions(_: None = Depends(require_api_key)):
    out = []
    for path in sorted(MODEL_ROOT.iterdir()) if MODEL_ROOT.exists() else []:
        if not path.is_dir() or not (path / "config.json").exists():
            continue
        meta_file = path / "training_metadata.json"
        meta = json.loads(meta_file.read_text()) if meta_file.exists() else {}
        out.append({"version": path.name, "path": str(path), "metadata": meta})
    return {"versions": out}

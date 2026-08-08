#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
FINE-TUNE THE MODERATION MODEL — one command
════════════════════════════════════════════════════════════════════════════════

Walks the whole loop: load examples → start a training run → wait → show the
result → tell you whether it is safe to promote.

    python3 scripts/train_moderation.py --user <admin> --password <pw>
    python3 scripts/train_moderation.py --user admin --password pw --examples my_data.jsonl
    python3 scripts/train_moderation.py --user admin --password pw --promote

Prerequisites:
  1. The app running on :8080
  2. The training container up:
         docker compose --profile on-demand up -d model-training
  3. An ADMIN account

--examples takes a JSONL file, one object per line:
    {"text": "…", "labels": {"toxic": 1, "insult": 1}}
Labels you omit default to 0. A row with no labels at all is a *clean* example —
you need plenty of those too, or the model learns that everything is toxic.

With no --examples, a small built-in starter set is loaded so you can watch the
pipeline work end to end. It is NOT a real training set: see §18 of the roadmap.
For production, load an established labeled corpus with the same six labels
(e.g. Jigsaw Toxic Comment Classification) and your own admin corrections.
"""

import argparse
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://localhost:8080"
TIMEOUT = 120
LABELS = ["toxic", "severe_toxic", "obscene", "threat", "insult", "identity_hate"]

# Deliberately mild. Enough to exercise the pipeline; not a substitute for a
# real corpus. Roughly half clean on purpose — a set of only-toxic rows teaches
# the model to flag everything.
STARTER = [
    ("Thank you for sharing this, it was genuinely useful.", {}),
    ("Beautiful reflection, may Allah reward you.", {}),
    ("I disagree with the conclusion but the methodology is sound.", {}),
    ("Could you add a citation for the second claim?", {}),
    ("This is a really helpful summary, bookmarking it.", {}),
    ("Great lecture, the section on usul was especially clear.", {}),
    ("Respectfully, I think you misread the hadith here.", {}),
    ("Looking forward to the next part of this series.", {}),
    ("The dataset appendix answered my question, thanks.", {}),
    ("Well argued, even where I'd push back.", {}),
    ("Jazak Allah khair for taking the time to write this.", {}),
    ("Solid work — the references check out.", {}),
    ("you are a complete idiot and everyone knows it", {"toxic": 1, "insult": 1}),
    ("shut up you moron, nobody asked", {"toxic": 1, "insult": 1}),
    ("what a stupid worthless post from a stupid person", {"toxic": 1, "insult": 1}),
    ("get lost, you are pathetic and useless", {"toxic": 1, "insult": 1}),
    ("nobody wants you here, just disappear", {"toxic": 1}),
    ("this is garbage written by a clown", {"toxic": 1, "insult": 1}),
    ("you're an embarrassment and a fool", {"toxic": 1, "insult": 1}),
    ("only an imbecile would believe this nonsense", {"toxic": 1, "insult": 1}),
    ("I will find you and make you regret it", {"toxic": 1, "threat": 1}),
    ("you should be afraid of what happens next", {"toxic": 1, "threat": 1}),
    ("people like you don't belong in this country", {"toxic": 1, "identity_hate": 1}),
    ("your kind always ruins everything", {"toxic": 1, "identity_hate": 1}),
]

# Held-out regression cases — never trained on, must keep passing before a
# promote. The valuable ones are the *hard* ones.
GOLDEN = [
    ("Thank you for this, it clarified a lot.", {}),
    ("I strongly disagree with this interpretation.", {}),
    ("The patient presented with acute abdominal pain.", {}),
    ("you are an idiot", {"toxic": 1, "insult": 1}),
    ("I hope something bad happens to you", {"toxic": 1, "threat": 1}),
]


def http(method, path, token=None, body=None, params=None):
    url = BASE + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Accept", "application/json")
    if data:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:                                    # noqa: BLE001
        return 0, str(e)


def js(raw):
    try:
        return json.loads(raw)
    except Exception:                                         # noqa: BLE001
        return {}


def step(n, msg):
    print(f"\n\033[1m[{n}]\033[0m {msg}")


def load_examples(path):
    rows = []
    with open(path, encoding="utf-8") as fh:
        for i, line in enumerate(fh, 1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            try:
                doc = json.loads(line)
            except json.JSONDecodeError:
                sys.exit(f"  line {i} is not valid JSON: {line[:60]}")
            if not doc.get("text"):
                sys.exit(f"  line {i} has no 'text'")
            rows.append((doc["text"], doc.get("labels") or {}))
    return rows


def main():
    global BASE

    ap = argparse.ArgumentParser()
    ap.add_argument("--user", required=True, help="ADMIN username or email")
    ap.add_argument("--password", required=True)
    ap.add_argument("--examples", help="JSONL file; omit to use the built-in starter set")
    ap.add_argument("--promote", action="store_true",
                    help="promote automatically if the gate passes (default: ask you to)")
    ap.add_argument("--notes", default="fine-tune via train_moderation.py")
    ap.add_argument("--base", default=BASE)
    args = ap.parse_args()
    BASE = args.base

    print("═" * 78)
    print("  FINE-TUNE THE MODERATION MODEL")
    print("═" * 78)

    # ── log in ──────────────────────────────────────────────────────────
    status, raw = http("POST", "/api/v1/auth/login",
                       body={"username": args.user, "password": args.password})
    token = js(raw).get("accessToken")
    if not token:
        sys.exit(f"  login failed (HTTP {status}): {raw[:200]}")
    print(f"  signed in as {args.user}")

    # ── 1. load the dataset ─────────────────────────────────────────────
    rows = load_examples(args.examples) if args.examples else STARTER
    step(1, f"Loading {len(rows)} training example(s)"
            + (f" from {args.examples}" if args.examples else " (built-in starter set)"))

    added = failed = 0
    for text, labels in rows:
        s, r = http("POST", "/api/v1/admin/moderation/model/training-examples", token,
                    {"text": text, "labels": labels, "note": "train_moderation.py"})
        if s in (200, 201):
            added += 1
        else:
            failed += 1
            if failed <= 3:
                print(f"    ! {js(r).get('errorCode', s)}: {js(r).get('message', r)[:80]}")
    print(f"  added/updated {added}, failed {failed}")

    if not args.examples:
        step("1b", f"Loading {len(GOLDEN)} golden regression case(s)")
        g = 0
        for text, labels in GOLDEN:
            s, _ = http("POST", "/api/v1/admin/moderation/model/golden-cases", token,
                        {"text": text, "labels": labels, "note": "starter golden set"})
            g += 1 if s in (200, 201) else 0
        print(f"  added/updated {g}")

    s, raw = http("GET", "/api/v1/admin/moderation/model/training-examples", token,
                  params={"pageSize": 1})
    summary = js(raw).get("summary", {})
    print(f"  dataset now: {summary.get('total')} examples "
          f"({summary.get('untrained')} untrained), {summary.get('goldenCases')} golden")

    # ── 2. start the run ────────────────────────────────────────────────
    step(2, "Starting the training run")
    s, raw = http("POST", "/api/v1/admin/moderation/model/retrain", token,
                  {"notes": args.notes})
    doc = js(raw)
    if s not in (200, 202):
        code = doc.get("errorCode")
        print(f"  FAILED ({code}): {doc.get('message', raw)[:160]}")
        if code == "TRAINING_SERVICE_UNAVAILABLE":
            print("\n  → Start the training container first:")
            print("      docker compose --profile on-demand up -d model-training")
        elif code == "STEP_UP_REQUIRED":
            print("\n  → This endpoint needs step-up auth. Arm it first:")
            print("      POST /api/v1/security/step-up  {\"password\": \"…\"}")
        elif code == "TRAINING_DATASET_TOO_SMALL":
            print("\n  → Add more examples and re-run.")
        sys.exit(1)

    version_id = doc.get("id")
    print(f"  job {doc.get('jobId')} started (registry row {doc.get('version')})")

    # ── 3. wait ─────────────────────────────────────────────────────────
    step(3, "Training — this takes a few minutes on CPU")
    last = None
    for _ in range(240):                       # up to ~20 min
        time.sleep(5)
        http("POST", "/api/v1/admin/moderation/model/retrain/refresh", token)
        s, raw = http("GET", "/api/v1/admin/moderation/model/versions", token,
                      params={"pageSize": 10})
        row = next((v for v in js(raw).get("items", []) if v.get("id") == version_id), None)
        if not row:
            continue
        if row["status"] != last:
            last = row["status"]
            print(f"    {last.lower()}…")
        if row["status"] in ("READY", "FAILED"):
            break
    else:
        sys.exit("  timed out waiting; check `docker logs irc-model-training-1`")

    if row["status"] == "FAILED":
        sys.exit(f"\n  training FAILED: {row.get('error')}")

    # ── 4. the verdict ──────────────────────────────────────────────────
    step(4, "Result")
    print(f"  version        {row['version']}")
    print(f"  macro F1       {row.get('macroF1')}")
    print(f"  trained on     {row.get('trainingExamples')} examples "
          f"({row.get('validationCount')} held out for validation)")
    print(f"  promotion gate {'PASS' if row.get('gatePassed') else 'FAIL'} — {row.get('gateDetail')}")

    if not row.get("gatePassed"):
        print("\n  The gate failed, so this version is NOT promoted.")
        print("  Either fix the dataset and retrain, or override deliberately:")
        print(f"    POST /api/v1/admin/moderation/model/versions/{version_id}/promote  "
              '{"force": true}')
        return

    if not args.promote:
        print("\n  Gate passed. Nothing is live yet — promotion is always a human decision.")
        print("  To make it the active model:")
        print(f"    POST /api/v1/admin/moderation/model/versions/{version_id}/promote")
        print("  (or re-run this script with --promote)")
        return

    step(5, "Promoting")
    s, raw = http("POST", f"/api/v1/admin/moderation/model/versions/{version_id}/promote",
                  token, {"force": False})
    if s == 200:
        print(f"  {js(raw).get('version')} is now ACTIVE")
        print("\n  Watch these for the next hour (a bad promote shows up here, not in the gate):")
        print("    GET /api/v1/admin/moderation/review/metrics")
        print("      · bands.autoDecidedPercent  — should stay high")
        print("      · queue.inReview            — should stay flat")
        print("  Rollback if it regressed:  POST /api/v1/admin/moderation/model/rollback")
    else:
        print(f"  promote failed (HTTP {s}): {js(raw).get('message', raw)[:200]}")


if __name__ == "__main__":
    main()

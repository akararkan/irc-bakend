#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AUTOMATED MODERATION — END-TO-END VERIFICATION
════════════════════════════════════════════════════════════════════════════════

Drives the whole quarantine-then-publish pipeline against a running backend and
reports what actually happened at each step, so a misconfiguration can be told
apart from a working system that is simply strict.

    python3 scripts/verify_moderation.py
    python3 scripts/verify_moderation.py --only pipeline
    python3 scripts/verify_moderation.py --text "custom string to score"

What it checks, in order:

  1. containers   model-inference reachable, which artifact is resident
  2. settings     effective thresholds/holds/fallbacks the app is enforcing
  3. scoring      the admin score-probe against benign and abusive text
  4. pipeline     posts a benign post (expect visible) and an abusive one
                  (expect CONTENT_REJECTED or held), then re-reads as a SECOND
                  user to prove held content is not visible to anyone else
  5. queue        the review queue and its counts
  6. dataset      training examples, golden cases, model registry
  7. dryrun       replays a proposed threshold against stored scores

Accounts come from scripts/seed_full_demo.py. Run that first if you have not.
"""

import argparse
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://localhost:8080"
INFERENCE = "http://localhost:8000"
ADMIN_USER = "yusuf_admin"
USER_A = "amina_researcher"
USER_B = "kareem_scholar"
PASSWORD = "Passw0rd!2026"
TIMEOUT = 60

# Deliberately mild — enough to move a toxicity classifier off zero without
# putting anything genuinely vile in a repo file.
ABUSIVE = "you are a complete idiot and everyone hates you, shut up you moron"
BENIGN = "Really thoughtful paper, thanks for sharing the dataset appendix."

PASS, FAIL, WARN, INFO = "PASS", "FAIL", "WARN", "INFO"
RESULTS = []


def http(method, path, token=None, body=None, params=None, base=None):
    url = (base or BASE) + path
    if params:
        url += "?" + urllib.parse.urlencode(
            {k: v for k, v in params.items() if v is not None}, doseq=True)
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
        return None


def record(step, verdict, detail):
    RESULTS.append((step, verdict, detail))
    mark = {PASS: "  ok  ", FAIL: " FAIL ", WARN: " warn ", INFO: "  ··  "}[verdict]
    print(f"[{mark}] {step:<34} {detail}")


def login(username):
    status, raw = http("POST", "/api/v1/auth/login",
                       body={"usernameOrEmail": username, "password": PASSWORD})
    doc = js(raw) or {}
    token = doc.get("accessToken") or (doc.get("data") or {}).get("accessToken")
    if not token:
        record("login " + username, FAIL, f"HTTP {status} — run seed_full_demo.py first")
    return token


# ── 1. containers ───────────────────────────────────────────────────────────

def check_containers():
    status, raw = http("GET", "/healthz", base=INFERENCE)
    doc = js(raw) or {}
    if status == 200:
        version = doc.get("model_version", "?")
        record("inference /healthz", PASS, f"up, serving {version}")
        if str(version).startswith("base:"):
            record("inference artifact", WARN,
                   "running the BASE checkpoint, not a fine-tuned artifact — "
                   "scores are the stock toxic-bert, not yours")
    else:
        record("inference /healthz", FAIL,
               f"HTTP {status} — start it: docker compose up -d model-inference")

    status, _ = http("GET", "/healthz", base="http://localhost:8001")
    record("training /healthz", INFO if status != 200 else PASS,
           "not running (expected — it is on-demand)" if status != 200 else "up")


# ── 2. settings ─────────────────────────────────────────────────────────────

def check_settings(admin):
    status, raw = http("GET", "/api/v1/admin/moderation/settings", token=admin)
    doc = js(raw) or {}
    if status != 200:
        record("settings", FAIL, f"HTTP {status} {raw[:120]}")
        return None

    effective = doc.get("effective") or {}
    if not effective.get("enabled", False):
        record("moderation enabled", FAIL,
               "app.moderation.enabled is FALSE — only the blocklist is enforced")
    else:
        record("moderation enabled", PASS, "true")

    if doc.get("warning"):
        record("settings warning", WARN, doc["warning"])

    post = (effective.get("entityTypes") or {}).get("post") or {}
    record("post policy", INFO,
           f"hold={post.get('holdMs')}ms inline={post.get('inlineMs')}ms "
           f"fallback={post.get('fallback')}")
    bands = post.get("thresholds") or {}
    for label in ("threat", "identity_hate"):
        band = bands.get(label) or {}
        if band.get("high", 1) > 0.6:
            record(f"band {label}", WARN,
                   f"high={band.get('high')} is loose for this label")
    record("overrides stored", INFO, str(len(doc.get("overrides") or {})))

    model = doc.get("model") or {}
    record("circuit breaker", PASS if model.get("circuit") == "CLOSED" else WARN,
           str(model.get("circuit")))
    return doc


# ── 3. scoring ──────────────────────────────────────────────────────────────

def check_scoring(admin, custom=None):
    for label, text in (("benign", BENIGN), ("abusive", custom or ABUSIVE)):
        status, raw = http("POST", "/api/v1/admin/moderation/model/score-probe",
                           token=admin, body={"text": text})
        doc = js(raw) or {}
        if status != 200:
            record(f"score-probe {label}", FAIL, f"HTTP {status} {raw[:120]}")
            continue
        scores = doc.get("scores") or {}
        top = max(scores.items(), key=lambda kv: kv[1]) if scores else ("—", 0)
        record(f"score-probe {label}", PASS,
               f"top={top[0]} {top[1]:.4f} model={doc.get('modelVersion')} "
               f"{doc.get('inferenceMs')}ms")
        # The seed model is tiny; a benign string scoring high is the loudest
        # possible signal that the artifact is not fit to auto-decide yet.
        if label == "benign" and top[1] > 0.5:
            record("benign false positive", WARN,
                   f"clean text scored {top[1]:.2f} on {top[0]} — expect over-blocking")
        if label == "abusive" and top[1] < 0.3:
            record("abusive false negative", WARN,
                   f"abusive text scored only {top[1]:.2f} — expect under-blocking")


# ── 4. the pipeline ─────────────────────────────────────────────────────────

def check_pipeline(token_a, token_b):
    created = {}

    for label, text in (("benign", BENIGN), ("abusive", ABUSIVE)):
        status, raw = http("POST", "/api/v1/posts", token=token_a,
                           body={"textContent": text, "postType": "TEXT",
                                 "visibility": "PUBLIC"})
        doc = js(raw) or {}

        if status in (200, 201):
            post_status = doc.get("status")
            created[label] = (doc.get("id"), post_status)
            if label == "benign" and post_status == "PUBLISHED":
                record("create benign post", PASS, "PUBLISHED inline")
            elif label == "benign":
                record("create benign post", WARN,
                       f"status={post_status} — held; thresholds may be too tight, "
                       "or the inference container is down")
            else:
                record("create abusive post", WARN,
                       f"status={post_status} — not blocked outright "
                       "(held is a valid outcome; PUBLISHED is not)")
                if post_status == "PUBLISHED":
                    record("abusive published", FAIL,
                           "abusive text published inline — check thresholds")
        elif status == 400 and (doc.get("errorCode") in
                                ("CONTENT_REJECTED", "CONTENT_BLOCKED_BY_POLICY")):
            record(f"create {label} post", PASS if label == "abusive" else FAIL,
                   f"{doc.get('errorCode')} — {'blocked as expected' if label == 'abusive' else 'CLEAN TEXT WAS BLOCKED'}")
        else:
            record(f"create {label} post", FAIL, f"HTTP {status} {raw[:140]}")

    # The invariant that matters: a held post must not be visible to anyone else.
    for label, (post_id, post_status) in created.items():
        if not post_id or post_status == "PUBLISHED":
            continue
        time.sleep(1)
        status, raw = http("GET", f"/api/v1/posts/{post_id}", token=token_b)
        if status == 404:
            record(f"held {label} hidden from others", PASS, "404 for a second user")
        elif status == 200:
            record(f"held {label} hidden from others", FAIL,
                   "SECOND USER CAN READ HELD CONTENT — this is the core invariant")
        else:
            record(f"held {label} hidden from others", WARN, f"HTTP {status}")

        status, raw = http("GET", f"/api/v1/posts/{post_id}", token=token_a)
        record(f"held {label} visible to author", PASS if status == 200 else FAIL,
               f"HTTP {status}")

    return created


# ── 5. queue ────────────────────────────────────────────────────────────────

def check_queue(admin):
    status, raw = http("GET", "/api/v1/admin/moderation/review", token=admin,
                       params={"status": "IN_REVIEW", "pageSize": 5})
    doc = js(raw) or {}
    if status != 200:
        record("review queue", FAIL, f"HTTP {status} {raw[:140]}")
        return
    counts = doc.get("counts") or {}
    record("review queue", PASS,
           f"inReview={counts.get('inReview')} pending={counts.get('pending')} "
           f"slaBreached={counts.get('slaBreached')}")
    if (counts.get("pending") or 0) > 20:
        record("pending backlog", WARN,
               "many cases stuck PENDING — the inference container is probably down")

    items = doc.get("items") or []
    if items:
        case_id = items[0]["caseId"]
        status, raw = http("GET", f"/api/v1/admin/moderation/review/{case_id}",
                           token=admin)
        detail = js(raw) or {}
        fields = detail.get("fields") or []
        record("case detail", PASS if status == 200 else FAIL,
               f"{len(fields)} field(s), thresholds={'yes' if detail.get('thresholds') else 'no'}")

    status, raw = http("GET", "/api/v1/admin/moderation/review/metrics",
                       token=admin, params={"windowHours": 24})
    metrics = js(raw) or {}
    if status == 200:
        bands = metrics.get("bands") or {}
        record("metrics", PASS,
               f"autoDecided={bands.get('autoDecidedPercent')}% "
               f"approved={bands.get('autoApproved')} rejected={bands.get('autoRejected')} "
               f"review={bands.get('sentToReview')}")
        model = metrics.get("model") or {}
        if not model.get("registryInSync", True):
            record("registry in sync", WARN,
                   "the resident artifact is not the one the registry calls active")
    else:
        record("metrics", FAIL, f"HTTP {status}")


# ── 6. dataset + registry ───────────────────────────────────────────────────

def check_dataset(admin):
    status, raw = http("GET", "/api/v1/admin/moderation/model/training-examples",
                       token=admin, params={"pageSize": 5})
    doc = js(raw) or {}
    if status != 200:
        record("training examples", FAIL, f"HTTP {status} {raw[:140]}")
        return
    summary = doc.get("summary") or {}
    total = summary.get("total", 0)
    record("training examples", PASS,
           f"{total} total, {summary.get('untrained')} untrained, "
           f"{summary.get('goldenCases')} golden")
    if total < 100:
        record("dataset size", WARN,
               f"{total} examples is proof-of-concept size — roadmap §18.1. "
               "Do not rely on auto-decisions yet.")

    status, raw = http("GET", "/api/v1/admin/moderation/model/versions", token=admin)
    doc = js(raw) or {}
    if status == 200:
        items = doc.get("items") or []
        active = [v for v in items if v.get("status") == "ACTIVE"]
        record("model registry", PASS,
               f"{len(items)} version(s), active={active[0]['version'] if active else 'none'}")
    else:
        record("model registry", FAIL, f"HTTP {status}")


# ── 7. dry-run ──────────────────────────────────────────────────────────────

def check_dryrun(admin):
    status, raw = http("GET", "/api/v1/admin/moderation/review", token=admin,
                       params={"status": "IN_REVIEW", "pageSize": 20})
    items = (js(raw) or {}).get("items") or []
    if not items:
        record("threshold dry-run", INFO, "no stored cases to replay against yet")
        return
    case_ids = [i["caseId"] for i in items]
    status, raw = http("POST", "/api/v1/admin/moderation/settings/dry-run",
                       token=admin,
                       body={"entityType": "post",
                             "labels": {"insult": {"low": 0.6}},
                             "caseIds": case_ids})
    doc = js(raw) or {}
    if status == 200:
        record("threshold dry-run", PASS,
               f"evaluated={doc.get('evaluated')} unchanged={doc.get('unchanged')} "
               f"would-change={len(doc.get('changed') or [])}")
    else:
        record("threshold dry-run", FAIL, f"HTTP {status} {raw[:140]}")


# ── main ────────────────────────────────────────────────────────────────────

SECTIONS = ("containers", "settings", "scoring", "pipeline", "queue", "dataset", "dryrun")


def main():
    global BASE

    parser = argparse.ArgumentParser()
    parser.add_argument("--only", choices=SECTIONS, help="run one section")
    parser.add_argument("--text", help="custom string to score in the scoring section")
    parser.add_argument("--base", default=BASE)
    args = parser.parse_args()

    BASE = args.base
    wanted = (args.only,) if args.only else SECTIONS

    print("═" * 78)
    print("  AUTOMATED MODERATION VERIFICATION")
    print("═" * 78)

    if "containers" in wanted:
        check_containers()

    admin = login(ADMIN_USER)
    if not admin:
        sys.exit(1)

    if "settings" in wanted:
        check_settings(admin)
    if "scoring" in wanted:
        check_scoring(admin, args.text)
    if "pipeline" in wanted:
        token_a = login(USER_A)
        token_b = login(USER_B)
        if token_a and token_b:
            check_pipeline(token_a, token_b)
    if "queue" in wanted:
        check_queue(admin)
    if "dataset" in wanted:
        check_dataset(admin)
    if "dryrun" in wanted:
        check_dryrun(admin)

    print("═" * 78)
    fails = [r for r in RESULTS if r[1] == FAIL]
    warns = [r for r in RESULTS if r[1] == WARN]
    print(f"  {len(RESULTS)} checks — {len(fails)} FAIL, {len(warns)} warn")
    for step, _, detail in fails:
        print(f"    FAIL  {step}: {detail}")
    print("═" * 78)
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()

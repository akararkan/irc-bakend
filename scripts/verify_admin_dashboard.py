#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ADMIN DASHBOARD VERIFICATION
════════════════════════════════════════════════════════════════════════════════

Logs in as the seeded admin and calls every read endpoint the admin dashboard
is built on, reporting for each one whether it answers and whether it answers
with *data* — so an empty panel can be told apart from a broken one.

    python3 scripts/verify_admin_dashboard.py
    python3 scripts/verify_admin_dashboard.py --section analytics
"""

import argparse
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter

BASE = "http://localhost:8080"
ADMIN_USER = "yusuf_admin"
PASSWORD = "Passw0rd!2026"
TIMEOUT = 45

TOKEN = None
COUNTS = Counter()
ROWS = []


def http(method, path, token=None, body=None, params=None):
    url = BASE + path
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
    except Exception as e:
        return 0, str(e)


def size_of(doc):
    """How much data came back — page rows, list length, or populated keys."""
    if doc is None:
        return 0
    if isinstance(doc, list):
        return len(doc)
    if isinstance(doc, dict):
        for key in ("content", "items", "rows", "data", "results", "events",
                    "entries", "series"):
            v = doc.get(key)
            if isinstance(v, list):
                return len(v)
        if "totalElements" in doc:
            return int(doc["totalElements"] or 0)
        meaningful = 0
        for v in doc.values():
            if isinstance(v, (int, float)) and v:
                meaningful += 1
            elif isinstance(v, (list, dict)) and v:
                meaningful += 1
            elif isinstance(v, str) and v:
                meaningful += 1
            elif v is True:
                meaningful += 1
        return meaningful
    return 1


def check(section, label, path, params=None):
    status, text = http("GET", path, TOKEN, params=params)
    doc = None
    if text.strip().startswith(("{", "[")):
        try:
            doc = json.loads(text)
        except Exception:
            doc = None
    n = size_of(doc) if 200 <= status < 300 else 0
    if 200 <= status < 300:
        verdict = "DATA" if n > 0 else "EMPTY"
    else:
        verdict = "FAIL"
    COUNTS[verdict] += 1
    ROWS.append((section, label, path, status, n, verdict,
                 "" if verdict != "FAIL" else text[:150].replace("\n", " ")))
    return doc


def first_id(doc, *keys):
    if isinstance(doc, dict):
        for k in ("content", "items", "rows", "data", "results"):
            if isinstance(doc.get(k), list):
                doc = doc[k]
                break
    if isinstance(doc, list) and doc:
        row = doc[0]
        if isinstance(row, dict):
            for k in keys or ("id",):
                if row.get(k):
                    return row[k]
    return None


def main():
    global TOKEN
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default=BASE)
    ap.add_argument("--section", default=None)
    args = ap.parse_args()

    globals()["BASE"] = args.base_url

    status, text = http("POST", "/api/v1/auth/login",
                        body=dict(username=ADMIN_USER, password=PASSWORD))
    if status != 200:
        print("cannot log in as %s: %s %s" % (ADMIN_USER, status, text[:200]))
        sys.exit(1)
    TOKEN = json.loads(text).get("accessToken")
    print("logged in as %s\n" % ADMIN_USER)

    # arm the step-up window (some admin reads sit behind it)
    http("POST", "/api/v1/security/step-up", TOKEN, body=dict(password=PASSWORD))

    want = args.section

    def on(sec):
        return want is None or want == sec

    # ── ids to substitute into path-parameterised endpoints ────────────────
    users = check("users", "user directory", "/api/v1/admin/users", dict(size=50))
    uid = first_id(users, "id", "userId")

    # Resolve named seed accounts so each panel is probed against a subject that
    # actually has the data it reports on (otherwise an empty answer says
    # nothing about the endpoint).
    def uid_of(username, fallback=None):
        rows = users
        if isinstance(rows, dict):
            rows = rows.get("content") or []
        for r in (rows or []):
            if isinstance(r, dict) and r.get("username") == username:
                return r.get("id")
        return fallback or uid

    author_id = uid_of("ahmad_faruqi")     # posts, affinity, activity
    bg_id = uid_of("zayd_mansouri")        # the user with the open break-glass case
    struck_id = uid_of("zayd_mansouri")    # carries strikes

    if on("users"):
        check("users", "user analytics", "/api/v1/admin/users/analytics")
        if uid:
            check("users", "user detail", "/api/v1/admin/users/%s" % uid)
            check("users", "user PII", "/api/v1/admin/users/%s/pii" % uid)
            check("users", "user sessions", "/api/v1/admin/users/%s/sessions" % uid)
            check("users", "user login events",
                  "/api/v1/admin/users/%s/login-events" % uid)
            check("users", "user settings audit",
                  "/api/v1/admin/users/%s/settings-audit" % author_id)
            check("users", "user moderation record",
                  "/api/v1/admin/users/%s/moderation" % struck_id)
            check("users", "user data (GDPR)", "/api/v1/admin/users/%s/data" % author_id)
            # break-glass-gated: probe the user that has an OPEN approved case
            check("users", "user activity (break-glass)",
                  "/api/v1/admin/users/%s/activity" % bg_id)
            check("users", "user activity summary (break-glass)",
                  "/api/v1/admin/users/%s/activity/summary" % bg_id)
            check("users", "user reels watched (break-glass)",
                  "/api/v1/admin/users/%s/reels/watched" % bg_id)
            check("users", "user discovery", "/api/v1/admin/users/%s/discovery" % uid)
            check("users", "user suggestions", "/api/v1/admin/users/%s/suggestions" % uid)

    if on("analytics"):
        for name, path in [
            ("overview", "/api/v1/admin/analytics/overview"),
            ("content", "/api/v1/admin/analytics/content"),
            ("engagement", "/api/v1/admin/analytics/engagement"),
            ("trending", "/api/v1/admin/analytics/trending"),
            ("series", "/api/v1/admin/analytics/series"),
            ("funnel", "/api/v1/admin/analytics/funnel"),
            ("retention", "/api/v1/admin/analytics/retention"),
            ("raw events sample", "/api/v1/admin/analytics/events/sample"),
            ("alerts config", "/api/v1/admin/analytics/alerts-config"),
            ("anomalies", "/api/v1/admin/analytics/anomalies"),
        ]:
            check("analytics", name, path,
                  dict(metric="posts", days=7) if name == "series" else None)

    if on("content"):
        # this endpoint is author-scoped by design
        posts = check("content", "posts (by author)", "/api/v1/admin/content/posts",
                      dict(authorId=author_id, size=20))
        pid = first_id(posts, "id", "postId")
        if pid:
            check("content", "post detail", "/api/v1/admin/content/posts/%s" % pid)
        check("content", "blocklist", "/api/v1/admin/content/blocklist")

    if on("research"):
        rs = check("research", "research list", "/api/v1/admin/research", dict(size=20))
        rid = first_id(rs, "id")
        # probe the paper that actually carries downloads, not just the first row
        rows = (rs or {}).get("content") if isinstance(rs, dict) else rs
        best = max((r for r in (rows or []) if isinstance(r, dict)),
                   key=lambda r: r.get("downloadCount") or 0, default=None)
        if best and (best.get("downloadCount") or 0) > 0:
            rid = best["id"]
        check("research", "top research", "/api/v1/admin/research/top")
        check("research", "flagged research", "/api/v1/admin/research/flags")
        if rid:
            check("research", "research detail", "/api/v1/admin/research/%s" % rid)
            check("research", "research downloads",
                  "/api/v1/admin/research/%s/downloads" % rid)

    if on("qna"):
        check("qna", "questions", "/api/v1/admin/qna/questions", dict(size=20))

    if on("chat"):
        check("chat", "chat overview", "/api/v1/admin/chat/overview")
        check("chat", "conversations", "/api/v1/admin/chat/conversations", dict(size=20))
        check("chat", "calls", "/api/v1/admin/chat/calls", dict(size=20))
        check("chat", "call stats", "/api/v1/admin/chat/calls/stats")
        check("chat", "message request stats",
              "/api/v1/admin/chat/message-requests/stats")
        check("chat", "legal holds", "/api/v1/admin/chat/legal-holds")

    if on("channels"):
        chs = check("channels", "channels", "/api/v1/admin/channels", dict(size=20))
        cid = first_id(chs, "id")
        if cid:
            check("channels", "channel detail", "/api/v1/admin/channels/%s" % cid)
            check("channels", "channel stats", "/api/v1/admin/channels/%s/stats" % cid)
            check("channels", "channel invite links",
                  "/api/v1/admin/channels/%s/invite-links" % cid)

    if on("streams"):
        st = check("streams", "streams", "/api/v1/admin/streams", dict(size=20))
        sid = first_id(st, "id")
        check("streams", "recordings", "/api/v1/admin/streams/recordings")
        check("streams", "top gifts", "/api/v1/admin/streams/gifts/top")
        if sid:
            check("streams", "stream detail", "/api/v1/admin/streams/%s" % sid)
            check("streams", "stream recording", "/api/v1/admin/streams/%s/recording" % sid)

    if on("moderation"):
        check("moderation", "moderation queue", "/api/v1/admin/moderation/queue",
              dict(size=20))
        rep = check("safety", "reports", "/api/v1/admin/safety/reports", dict(size=20))
        rid = first_id(rep, "id")
        if rid:
            check("safety", "report detail", "/api/v1/admin/safety/reports/%s" % rid)
        check("safety", "strikes", "/api/v1/admin/safety/strikes")
        check("safety", "block stats", "/api/v1/admin/safety/stats/blocks")
        check("safety", "safety analytics", "/api/v1/admin/safety/analytics")
        if uid:
            check("safety", "user safety record",
                  "/api/v1/admin/safety/users/%s/record" % struck_id)
            check("safety", "user consent", "/api/v1/admin/safety/users/%s/consent" % uid)

    if on("sounds"):
        snd = check("sounds", "sounds pending", "/api/v1/admin/sounds",
                    dict(status="PENDING_REVIEW"))
        appr = check("sounds", "sounds approved", "/api/v1/admin/sounds",
                     dict(status="APPROVED"))
        sid = first_id(appr, "id") or first_id(snd, "id")
        check("sounds", "status counts", "/api/v1/admin/sounds/status-counts")
        check("sounds", "trending sounds", "/api/v1/admin/sounds/trending")
        if sid:
            check("sounds", "sound detail", "/api/v1/admin/sounds/%s" % sid)

    if on("media"):
        check("media", "media assets", "/api/v1/admin/media", dict(size=20))
        check("media", "status summary", "/api/v1/admin/media/status-summary")
        check("media", "quotas", "/api/v1/admin/media/quotas")
        check("media", "media ops", "/api/v1/admin/media/ops")
        check("media", "storage usage", "/api/v1/admin/storage/usage")

    if on("logs"):
        check("logs", "log explore", "/api/v1/admin/logs/explore", dict(size=20))
        check("logs", "login events", "/api/v1/admin/logs/login-events", dict(size=20))
        check("logs", "views", "/api/v1/admin/logs/views", dict(size=20))
        check("logs", "alert rules", "/api/v1/admin/logs/alerts")
        check("logs", "alert firings", "/api/v1/admin/logs/alerts/firings")
        check("logs", "retention", "/api/v1/admin/logs/retention")
        check("logs", "otp stats", "/api/v1/admin/logs/otp-stats")
        # the audit search is partition-scoped: userId is required
        check("logs", "audit log (by user)", "/api/v1/admin/audit",
              dict(userId=uid, pageSize=20))
        if uid:
            check("logs", "audit by user", "/api/v1/admin/audit/users/%s" % uid)

    if on("ops"):
        for name, path in [
            ("health", "/api/v1/admin/ops/health"),
            ("jobs", "/api/v1/admin/ops/jobs"),
            ("queues", "/api/v1/admin/ops/queues"),
            ("sse", "/api/v1/admin/ops/sse"),
            ("dlq", "/api/v1/admin/ops/queues/dlq"),
            ("paused jobs", "/api/v1/admin/ops/jobs/paused"),
            ("redis", "/api/v1/admin/ops/redis"),
            ("config reconciler", "/api/v1/admin/ops/config/reconciler"),
            ("config", "/api/v1/admin/ops/config"),
            ("media plane", "/api/v1/admin/ops/media-plane"),
        ]:
            check("ops", name, path)

    if on("search"):
        check("search", "indices", "/api/v1/admin/search/indices")
        check("search", "search health", "/api/v1/admin/search/health")
        check("search", "top queries", "/api/v1/admin/search/analytics/top-queries")
        check("search", "zero results", "/api/v1/admin/search/analytics/zero-results")

    if on("feed"):
        check("feed", "feed weights", "/api/v1/admin/feed/weights")
        check("feed", "feed config", "/api/v1/admin/feed/config")
        check("feed", "suggestion knobs", "/api/v1/admin/suggestions/knobs")
        if uid:
            check("feed", "feed explain", "/api/v1/admin/feed/explain/%s" % author_id)
            check("feed", "author affinity",
                  "/api/v1/admin/feed/affinity/%s" % author_id)
            check("feed", "suggestions explain",
                  "/api/v1/admin/suggestions/explain/%s" % uid)

    if on("notifications"):
        check("notifications", "stats", "/api/v1/admin/notifications/stats")
        check("notifications", "types", "/api/v1/admin/notifications/types")
        check("notifications", "announcements",
              "/api/v1/admin/notifications/announcements")
        check("notifications", "email stats", "/api/v1/admin/notifications/email/stats")

    if on("knowledge"):
        check("knowledge", "topics", "/api/v1/admin/knowledge/topics")
        check("knowledge", "madhhabs", "/api/v1/admin/knowledge/madhhabs")

    if on("discovery"):
        check("discovery", "contact sync stats",
              "/api/v1/admin/discovery/contact-sync/stats")
        check("discovery", "contact sync compliance",
              "/api/v1/admin/discovery/contact-sync/compliance")

    if on("trending"):
        check("trending", "trending overrides", "/api/v1/admin/trending/overrides")

    if on("activity"):
        check("activity", "breakglass cases", "/api/v1/admin/breakglass/cases")

    # ── report ────────────────────────────────────────────────────────────
    width = max(len(r[1]) for r in ROWS) + 1
    cur = None
    for section, label, path, status, n, verdict, msg in ROWS:
        if section != cur:
            cur = section
            print("\n\033[1m%s\033[0m" % section.upper())
        colour = {"DATA": "32", "EMPTY": "33", "FAIL": "31"}[verdict]
        print("  \033[%sm%-5s\033[0m %-*s %3s  %s%s"
              % (colour, verdict, width, label, status,
                 ("n=%s" % n) if verdict != "FAIL" else "", (" " + msg) if msg else ""))

    total = sum(COUNTS.values())
    print("\n" + "═" * 74)
    print("  %d endpoints checked · \033[32m%d returning data\033[0m · "
          "\033[33m%d empty\033[0m · \033[31m%d failing\033[0m"
          % (total, COUNTS["DATA"], COUNTS["EMPTY"], COUNTS["FAIL"]))


if __name__ == "__main__":
    main()

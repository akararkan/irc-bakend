#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
IRC PLATFORM — FULL DEMO SEEDER
═══════════════════════════════════════════════════════════════════════════════

Drives the *live* HTTP API (never the database, except for the one-time role
bootstrap) so every write goes through the real service layer: Postgres rows,
Cassandra partitions, Elasticsearch indices, Redis counters, RabbitMQ events,
SSE broadcasts, notifications and analytics taps all get populated exactly the
way production traffic would populate them.

Covers, in order:

   0  bootstrap        health check, register users, grant roles, login
   1  profiles         bios, specializations, avatars, links
   2  social graph     follows, close friends, blocks, restricts, contacts
   3  sounds           library uploads + moderation approve
   4  posts            TEXT / EMBEDDED / REEL / VOICE_POST / REPOST + hashtags
   5  engagement       views, reactions, saves, shares, comments, replies
   6  stories          stories, views, polls + votes, highlights
   7  research         create → publish, contributors, comments, cites, saves
   8  qna              questions, answers, re-answers, sources, accept
   9  chat/direct      DMs, every MessageType, reactions, stars, pins, forwards
  10  chat/groups      groups, members, roles, invite links, restrictions
  11  chat/channels    broadcast channels, subscribers, posts, views, comments
  12  calls            voice/video: accepted, declined (missed), ended
  13  live streams     go live, viewers, chat, gifts, stage guests, end
  14  safety           reports across every target type (moderation queue)
  15  settings         privacy, notifications, DND, consent, policies, presence
  16  traffic          feed/search/trending reads → analytics + search logs

Usage:
    python3 scripts/seed_full_demo.py                  # full run
    python3 scripts/seed_full_demo.py --only 4,5,9     # selected phases
    python3 scripts/seed_full_demo.py --base-url http://localhost:8080
"""

import argparse
import io
import json
import mimetypes
import os
import random
import string
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zlib
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone

# ═══════════════════════════════════════════════════════════════════════════
#  CONFIG
# ═══════════════════════════════════════════════════════════════════════════

BASE_URL = os.environ.get("IRC_BASE", "http://localhost:8080")
PG_DB = os.environ.get("IRC_PG_DB", "irc")
PASSWORD = "Passw0rd!2026"
TIMEOUT = 45

random.seed(20260807)

STATS = Counter()
FAILS = []
VERBOSE = False


def log(msg):
    print(msg, flush=True)


def phase(title):
    log("")
    log("\033[1;36m" + "═" * 78 + "\033[0m")
    log("\033[1;36m  " + title + "\033[0m")
    log("\033[1;36m" + "═" * 78 + "\033[0m")


def ok(msg):
    log("  \033[32m✓\033[0m " + msg)


def warn(msg):
    log("  \033[33m!\033[0m " + msg)


def err(msg):
    log("  \033[31m✗\033[0m " + msg)


# ═══════════════════════════════════════════════════════════════════════════
#  HTTP CLIENT  (stdlib only — no third-party deps on the host)
# ═══════════════════════════════════════════════════════════════════════════

class RateGuard:
    """Client-side mirror of the server's per-actor token buckets so we glide
    under the limit instead of eating 429s (RateLimiter: reaction 30/10s,
    comment 10/30s, social 30/60s)."""

    BUCKETS = {
        "reaction": (26, 10.0),
        "comment": (8, 30.0),
        "social": (26, 60.0),
    }

    def __init__(self):
        self.hits = defaultdict(list)

    def take(self, action, actor):
        cfg = self.BUCKETS.get(action)
        if not cfg or not actor:
            return
        burst, window = cfg
        key = (action, actor)
        now = time.time()
        stamps = [t for t in self.hits[key] if now - t < window]
        if len(stamps) >= burst:
            sleep_for = window - (now - stamps[0]) + 0.15
            if sleep_for > 0:
                time.sleep(sleep_for)
            now = time.time()
            stamps = [t for t in stamps if now - t < window]
        stamps.append(now)
        self.hits[key] = stamps


GUARD = RateGuard()


class Api:
    def __init__(self, base_url):
        self.base = base_url.rstrip("/")

    # ── low level ─────────────────────────────────────────────────────────
    def _send(self, method, url, headers, body):
        req = urllib.request.Request(url, data=body, method=method)
        for k, v in headers.items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
                raw = resp.read()
                return resp.status, raw
        except urllib.error.HTTPError as e:
            return e.code, e.read()
        except Exception as e:  # connection reset, timeout…
            return 0, str(e).encode()

    def call(self, method, path, token=None, json_body=None, params=None,
             parts=None, label=None, quiet=False, rate=None, actor=None,
             raw_body=None, content_type=None, tolerate=(), accept=None):
        """parts = list of (name, filename|None, content_type|None, bytes|str)"""
        if rate:
            GUARD.take(rate, actor)

        url = self.base + path
        if params:
            clean = {k: v for k, v in params.items() if v is not None}
            if clean:
                url += "?" + urllib.parse.urlencode(clean, doseq=True)

        headers = {"Accept": accept or "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token

        body = None
        if parts is not None:
            boundary = "----ircseed" + uuid.uuid4().hex
            buf = io.BytesIO()
            for name, filename, ctype, content in parts:
                if isinstance(content, str):
                    content = content.encode("utf-8")
                buf.write(("--%s\r\n" % boundary).encode())
                disp = 'Content-Disposition: form-data; name="%s"' % name
                if filename:
                    disp += '; filename="%s"' % filename
                buf.write((disp + "\r\n").encode())
                if ctype:
                    buf.write(("Content-Type: %s\r\n" % ctype).encode())
                buf.write(b"\r\n")
                buf.write(content)
                buf.write(b"\r\n")
            buf.write(("--%s--\r\n" % boundary).encode())
            body = buf.getvalue()
            headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
        elif raw_body is not None:
            body = raw_body if isinstance(raw_body, bytes) else raw_body.encode()
            headers["Content-Type"] = content_type or "application/octet-stream"
        elif json_body is not None:
            body = json.dumps(json_body).encode("utf-8")
            headers["Content-Type"] = "application/json"

        tag = label or ("%s %s" % (method, path))

        for attempt in range(6):
            status, raw = self._send(method, url, headers, body)
            if status == 429:
                retry = 2.0
                try:
                    doc = json.loads(raw.decode("utf-8", "replace"))
                    retry = float(_deep_find(doc, "retryAfterSeconds") or 2) + 0.4
                except Exception:
                    pass
                time.sleep(min(retry, 15))
                continue
            break

        data = None
        text = raw.decode("utf-8", "replace") if raw else ""
        if text.strip().startswith(("{", "[")):
            try:
                data = json.loads(text)
            except Exception:
                data = None

        if 200 <= status < 300:
            STATS["ok"] += 1
            if VERBOSE and not quiet:
                ok(tag)
            return data if data is not None else text
        if status in tolerate:
            # an idempotent no-op (already unfollowed, already left, …)
            STATS["ok"] += 1
            return None
        STATS["fail"] += 1
        STATS["fail:%d" % status] += 1
        msg = text[:220].replace("\n", " ")
        FAILS.append((tag, status, msg))
        if not quiet:
            err("%s → %s %s" % (tag, status, msg))
        return None

    def get(self, path, token=None, **kw):
        return self.call("GET", path, token, **kw)

    def post(self, path, token=None, **kw):
        return self.call("POST", path, token, **kw)

    def patch(self, path, token=None, **kw):
        return self.call("PATCH", path, token, **kw)

    def put(self, path, token=None, **kw):
        return self.call("PUT", path, token, **kw)

    def delete(self, path, token=None, **kw):
        return self.call("DELETE", path, token, **kw)


def _deep_find(doc, key):
    if isinstance(doc, dict):
        if key in doc:
            return doc[key]
        for v in doc.values():
            r = _deep_find(v, key)
            if r is not None:
                return r
    elif isinstance(doc, list):
        for v in doc:
            r = _deep_find(v, key)
            if r is not None:
                return r
    return None


API = Api(BASE_URL)


# ═══════════════════════════════════════════════════════════════════════════
#  TINY MEDIA GENERATORS  (avatars / covers — no external assets needed)
# ═══════════════════════════════════════════════════════════════════════════

def make_png(width=256, height=256, rgb=(31, 111, 92)):
    """A valid solid-colour PNG built from scratch (no Pillow on the host)."""
    def chunk(tag, payload):
        data = tag + payload
        return (len(payload).to_bytes(4, "big") + data
                + (zlib.crc32(data) & 0xFFFFFFFF).to_bytes(4, "big"))

    raw = b"".join(b"\x00" + bytes(rgb) * width for _ in range(height))
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", width.to_bytes(4, "big") + height.to_bytes(4, "big")
                    + bytes([8, 2, 0, 0, 0]))
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b""))


PALETTE = [(31, 111, 92), (52, 78, 126), (128, 66, 54), (94, 60, 120),
           (172, 128, 42), (48, 96, 108), (110, 44, 70), (60, 100, 60),
           (140, 90, 40), (70, 70, 130), (35, 120, 120), (150, 60, 60),
           (90, 110, 50), (100, 50, 130)]

# Publicly reachable sample media — used as *metadata* URLs on posts/messages
# so the frontend has something real to render without an R2 round-trip.
IMG = lambda seed, w=1080, h=1350: "https://picsum.photos/seed/irc%s/%d/%d" % (seed, w, h)
VIDEO_URLS = [
    "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerJoyrides.mp4",
    "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4",
    "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerMeltdowns.mp4",
    "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
]
AUDIO_URLS = [
    "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3",
    "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3",
    "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-5.mp3",
]
PDF_URL = "https://www.orimi.com/pdf-test.pdf"


# ═══════════════════════════════════════════════════════════════════════════
#  PERSONAS
# ═══════════════════════════════════════════════════════════════════════════

PEOPLE = [
    # key, fname, lname, username, role, title, institution, location, bio
    ("admin", "Yusuf", "Al-Amin", "yusuf_admin", "ADMIN",
     "Platform Director", "Islamic Research Center", "Baghdad, Iraq",
     "Directing the IRC platform. Building tools that serve knowledge."),
    # a second ADMIN is required for dual-control flows (break-glass approval
    # must come from a different admin than the one who opened the case)
    ("admin2", "Safiya", "Ilhan", "safiya_admin", "ADMIN",
     "Compliance Lead", "Islamic Research Center", "Istanbul, Türkiye",
     "Compliance and data-protection oversight for the platform."),
    ("mod", "Layla", "Haddad", "layla_mod", "MODERATOR",
     "Community Moderator", "Islamic Research Center", "Amman, Jordan",
     "Keeping discourse honest and sources cited."),
    ("support", "Omar", "Kilani", "omar_support", "SUPPORT",
     "Member Support", "Islamic Research Center", "Cairo, Egypt",
     "Here to help members with accounts and access."),
    ("analyst", "Nadia", "Berrada", "nadia_analyst", "ANALYST",
     "Data Analyst", "Islamic Research Center", "Rabat, Morocco",
     "Measuring what the community actually reads."),

    ("sh_ahmad", "Ahmad", "Al-Faruqi", "ahmad_faruqi", "SCHOLAR",
     "Professor of Usul al-Fiqh", "Al-Azhar University", "Cairo, Egypt",
     "Teaching legal theory for 22 years. Ijazah in Hanafi and Shafi'i fiqh."),
    ("sh_maryam", "Maryam", "Al-Ansari", "maryam_ansari", "SCHOLAR",
     "Chair of Hadith Sciences", "Islamic University of Madinah", "Madinah, KSA",
     "Hadith criticism, isnad analysis, and the science of rijal."),
    ("sh_ibrahim", "Ibrahim", "Cetinkaya", "ibrahim_cetin", "SCHOLAR",
     "Professor of Tafsir", "Marmara University", "Istanbul, Türkiye",
     "Quranic exegesis with a focus on the classical Ottoman commentaries."),

    ("r_fatima", "Fatima", "Zahra", "fatima_zahra", "RESEARCHER",
     "Postdoctoral Researcher", "SOAS University of London", "London, UK",
     "Islamic manuscripts, codicology, and digital humanities."),
    ("r_bilal", "Bilal", "Osman", "bilal_osman", "RESEARCHER",
     "PhD Candidate", "International Islamic University Malaysia", "Kuala Lumpur, MY",
     "Islamic finance and contemporary applications of maqasid al-shariah."),
    ("r_sara", "Sara", "Khalidi", "sara_khalidi", "RESEARCHER",
     "Research Fellow", "Qatar Faculty of Islamic Studies", "Doha, Qatar",
     "Comparative fiqh of contemporary medical ethics."),

    ("u_hamza", "Hamza", "Rahman", "hamza_rahman", "USER",
     "Software Engineer", "Independent", "Dhaka, Bangladesh",
     "Student of knowledge. Building Islamic apps in my spare time."),
    ("u_aisha", "Aisha", "Toure", "aisha_toure", "USER",
     "Teacher", "Madrasah An-Nur", "Dakar, Senegal",
     "Teaching Quran to children. Always learning."),
    ("u_zayd", "Zayd", "Mansouri", "zayd_mansouri", "USER",
     "Medical Student", "University of Algiers", "Algiers, Algeria",
     "Interested in the fiqh of medicine and bioethics."),
    ("u_khadija", "Khadija", "Noor", "khadija_noor", "USER",
     "Librarian", "Karachi Public Library", "Karachi, Pakistan",
     "Cataloguing Islamic literature. Book recommendations welcome."),
]

SPECIALIZATIONS = {
    "sh_ahmad": ["Usul al-Fiqh", "Fiqh", "Legal Maxims"],
    "sh_maryam": ["Hadith", "Ilm al-Rijal", "Isnad Criticism"],
    "sh_ibrahim": ["Tafsir", "Quranic Sciences", "Ottoman Studies"],
    "r_fatima": ["Manuscript Studies", "Codicology", "Digital Humanities"],
    "r_bilal": ["Islamic Finance", "Maqasid al-Shariah", "Contemporary Muamalat"],
    "r_sara": ["Medical Ethics", "Bioethics", "Fiqh"],
    "u_hamza": ["Digital Humanities", "Islamic Education"],
    "u_aisha": ["Quranic Sciences", "Islamic Education"],
    "u_zayd": ["Medical Ethics", "Bioethics"],
    "u_khadija": ["Manuscript Studies", "Islamic History"],
    "admin": ["Islamic Education"],
    "mod": ["Fiqh"],
}

# The knowledge taxonomy the profile editor binds against (topics table is
# empty on a fresh database, which silently breaks specialization saving).
TOPIC_VOCAB = [
    ("Usul al-Fiqh", "أصول الفقه", "بنەماکانی فیقھ"),
    ("Fiqh", "الفقه", "فیقھ"),
    ("Hadith", "الحديث", "حەدیس"),
    ("Tafsir", "التفسير", "تەفسیر"),
    ("Aqidah", "العقيدة", "عەقیدە"),
    ("Seerah", "السيرة النبوية", "سیرەی پێغەمبەر"),
    ("Quranic Sciences", "علوم القرآن", "زانستەکانی قورئان"),
    ("Ilm al-Rijal", "علم الرجال", "زانستی ڕیجال"),
    ("Isnad Criticism", "نقد الأسانيد", "ڕەخنەی ئیسناد"),
    ("Maqasid al-Shariah", "مقاصد الشريعة", "مەبەستەکانی شەریعەت"),
    ("Legal Maxims", "القواعد الفقهية", "بنەما فیقھییەکان"),
    ("Islamic Finance", "الاقتصاد الإسلامي", "ئابووری ئیسلامی"),
    ("Contemporary Muamalat", "المعاملات المعاصرة", "مامەڵە هاوچەرخەکان"),
    ("Medical Ethics", "الأخلاق الطبية", "ئەخلاقی پزیشکی"),
    ("Bioethics", "الأخلاق الحيوية", "ئەخلاقی ژیانی"),
    ("Manuscript Studies", "علم المخطوطات", "لێکۆڵینەوەی دەستنووس"),
    ("Codicology", "الكوديكولوجيا", "کۆدیکۆلۆجی"),
    ("Ottoman Studies", "الدراسات العثمانية", "لێکۆڵینەوەی عوسمانی"),
    ("Islamic History", "التاريخ الإسلامي", "مێژووی ئیسلامی"),
    ("Arabic Language", "اللغة العربية", "زمانی عەرەبی"),
    ("Islamic Education", "التربية الإسلامية", "پەروەردەی ئیسلامی"),
    ("Digital Humanities", "الإنسانيات الرقمية", "مرۆڤناسی دیجیتاڵ"),
    ("Comparative Religion", "مقارنة الأديان", "بەراوردی ئایینەکان"),
    ("Literary Analysis", "التحليل الأدبي", "شیکاری ئەدەبی"),
]

MADHHAB_VOCAB = [
    ("Hanafi", "الحنفي", "حەنەفی"),
    ("Maliki", "المالكي", "مالیکی"),
    ("Shafi'i", "الشافعي", "شافیعی"),
    ("Hanbali", "الحنبلي", "حەنبەلی"),
    ("Ja'fari", "الجعفري", "جەعفەری"),
    ("Zahiri", "الظاهري", "زاهیری"),
    ("Ibadi", "الإباضي", "ئیباضی"),
]


# ═══════════════════════════════════════════════════════════════════════════
#  CONTENT CORPUS
# ═══════════════════════════════════════════════════════════════════════════

POST_TEXTS = [
    "Finished a re-read of al-Shatibi's al-Muwafaqat this week. His treatment of "
    "maqasid still reads as the most disciplined attempt to keep legal reasoning "
    "tethered to purpose without collapsing into pure utility. #usul #maqasid",

    "A small reminder from today's halaqah: an isnad is not a formality. It is the "
    "difference between a claim and a transmission. #hadith #isnad",

    "Working through the manuscript catalogue at the Süleymaniye. Three copies of "
    "the same commentary, three different marginal traditions. Codicology is "
    "history that nobody wrote down on purpose. #manuscripts #tarikh",

    "Question for the researchers here: when you cite a hadith through a secondary "
    "source, do you list the intermediary or go straight to the primary collection? "
    "Our citation guide is being revised. #methodology",

    "The maqasid framework is not a licence to shortcut the texts. It is a lens for "
    "reading them coherently. Conflating the two produces bad fiqh and worse ethics. "
    "#maqasid #fiqh",

    "Alhamdulillah — the new reading room opens next month. Six hundred volumes "
    "catalogued, forty of them rare prints from the Ottoman period. #library",

    "Teaching tip that took me a decade to learn: never explain a legal ruling "
    "before the students can state the disagreement. Understanding the ikhtilaf is "
    "what makes the ruling intelligible. #teaching #ikhtilaf",

    "Draft chapter done: 9,400 words on the evolution of istihsan in the Hanafi "
    "school between the 4th and 7th centuries. Comments welcome before I submit. "
    "#usul #hanafi",

    "Spent the morning with a student going line by line through Surah al-Kahf. "
    "The narrative structure alone is worth a semester. #tafsir #quran",

    "The most underrated skill in research is knowing when a source does not say "
    "what everyone quotes it as saying. #methodology #research",

    "Islamic finance note: a contract is not halal because it avoids the word "
    "interest. Structure follows substance. #finance #muamalat",

    "Our medical ethics working group met today. Consensus is hard; documenting the "
    "disagreement honestly is harder and more useful. #bioethics #fiqh",

    "Reminder that the Arabic of the classical texts is a technical register. "
    "Reading it as modern journalistic Arabic will mislead you every time. #arabic",

    "New to the platform? Introduce yourself. Tell us what you are reading, not "
    "what you have already concluded. #welcome",

    "One page of careful reading beats fifty pages of skimming. This is the whole "
    "method. #study",
]

REEL_TEXTS = [
    "60 seconds on why the isnad system has no real parallel in pre-modern "
    "historiography. #hadith #reel",
    "What maqasid al-shariah actually means — in one minute, no jargon. #maqasid",
    "Three manuscript details that tell you where a copy was made. #manuscripts",
    "How to read a fiqh disagreement without picking a side first. #fiqh #ikhtilaf",
    "The one question that improves every research paper. #research",
    "Quran memorisation: the spacing method that actually works. #quran #hifz",
]

VOICE_TEXTS = [
    "Voice note: my working answer on the citation question from yesterday.",
    "Recorded the opening of today's halaqah for anyone who missed it.",
    "Thinking out loud about the istihsan chapter structure.",
]

COMMENTS = [
    "This matches what I found in the Cairo edition — the printed text drops a line "
    "that the Damascus manuscript keeps.",
    "Jazak Allah khayran. Do you have a reference for the second point?",
    "I would push back gently here: al-Ghazali frames this differently in al-Mustasfa.",
    "Saved this. We are discussing exactly this in our reading group next week.",
    "Could you say more about the methodology? I am not sure the sample supports the claim.",
    "Excellent. The distinction between the two positions is usually collapsed.",
    "This is the clearest statement of the problem I have read.",
    "Agreed on the substance, but the terminology in the third paragraph is doing "
    "a lot of unacknowledged work.",
    "Reading this alongside the earlier thread — the two arguments fit together well.",
    "Any chance you will publish the full bibliography?",
    "Barakallahu feek. Sharing this with my students.",
    "I had the opposite reading of that passage for years. This is convincing.",
]

REPLIES = [
    "Yes — see footnote 14, I cite the exact folio there.",
    "Fair point. I will tighten that paragraph before the next revision.",
    "That is the standard objection and I think it holds. Working on it.",
    "Sent you the bibliography.",
    "Wa iyyakum. Happy to go deeper if useful.",
    "Exactly. That is the crux.",
]

RESEARCH_PAPERS = [
    dict(
        title="Istihsan in the Hanafi School: A Reassessment of the 4th–7th Century Sources",
        abstract="This study revisits the doctrine of istihsan (juristic preference) as "
                 "articulated in Hanafi legal theory between the 4th and 7th Islamic "
                 "centuries. Drawing on eleven primary works, four of which are examined "
                 "here in manuscript form for the first time, the paper argues that the "
                 "commonly cited 'discretionary' reading of istihsan is a later "
                 "polemical construction rather than a description of how Hanafi jurists "
                 "actually deployed the principle.",
        keywords="istihsan, hanafi, usul al-fiqh, legal theory, juristic preference",
        tags=["usul", "hanafi", "istihsan", "fiqh", "legal-theory"],
        body="## 1. Introduction\n\nThe doctrine of istihsan has attracted more polemic "
             "than analysis...\n\n## 2. The Sources\n\nEleven works form the core "
             "corpus of this study...\n\n## 3. Method\n\nEach occurrence of the term "
             "was coded for context, authority invoked, and outcome...\n\n"
             "## 4. Findings\n\nIn 84% of the coded passages the principle is invoked "
             "alongside an explicit textual or analogical warrant...\n\n"
             "## 5. Conclusion\n\nIstihsan as discretion is a caricature.",
    ),
    dict(
        title="Isnad Criticism and the Limits of the Common-Link Theory",
        abstract="The common-link theory has shaped a generation of Western hadith "
                 "scholarship. This paper tests the theory against a corpus of 340 "
                 "isnad bundles drawn from six canonical collections and finds that "
                 "the distribution of common links is substantially explained by "
                 "transmission economics rather than by fabrication.",
        keywords="hadith, isnad, common link, rijal, transmission",
        tags=["hadith", "isnad", "methodology", "rijal"],
        body="## 1. The Claim\n\nJuynboll's formulation remains the reference "
             "point...\n\n## 2. Corpus\n\n340 bundles, six collections...\n\n"
             "## 3. Results\n\nThe correlation is weaker than reported...\n\n"
             "## 4. Discussion\n\nA transmission-cost model fits the data better.",
    ),
    dict(
        title="Maqasid al-Shariah and Contemporary Financial Contracts",
        abstract="Contemporary Islamic finance frequently invokes maqasid al-shariah to "
                 "justify contract structures that replicate conventional instruments. "
                 "This paper develops a three-part test — substance, risk allocation, "
                 "and social outcome — and applies it to seven widely used structures.",
        keywords="maqasid, islamic finance, murabaha, sukuk, muamalat",
        tags=["maqasid", "finance", "muamalat", "contracts"],
        body="## 1. Problem\n\nForm-over-substance is the recurring critique...\n\n"
             "## 2. A Three-Part Test\n\nSubstance, risk, outcome...\n\n"
             "## 3. Application\n\nSeven structures assessed...\n\n"
             "## 4. Conclusion\n\nThree of the seven fail on risk allocation.",
    ),
    dict(
        title="Codicological Markers of Provenance in Ottoman-Era Fiqh Manuscripts",
        abstract="Paper, watermark, rulings, and marginal hand can locate an undated "
                 "manuscript with surprising precision. This study catalogues 62 "
                 "Ottoman-era fiqh manuscripts and proposes a scoring rubric for "
                 "provenance attribution that does not depend on colophons.",
        keywords="codicology, manuscripts, ottoman, provenance, watermarks",
        tags=["manuscripts", "codicology", "ottoman", "history"],
        body="## 1. Why Colophons Fail\n\nColophons are copied, forged, and "
             "lost...\n\n## 2. The Rubric\n\nSix weighted markers...\n\n"
             "## 3. Validation\n\nTested against 18 dated controls.",
    ),
    dict(
        title="The Fiqh of Brain Death: Mapping Forty Years of Contemporary Rulings",
        abstract="Since 1986 at least fourteen collective fatwa bodies have addressed "
                 "brain death. This paper maps their reasoning, isolates the four "
                 "points of genuine disagreement, and shows that most apparent "
                 "conflict is terminological rather than substantive.",
        keywords="bioethics, brain death, fatwa, medical fiqh, ijtihad",
        tags=["bioethics", "medicine", "fiqh", "fatwa", "contemporary"],
        body="## 1. Scope\n\nFourteen bodies, forty years...\n\n"
             "## 2. Points of Disagreement\n\nFour, not fourteen...\n\n"
             "## 3. Terminology\n\nMuch of the conflict dissolves on translation.",
    ),
    dict(
        title="Narrative Structure in Surah al-Kahf: A Literary-Exegetical Reading",
        abstract="Surah al-Kahf presents four narratives with an unusually tight "
                 "structural symmetry. This paper reads the surah's architecture "
                 "against the classical tafsir tradition and argues that the "
                 "structure itself carries exegetical weight the commentaries "
                 "register but rarely theorise.",
        keywords="tafsir, surah al-kahf, quranic structure, literary analysis",
        tags=["tafsir", "quran", "literary", "structure"],
        body="## 1. Four Narratives\n\nThe companions, the two gardens, Musa and "
             "al-Khidr, Dhul-Qarnayn...\n\n## 2. Symmetry\n\nThe ring structure is "
             "explicit...\n\n## 3. The Commentaries\n\nal-Razi comes closest.",
    ),
]

QUESTIONS = [
    dict(title="How should a secondary source be cited when the primary is inaccessible?",
         body="I am writing on a 6th-century Hanafi text that survives only in a "
              "single manuscript I cannot access. Several modern studies quote it "
              "at length. What is the correct citation practice — cite the modern "
              "study, or cite the manuscript through it? Our department style guide "
              "is silent on this and I want to get it right.",
         tags=["methodology", "citation", "research", "manuscripts"]),
    dict(title="Is the common-link theory still defensible after recent isnad studies?",
         body="I keep encountering the common-link argument in secondary literature, "
              "but the more recent quantitative work seems to undercut it. Is there "
              "a current defence of the theory that engages that work directly, or "
              "has the field moved on without saying so explicitly?",
         tags=["hadith", "isnad", "methodology"]),
    dict(title="What distinguishes istihsan from maslaha mursala in practice?",
         body="Textbook definitions separate them cleanly, but in the actual Hanafi "
              "and Maliki cases the boundary looks porous to me. Can someone give "
              "concrete examples where the two would yield different rulings on the "
              "same fact pattern?",
         tags=["usul", "istihsan", "maslaha", "fiqh"]),
    dict(title="Best approach for teaching ikhtilaf to beginners without confusing them?",
         body="I teach a weekend class of adult beginners. When I present a "
              "disagreement between schools, a portion of the class hears it as "
              "'nobody knows'. When I present one position, they later feel misled. "
              "What has worked for others?",
         tags=["teaching", "ikhtilaf", "education"]),
    dict(title="Recommended critical editions of al-Muwafaqat?",
         body="Looking for a critical edition with a serious apparatus. I have the "
              "Dar Ibn Affan printing but the variants are thinly documented. "
              "Anything better in print or digital?",
         tags=["books", "usul", "maqasid", "editions"]),
    dict(title="How do contemporary fatwa bodies handle disagreement on brain death?",
         body="I am a medical student trying to understand the actual state of the "
              "question. Reading the fatwas directly I find the conclusions differ "
              "but the reasoning often does not. Is that a fair reading?",
         tags=["bioethics", "medicine", "fatwa", "contemporary"]),
    dict(title="Watermark databases for Ottoman-era paper — what is actually usable?",
         body="Briquet and Piccard are the standard references but coverage of "
              "Ottoman paper is thin. Are there regional databases worth the "
              "subscription?",
         tags=["manuscripts", "codicology", "ottoman", "tools"]),
    dict(title="Does structure carry exegetical weight, or is that reading it in?",
         body="A literary reading of Surah al-Kahf finds a tight ring structure. "
              "How do we distinguish structure that the text bears from structure "
              "the reader imposes? Is there a discipline to this?",
         tags=["tafsir", "quran", "methodology", "literary"]),
]

ANSWERS = [
    "The convention in our department is to cite the primary source and then add "
    "'quoted in' with the secondary reference. That keeps the chain honest — the "
    "reader can see you did not consult the manuscript — while still pointing at "
    "the real locus. Never cite the primary silently through a secondary; if the "
    "intermediary misquoted, the error becomes yours.",

    "Short answer: the theory survives in a weakened form. The strong version — "
    "common link equals fabricator — is no longer defensible. The weak version — "
    "common link marks the point at which a tradition entered systematic written "
    "circulation — is compatible with the quantitative results and is what most "
    "careful people now mean when they use the term.",

    "The distinction is real but it lives at the level of warrant, not outcome. "
    "Istihsan departs from an analogy because a *competing* textual or analogical "
    "indicator is stronger. Maslaha mursala operates where no specific indicator "
    "exists at all. Same ruling can issue from either; the justification differs, "
    "and the justification is what constrains the next case.",

    "What works for me: present the disagreement as a disagreement about a "
    "*question*, not about the answer. Spend the first ten minutes making sure "
    "everyone can state the question precisely. Once the question is sharp, the "
    "positions look like reasoned alternatives rather than noise.",

    "The Mashhur Hasan Salman edition is the one to use. The apparatus is genuinely "
    "critical and the manuscript sigla are documented. The Dar Ibn Affan printing "
    "you have is readable but it is not a critical edition in the technical sense.",

    "Your reading is fair and it is the finding of the literature. The substantive "
    "disagreement reduces to about four points, and at least two of those are "
    "artefacts of how the medical terminology was rendered into Arabic in the "
    "1980s. Read the fatwas against the medical definitions current at the time.",

    "For Ottoman paper the Istanbul-based catalogues are better than Briquet. The "
    "Süleymaniye's own project has digitised a large tranche and it is free. "
    "Piccard is still worth having for the European imports.",

    "The discipline is falsifiability. A structural claim earns its keep when it "
    "predicts something you did not build it from — a variant reading, a lexical "
    "choice, a placement that looks arbitrary until the structure explains it. If "
    "the structure only re-describes what you already saw, it is decoration.",

    "Adding to the above: check whether the secondary source itself consulted the "
    "manuscript or is quoting a third party. Two-step chains are common and rarely "
    "flagged.",

    "I would add one caution — the weakened version is doing much less work than "
    "people assume when they invoke it in passing.",
]

CHAT_LINES = [
    "Assalamu alaikum — did you get a chance to look at the draft?",
    "Wa alaikum assalam. Reading it now, about halfway through.",
    "No rush. The deadline is the 22nd.",
    "Section 3 is the strongest part. Section 2 needs the sources tightened.",
    "That is fair. I was worried about exactly that paragraph.",
    "Can you send me the Süleymaniye scans?",
    "Sending now — three folios, about 40MB total.",
    "Got them, jazak Allah khayran.",
    "Are you joining the reading group on Thursday?",
    "In sha Allah. What are we covering?",
    "The istihsan chapter, pages 140 to 190.",
    "Perfect, that is exactly what I am working on.",
    "One more thing — the citation format changed, check the new guide.",
    "Noted. I will fix the bibliography before submitting.",
    "The library confirmed the reading room opens the 1st.",
    "Excellent news. I will book a slot.",
    "Did the committee respond about the conference panel?",
    "Accepted — three speakers, 90 minutes.",
    "Alhamdulillah. Who is chairing?",
    "Dr. Al-Ansari agreed to chair it.",
    "Send me the abstract when you have a moment.",
    "Will do after Maghrib.",
    "Quick question about the manuscript sigla — are we using the Cairo convention?",
    "Yes, Cairo convention throughout. It is in the style guide now.",
]

GROUP_LINES = [
    "Welcome everyone. This group is for the usul reading circle.",
    "Assalamu alaikum all.",
    "Wa alaikum assalam wa rahmatullah.",
    "First session Thursday 7pm, al-Muwafaqat vol. 2.",
    "Can we get a PDF of the edition we are using?",
    "Uploading the scans now.",
    "Jazakum Allah khayran.",
    "Question: are we reading the introduction or starting at chapter 1?",
    "Introduction first — it sets the whole method.",
    "Agreed. The introduction is half the book's argument.",
    "I will take notes and share them after each session.",
    "That would be very helpful.",
    "Reminder: session tomorrow, same time.",
    "I may be 10 minutes late, please start without me.",
    "No problem.",
    "Notes from session 1 are up.",
    "These are excellent, barakallahu feek.",
]

CHANNEL_POSTS = [
    "📢 **Reading room opening** — The IRC reading room opens on the 1st. Six "
    "hundred catalogued volumes, forty rare Ottoman-period prints. Booking opens "
    "next week.",
    "📄 **New paper published** — 'Istihsan in the Hanafi School: A Reassessment' "
    "is now live on the platform. Open access, full apparatus included.",
    "🎓 **Call for papers** — The annual IRC conference is accepting abstracts on "
    "Islamic legal theory and manuscript studies until the end of next month.",
    "📚 **Acquisition** — We have acquired a complete set of the Süleymaniye "
    "catalogue. Available in the reading room from next week.",
    "🔴 **Live session tonight** — Prof. Al-Faruqi on the structure of legal "
    "disagreement. 8pm, streamed on the platform.",
    "📝 **Style guide update** — The citation guide has been revised. Manuscript "
    "sigla now follow the Cairo convention throughout. Please update drafts in "
    "progress.",
    "🗓 **Reading circle** — The usul reading circle meets Thursdays at 7pm. "
    "Currently working through al-Muwafaqat vol. 2.",
    "🏅 **Congratulations** — Dr. Al-Ansari's hadith methodology paper has been "
    "accepted for publication. A summary thread follows next week.",
]

LIVE_CHAT = [
    "Assalamu alaikum from Cairo 🇪🇬",
    "Can you repeat the last point about the isnad?",
    "This is excellent, jazak Allah khayran",
    "Audio is clear here 👍",
    "Watching from Kuala Lumpur",
    "Will this be recorded?",
    "The slide reference please?",
    "Barakallahu feek ya shaykh",
    "Question: how does this apply to the Maliki position?",
    "Salam from London",
    "👏👏👏",
    "Following from Istanbul",
]

SOUNDS = [
    ("Adhan — Makkah", "Sheikh Ali Mullah", "QURAN_RECITATION", 210),
    ("Surah Ar-Rahman", "Sheikh Abdul Basit", "QURAN_RECITATION", 780),
    ("Tala'a al-Badru", "IRC Ensemble", "NASHEED", 195),
    ("Hasbi Rabbi", "Nasheed Collective", "NASHEED", 240),
    ("Halaqah Opening", "Prof. Ahmad Al-Faruqi", "LECTURE_CLIP", 95),
    ("Rain over Madinah", "Field Recording", "NATURE", 300),
    ("Library Ambience", "IRC Studio", "NATURE", 420),
    ("Study Focus Loop", "IRC Studio", "PLATFORM_MUSIC", 180),
]


def now_ms():
    return int(time.time() * 1000)


def nonce():
    return "seed-" + uuid.uuid4().hex[:20]


def pick(seq, n=1):
    return random.sample(list(seq), min(n, len(seq)))


# ═══════════════════════════════════════════════════════════════════════════
#  WORLD  — everything created gets recorded here for later phases
# ═══════════════════════════════════════════════════════════════════════════

class World:
    def __init__(self):
        self.users = {}          # key -> dict(id, username, token, role, ...)
        self.posts = []          # dicts: id, author, type
        self.comments = []       # dicts: id, postId, author
        self.stories = []
        self.polls = []
        self.sounds = []
        self.research = []
        self.questions = []
        self.answers = []
        self.dms = []            # conversation ids
        self.groups = []
        self.channels = []
        self.messages = []       # dicts: id, convId, author
        self.streams = []
        self.calls = []
        self.highlights = []

    def u(self, key):
        return self.users[key]

    def tok(self, key):
        return self.users[key]["token"]

    def id(self, key):
        return self.users[key]["id"]

    @property
    def keys(self):
        return list(self.users.keys())

    def members(self, exclude=()):
        return [k for k in self.users if k not in exclude]


W = World()


# ═══════════════════════════════════════════════════════════════════════════
#  PHASE 0 — BOOTSTRAP
# ═══════════════════════════════════════════════════════════════════════════

_STEPUP_AT = {}


def step_up(key, force=False):
    """Arm the sensitive-action window. Admin mutations annotated
    @RequiresStepUp reject with 403 unless a fresh password re-auth sits in
    Redis (user-bound, app.security.step-up.ttl-seconds = 300)."""
    if not force and time.time() - _STEPUP_AT.get(key, 0) < 180:
        return True
    r = API.post("/api/v1/security/step-up", W.tok(key),
                 json_body=dict(password=PASSWORD),
                 label="step-up %s" % key, quiet=True)
    if r is not None:
        _STEPUP_AT[key] = time.time()
        return True
    return False


def psql(sql):
    try:
        out = subprocess.run(["psql", "-d", PG_DB, "-tAc", sql],
                             capture_output=True, text=True, timeout=30)
        if out.returncode != 0:
            warn("psql: " + out.stderr.strip()[:160])
            return None
        return out.stdout.strip()
    except Exception as e:
        warn("psql unavailable: %s" % e)
        return None


def phase0_bootstrap():
    phase("PHASE 0 — BOOTSTRAP  ·  accounts, roles, sessions")

    # Aggregate health goes DOWN whenever a *non-essential* indicator fails
    # (SMTP is unreachable from most dev boxes), so probe reachability instead:
    # any HTTP answer at all means the container is serving.
    status, raw = API._send("GET", BASE_URL + "/actuator/health",
                            {"Accept": "application/json"}, None)
    if status == 0:
        err("Application is not reachable at %s — start it first. (%s)"
            % (BASE_URL, raw.decode("utf-8", "replace")[:120]))
        sys.exit(1)
    detail = raw.decode("utf-8", "replace")[:80]
    if status == 200:
        ok("app healthy at %s" % BASE_URL)
    else:
        warn("app serving at %s but /actuator/health is %s %s "
             "(usually the SMTP indicator — harmless here)"
             % (BASE_URL, status, detail))

    # ── register ──────────────────────────────────────────────────────────
    created, existing = 0, 0
    for (key, fname, lname, username, role, title, inst, loc, bio) in PEOPLE:
        email = "%s@irc.test" % username
        body = dict(fname=fname, lname=lname, username=username,
                    email=email, password=PASSWORD)
        before = STATS["fail"]
        res = API.post("/api/v1/auth/register", json_body=body, quiet=True,
                       label="register %s" % username)
        if res:
            created += 1
        else:
            existing += 1
            # a re-run hitting "already exists" is the expected path, not a failure
            if STATS["fail"] > before and FAILS and FAILS[-1][1] == 409:
                FAILS.pop()
                STATS["fail"] -= 1
                STATS["fail:409"] -= 1
                STATS["ok"] += 1
        W.users[key] = dict(key=key, username=username, email=email, role=role,
                            fname=fname, lname=lname, title=title,
                            institution=inst, location=loc, bio=bio)
    ok("accounts: %d created, %d already existed" % (created, existing))

    # ── grant roles (JWT is minted at login, so this must precede login) ──
    for key, u in W.users.items():
        if u["role"] != "USER":
            psql("UPDATE users SET role = '%s' WHERE username = '%s';"
                 % (u["role"], u["username"]))
    # keep the check constraint honest if an older DB pinned the enum
    ok("roles granted: " + ", ".join(
        "%s=%s" % (u["username"], u["role"]) for u in W.users.values()
        if u["role"] != "USER"))

    # ── login ─────────────────────────────────────────────────────────────
    for key, u in W.users.items():
        res = API.post("/api/v1/auth/login",
                       json_body=dict(username=u["username"], password=PASSWORD),
                       label="login %s" % u["username"])
        if not res:
            err("cannot log in as %s — aborting" % u["username"])
            sys.exit(1)
        u["token"] = res.get("accessToken") or res.get("token")
        u["refresh"] = res.get("refreshToken")
        me = API.get("/api/v1/users/me", u["token"], quiet=True)
        u["id"] = (me or {}).get("id")
        if not u["id"]:
            err("no id for %s" % u["username"])
            sys.exit(1)
    ok("logged in %d users, tokens + ids captured" % len(W.users))
    STATS["users"] = len(W.users)


# ═══════════════════════════════════════════════════════════════════════════
#  PHASE 1 — PROFILES
# ═══════════════════════════════════════════════════════════════════════════

def seed_vocabulary():
    """Topics + madhhabs are a migration-managed taxonomy that a fresh database
    ships empty. Nothing in the profile editor works without it, so seed it
    through the admin vocabulary endpoints and drop the cached (stale) copy."""
    admin = W.tok("admin")
    if not step_up("admin"):
        warn("step-up failed for admin — vocabulary writes will be rejected")
    API.post("/api/v1/admin/knowledge/cache/evict", admin, quiet=True)

    existing = API.get("/api/v1/admin/knowledge/topics", admin, quiet=True) or []
    have = {str(t.get("nameEn") or t.get("name_en") or "").strip()
            for t in existing if isinstance(t, dict)}
    added_t = 0
    for en, ar, ckb in TOPIC_VOCAB:
        if en in have:
            continue
        step_up("admin")
        if API.post("/api/v1/admin/knowledge/topics", admin,
                    json_body=dict(nameEn=en, nameAr=ar, nameCkb=ckb),
                    label="topic %s" % en, quiet=True) is not None:
            added_t += 1

    existing_m = API.get("/api/v1/admin/knowledge/madhhabs", admin, quiet=True) or []
    have_m = {str(m.get("nameEn") or m.get("name_en") or "").strip()
              for m in existing_m if isinstance(m, dict)}
    added_m = 0
    for en, ar, ckb in MADHHAB_VOCAB:
        if en in have_m:
            continue
        step_up("admin")
        if API.post("/api/v1/admin/knowledge/madhhabs", admin,
                    json_body=dict(nameEn=en, nameAr=ar, nameCkb=ckb),
                    label="madhhab %s" % en, quiet=True) is not None:
            added_m += 1

    API.post("/api/v1/admin/knowledge/cache/evict", admin, quiet=True)
    ok("knowledge taxonomy: +%d topics, +%d madhhabs" % (added_t, added_m))

    topics = API.get("/api/v1/admin/knowledge/topics", admin, quiet=True) or []
    madhhabs = API.get("/api/v1/admin/knowledge/madhhabs", admin, quiet=True) or []
    topic_ids = {}
    for t in topics:
        if isinstance(t, dict) and t.get("id") is not None:
            topic_ids[str(t.get("nameEn") or t.get("name_en") or "").strip()] = t["id"]
    madhhab_ids = [m["id"] for m in madhhabs
                   if isinstance(m, dict) and m.get("id") is not None]

    # the public (cached) endpoints must now serve the same rows
    pub_t = API.get("/api/v1/topics", admin, quiet=True)
    pub_m = API.get("/api/v1/madhhabs", admin, quiet=True)
    if pub_t is None or pub_m is None:
        warn("public /topics or /madhhabs still failing after cache evict")
    else:
        ok("public vocab endpoints serving %d topics / %d madhhabs"
           % (len(pub_t), len(pub_m)))
    return topic_ids, madhhab_ids


def phase1_profiles():
    phase("PHASE 1 — PROFILES  ·  taxonomy, bios, specializations, avatars")

    topic_ids, madhhab_ids = seed_vocabulary()

    n_avatar = 0
    n_spec = 0
    for i, (key, u) in enumerate(W.users.items()):
        body = dict(
            displayName="%s %s" % (u["fname"], u["lname"]),
            profileBio=u["bio"],
            selfDescriber=u["title"],
            location=u["location"],
            academicTitle=u["title"],
            institutionName=u["institution"],
            websiteUrl="https://irc.example.org/people/%s" % u["username"],
            isForHire=(u["role"] in ("RESEARCHER", "SCHOLAR") and i % 3 == 0),
            contentLanguage=random.choice(["en", "ar", "en"]),
        )
        if madhhab_ids:
            body["madhhabId"] = random.choice(madhhab_ids)
        API.patch("/api/v1/users/me/profile", u["token"], json_body=body,
                  label="profile %s" % u["username"], quiet=True)

        if key in SPECIALIZATIONS and topic_ids:
            items = [dict(topicId=topic_ids[name], displayOrder=n)
                     for n, name in enumerate(SPECIALIZATIONS[key])
                     if name in topic_ids]
            if items and API.patch("/api/v1/users/me/profile/specializations", u["token"],
                                   json_body=dict(specializations=items),
                                   label="specializations %s" % u["username"],
                                   quiet=True) is not None:
                n_spec += 1

        png = make_png(384, 384, PALETTE[i % len(PALETTE)])
        r = API.post("/api/v1/users/me/profile/avatar", u["token"],
                     parts=[("image", "%s.png" % u["username"], "image/png", png)],
                     label="avatar %s" % u["username"], quiet=True)
        if r:
            n_avatar += 1
            u["avatar"] = r.get("profileImage") or r.get("avatarUrl")
        cover = make_png(1200, 400, PALETTE[(i + 5) % len(PALETTE)])
        API.post("/api/v1/users/me/profile/cover", u["token"],
                 parts=[("image", "%s-cover.png" % u["username"], "image/png", cover)],
                 label="cover %s" % u["username"], quiet=True)

    ok("profiles written for %d users · %d avatars uploaded · %d specialization sets"
       % (len(W.users), n_avatar, n_spec))

    seed_media_assets()
    if n_avatar == 0:
        warn("no avatars stored — object storage (R2) is unreachable from here")


def seed_media_assets():
    """Drive the real upload pipeline (intent → presigned PUT → complete) so the
    media dashboard, storage usage and quota panels have assets to report on."""
    import hashlib
    made = deduped = 0
    for i, (key, u) in enumerate(W.users.items()):
        for n in range(2):
            png = make_png(320 + n * 64, 320 + n * 64, PALETTE[(i + n) % len(PALETTE)])
            intent = API.post("/api/v1/media/upload-intent", u["token"],
                              json_body=dict(mime="image/png", sizeBytes=len(png),
                                             sha256=hashlib.sha256(png).hexdigest(),
                                             type="IMAGE"),
                              label="upload-intent", quiet=True)
            if not intent or not intent.get("mediaId"):
                continue
            if intent.get("deduped"):
                deduped += 1
                continue
            put_url = intent.get("presignedPutUrl")
            if put_url:
                status, _ = API._send("PUT", put_url,
                                      {"Content-Type": "image/png"}, png)
                if not (200 <= status < 300):
                    continue
            if API.post("/api/v1/media/%s/complete" % intent["mediaId"], u["token"],
                        label="media complete", quiet=True) is not None:
                made += 1
    ok("media pipeline: %d assets uploaded + completed (%d dedup hits)"
       % (made, deduped))


# ═══════════════════════════════════════════════════════════════════════════
#  PHASE 2 — SOCIAL GRAPH
# ═══════════════════════════════════════════════════════════════════════════

def phase2_social():
    phase("PHASE 2 — SOCIAL GRAPH  ·  follows, close friends, blocks, contacts")

    keys = W.keys
    follows = 0
    # everyone follows the scholars + admin; then a dense random layer
    hubs = ["sh_ahmad", "sh_maryam", "sh_ibrahim", "admin"]
    for k in keys:
        for h in hubs:
            if h == k:
                continue
            if API.post("/api/v1/users/%s/follow" % W.id(h), W.tok(k),
                        label="%s→%s" % (k, h), quiet=True) is not None:
                follows += 1
    for k in keys:
        for t in pick([x for x in keys if x != k], random.randint(3, 7)):
            if API.post("/api/v1/users/%s/follow" % W.id(t), W.tok(k),
                        label="%s→%s" % (k, t), quiet=True,
                        tolerate=(403, 409)) is not None:
                follows += 1
    ok("%d follow edges" % follows)

    cf = 0
    for k in ["sh_ahmad", "sh_maryam", "r_fatima", "u_aisha", "r_bilal"]:
        for f in pick([x for x in keys if x != k], 3):
            if API.post("/api/v1/close-friends", W.tok(k),
                        params=dict(friendId=W.id(f)), quiet=True) is not None:
                cf += 1
    ok("%d close-friend entries" % cf)

    # a couple of blocks and restricts so the safety dashboards have rows
    for path, tok in [("/api/v1/users/%s/block" % W.id("u_zayd"), W.tok("u_khadija")),
                      ("/api/v1/blocks/%s" % W.id("u_hamza"), W.tok("u_aisha")),
                      ("/api/v1/users/%s/restrict" % W.id("u_hamza"), W.tok("r_sara")),
                      ("/api/v1/settings/privacy/muted/%s" % W.id("u_zayd"),
                       W.tok("r_bilal"))]:
        API.post(path, tok, quiet=True, tolerate=(409,))
    ok("2 blocks, 1 restrict, 1 mute")

    # Hashed contact sync — feeds PYMK and the discovery-compliance dashboard.
    # The client never uploads phone numbers: it uploads salted digests, and the
    # endpoint takes them as `hashes` (rate-limited to 3 syncs / 24h / user).
    import hashlib

    def contact_hash(e164):
        return hashlib.sha256(e164.encode()).hexdigest()

    shared = ["+96477%06d" % (i * 7) for i in range(4)]   # deliberate overlap
    synced = stored = 0
    for k in keys:
        phones = ["+9647%08d" % random.randint(0, 99999999) for _ in range(8)] + shared
        res = API.post("/api/v1/contacts/sync", W.tok(k),
                       json_body=dict(hashes=[contact_hash(p) for p in phones],
                                      appVersion="1.0.0"),
                       label="contact sync %s" % k, quiet=True, tolerate=(429,))
        if isinstance(res, dict):
            synced += 1
            stored += res.get("stored") or 0
    ok("contact sync: %d users, %d hashed entries stored" % (synced, stored))

    for k in pick(keys, 6):
        API.get("/api/v1/users/me/suggestions", W.tok(k), params=dict(limit=10), quiet=True)
        API.get("/api/v1/users/who-to-follow", W.tok(k), params=dict(limit=10), quiet=True)
    API.delete("/api/v1/users/me/suggestions/%s" % W.id("u_zayd"), W.tok("u_hamza"), quiet=True)
    ok("PYMK suggestions computed + one dismissal recorded")


# ═══════════════════════════════════════════════════════════════════════════
#  PHASE 3 — SOUND LIBRARY
# ═══════════════════════════════════════════════════════════════════════════

def phase3_sounds():
    phase("PHASE 3 — SOUND LIBRARY  ·  uploads + moderation")

    uploaders = ["admin", "sh_ahmad", "r_fatima", "u_aisha", "sh_ibrahim"]
    for i, (title, artist, cat, dur) in enumerate(SOUNDS):
        who = uploaders[i % len(uploaders)]
        body = dict(title=title, artistName=artist,
                    audioUrl=AUDIO_URLS[i % len(AUDIO_URLS)],
                    coverArtUrl=IMG("snd%d" % i, 640, 640),
                    durationSeconds=dur, category=cat,
                    autoApprove=(who == "admin"))
        res = API.post("/api/v1/sounds", W.tok(who), json_body=body,
                       label="sound %s" % title, quiet=True)
        if res and res.get("id"):
            W.sounds.append(dict(id=res["id"], title=title, category=cat,
                                 status=res.get("status")))
    ok("%d sounds uploaded" % len(W.sounds))

    # leave the last two in PENDING_REVIEW so the moderation queue is not empty
    approved = 0
    for s in W.sounds[:-2]:
        if s.get("status") != "APPROVED":
            if API.post("/api/v1/sounds/%s/approve" % s["id"], W.tok("admin"),
                        quiet=True) is not None:
                approved += 1
                s["status"] = "APPROVED"
    # two more from an unprivileged uploader that stay in the queue
    for title, artist, cat, dur in [("Dua for Knowledge", "Community Recording",
                                     "LECTURE_CLIP", 120),
                                    ("Evening Nasheed", "Student Ensemble",
                                     "NASHEED", 165)]:
        res = API.post("/api/v1/sounds", W.tok("u_aisha"),
                       json_body=dict(title=title, artistName=artist,
                                      audioUrl=AUDIO_URLS[0],
                                      coverArtUrl=IMG("sndp" + title[:4], 640, 640),
                                      durationSeconds=dur, category=cat),
                       label="pending sound %s" % title, quiet=True)
        if res and res.get("id"):
            W.sounds.append(dict(id=res["id"], title=title, category=cat,
                                 status=res.get("status")))
    pending = [s for s in W.sounds if s.get("status") != "APPROVED"]
    ok("%d sounds approved · %d left in PENDING_REVIEW" % (approved, len(pending)))

    API.get("/api/v1/sounds/search", W.tok("u_hamza"),
            params=dict(q="Surah", limit=10), quiet=True)
    for cat in {s["category"] for s in W.sounds}:
        API.get("/api/v1/sounds/by-category/%s" % cat, W.tok("u_hamza"), quiet=True)


# ═══════════════════════════════════════════════════════════════════════════
#  PHASE 4 — POSTS
# ═══════════════════════════════════════════════════════════════════════════

def phase4_posts():
    phase("PHASE 4 — POSTS  ·  text, media, reels, voice, reposts")

    authors = [k for k in W.keys if k not in ("analyst",)]
    approved_sounds = [s["id"] for s in W.sounds if s.get("status") == "APPROVED"]

    # mention a real handle in ~1/3 of posts so the mention pipeline fires
    handles = [W.u(k)["username"] for k in W.keys]

    for i, text in enumerate(POST_TEXTS):
        author = authors[i % len(authors)]
        body_text = text
        if i % 3 == 1:
            body_text += " cc @%s" % random.choice(
                [h for h in handles if h != W.u(author)["username"]])

        kind = ["TEXT", "EMBEDDED", "TEXT", "EMBEDDED", "TEXT"][i % 5]
        payload = dict(postType=kind,
                       visibility="PUBLIC" if i % 7 else "FOLLOWERS_ONLY",
                       textContent=body_text)
        if kind == "EMBEDDED":
            n = random.choice([1, 1, 2, 3])
            payload["mediaUrls"] = [IMG("p%d_%d" % (i, j)) for j in range(n)]
            payload["mediaTypes"] = ["IMAGE"] * n
        if i % 4 == 0:
            payload["locationName"] = random.choice(
                ["Cairo, Egypt", "Istanbul, Türkiye", "Madinah, KSA",
                 "London, UK", "Doha, Qatar"])
            payload["locationLat"] = round(random.uniform(20, 55), 5)
            payload["locationLng"] = round(random.uniform(-10, 60), 5)

        res = API.post("/api/v1/posts", W.tok(author), json_body=payload,
                       label="post#%d %s" % (i, kind), rate="social",
                       actor=author, quiet=True)
        if res and res.get("id"):
            W.posts.append(dict(id=res["id"], author=author, type=kind))

    for i, text in enumerate(REEL_TEXTS):
        author = authors[(i * 3) % len(authors)]
        payload = dict(postType="REEL", visibility="PUBLIC", textContent=text,
                       mediaUrls=[VIDEO_URLS[i % len(VIDEO_URLS)]],
                       mediaTypes=["VIDEO"])
        if approved_sounds:
            payload["soundId"] = approved_sounds[i % len(approved_sounds)]
        res = API.post("/api/v1/posts", W.tok(author), json_body=payload,
                       label="reel#%d" % i, rate="social", actor=author, quiet=True)
        if res and res.get("id"):
            W.posts.append(dict(id=res["id"], author=author, type="REEL"))

    for i, text in enumerate(VOICE_TEXTS):
        author = authors[(i * 5) % len(authors)]
        payload = dict(postType="VOICE_POST", visibility="PUBLIC", textContent=text,
                       audioTrackUrl=AUDIO_URLS[i % len(AUDIO_URLS)],
                       audioTrackName="Voice note %d" % (i + 1))
        res = API.post("/api/v1/posts", W.tok(author), json_body=payload,
                       label="voice#%d" % i, rate="social", actor=author, quiet=True)
        if res and res.get("id"):
            W.posts.append(dict(id=res["id"], author=author, type="VOICE_POST"))

    # reposts (self-repost is allowed on this platform)
    base = [p for p in W.posts if p["type"] in ("TEXT", "EMBEDDED")][:6]
    for i, src in enumerate(base):
        sharer = authors[(i * 4 + 2) % len(authors)]
        payload = dict(postType="REPOST", visibility="PUBLIC",
                       textContent=random.choice([
                           "Worth reading in full.",
                           "This is the point I was trying to make last week.",
                           "Sharing for the researchers here.",
                           "Excellent thread — adding my notes below.",
                           "",
                       ]) or None,
                       sharedPostId=src["id"])
        res = API.post("/api/v1/posts", W.tok(sharer), json_body=payload,
                       label="repost#%d" % i, rate="social", actor=sharer, quiet=True)
        if res and res.get("id"):
            W.posts.append(dict(id=res["id"], author=sharer, type="REPOST"))

    # one edit + one archive so the content dashboard shows lifecycle states
    if W.posts:
        p = W.posts[0]
        API.patch("/api/v1/posts/%s" % p["id"], W.tok(p["author"]),
                  json_body=dict(textContent=POST_TEXTS[0] + "\n\n[edited: added a "
                                 "reference to al-Muwafaqat vol. 2, p. 164]"),
                  label="edit post", quiet=True)
    ok("%d posts created (%s)" % (
        len(W.posts),
        ", ".join("%s×%d" % (t, sum(1 for p in W.posts if p["type"] == t))
                  for t in ["TEXT", "EMBEDDED", "REEL", "VOICE_POST", "REPOST"])))


# ═══════════════════════════════════════════════════════════════════════════
#  PHASE 5 — ENGAGEMENT
# ═══════════════════════════════════════════════════════════════════════════

def phase5_engagement():
    phase("PHASE 5 — ENGAGEMENT  ·  views, reactions, comments, saves, shares")

    keys = W.keys
    views = reactions = saves = shares = 0

    for p in W.posts:
        viewers = pick([k for k in keys if k != p["author"]], random.randint(4, 11))
        for v in viewers:
            if API.post("/api/v1/posts/%s/views" % p["id"], W.tok(v), quiet=True) is not None:
                views += 1
        for r in pick(viewers, random.randint(2, max(2, len(viewers) - 2))):
            if API.post("/api/v1/posts/%s/reactions" % p["id"], W.tok(r),
                        json_body=dict(reactionType="LIKE"), rate="reaction",
                        actor=r, quiet=True) is not None:
                reactions += 1
        for s in pick(viewers, random.randint(0, 3)):
            if API.post("/api/v1/posts/%s/saves" % p["id"], W.tok(s),
                        params=dict(collection=random.choice(
                            ["Reading list", "Teaching", "To cite", None])),
                        quiet=True) is not None:
                saves += 1
        for s in pick(viewers, random.randint(0, 2)):
            if API.post("/api/v1/posts/%s/shares" % p["id"], W.tok(s),
                        json_body=dict(caption=random.choice(
                            ["Sharing this", "Relevant to our discussion", ""])),
                        quiet=True) is not None:
                shares += 1
    ok("%d views · %d reactions · %d saves · %d shares" % (views, reactions, saves, shares))

    # reels get their own watch-time surface
    reel_views = 0
    for p in [x for x in W.posts if x["type"] == "REEL"]:
        for v in pick([k for k in keys if k != p["author"]], random.randint(5, 10)):
            if API.post("/api/v1/posts/%s/reels/view" % p["id"], W.tok(v),
                        json_body=dict(watchedMs=random.randint(1500, 58000),
                                       completed=random.random() > 0.5),
                        quiet=True) is not None:
                reel_views += 1
    ok("%d reel views with watch-time" % reel_views)

    # comments (flat replies at depth 1 — platform rule)
    n_c = n_r = n_cr = 0
    for p in W.posts:
        commenters = pick([k for k in keys if k != p["author"]], random.randint(1, 4))
        for c in commenters:
            text = random.choice(COMMENTS)
            if random.random() < 0.25:
                text += " @%s" % W.u(p["author"])["username"]
            res = API.post("/api/v1/posts/%s/comments" % p["id"], W.tok(c),
                           json_body=dict(text=text), rate="comment", actor=c,
                           label="comment", quiet=True)
            if res and res.get("id"):
                cid = res["id"]
                W.comments.append(dict(id=cid, postId=p["id"], author=c))
                n_c += 1
                # author replies to some comments
                if random.random() < 0.45:
                    rr = API.post("/api/v1/posts/comments/%s/replies" % cid,
                                  W.tok(p["author"]),
                                  json_body=dict(text=random.choice(REPLIES)),
                                  rate="comment", actor=p["author"],
                                  label="reply", quiet=True)
                    if rr:
                        n_r += 1
                for liker in pick([k for k in keys if k != c], random.randint(0, 3)):
                    if API.post("/api/v1/posts/%s/comments/%s/reactions" % (p["id"], cid),
                                W.tok(liker), json_body=dict(reactionType="LIKE"),
                                rate="reaction", actor=liker, quiet=True) is not None:
                        n_cr += 1
    ok("%d comments · %d replies · %d comment reactions" % (n_c, n_r, n_cr))

    # a hard-deleted comment + an edited one, for the moderation surfaces
    if len(W.comments) > 3:
        c = W.comments[-1]
        API.patch("/api/v1/posts/comments/%s" % c["id"], W.tok(c["author"]),
                  json_body=dict(text="(edited) " + random.choice(COMMENTS)), quiet=True)
        c2 = W.comments[-2]
        API.delete("/api/v1/posts/comments/%s" % c2["id"], W.tok(c2["author"]), quiet=True)
        W.comments.remove(c2)
        ok("1 comment edited, 1 hard-deleted")


# ═══════════════════════════════════════════════════════════════════════════
#  PHASE 6 — STORIES
# ═══════════════════════════════════════════════════════════════════════════

def phase6_stories():
    phase("PHASE 6 — STORIES  ·  stories, views, polls, highlights")

    story_authors = ["sh_ahmad", "sh_maryam", "sh_ibrahim", "r_fatima", "r_bilal",
                     "u_aisha", "u_hamza", "admin", "r_sara", "u_khadija"]
    texts = [
        "In the reading room today. Three folios to go.",
        "Session starts in 20 minutes.",
        "New arrivals on the shelf 📚",
        "Late night with al-Muwafaqat.",
        "Manuscript detail of the day.",
        "Coffee and codicology ☕",
        "Notes from this morning's halaqah.",
        "The view from the library window.",
        "Draft submitted. Alhamdulillah.",
        "Reminder: reading circle Thursday 7pm.",
    ]
    vis_cycle = ["PUBLIC", "FOLLOWERS_ONLY", "CLOSE_FRIENDS", "PUBLIC"]

    for i, a in enumerate(story_authors):
        for j in range(2):
            n = i * 2 + j
            st = ["IMAGE", "TEXT", "VIDEO", "IMAGE"][n % 4]
            body = dict(storyType=st,
                        visibility=vis_cycle[n % len(vis_cycle)],
                        textContent=texts[n % len(texts)],
                        lifetimeHours=random.choice([8, 16, 24, 24]))
            if st == "IMAGE":
                body["mediaUrl"] = IMG("st%d" % n, 1080, 1920)
                body["thumbnailUrl"] = IMG("st%d" % n, 320, 568)
            elif st == "VIDEO":
                body["mediaUrl"] = VIDEO_URLS[n % len(VIDEO_URLS)]
                body["thumbnailUrl"] = IMG("stv%d" % n, 320, 568)
            res = API.post("/api/v1/stories", W.tok(a), json_body=body,
                           label="story %s/%s" % (a, st), rate="social",
                           actor=a, quiet=True)
            if res and res.get("storyId"):
                W.stories.append(dict(id=res["storyId"], author=a, type=st))
            elif res and res.get("id"):
                W.stories.append(dict(id=res["id"], author=a, type=st))
    ok("%d stories created" % len(W.stories))

    sv = 0
    for s in W.stories:
        for v in pick([k for k in W.keys if k != s["author"]], random.randint(3, 9)):
            if API.post("/api/v1/stories/%s/views" % s["id"], W.tok(v),
                        quiet=True) is not None:
                sv += 1
    ok("%d story views" % sv)

    poll_specs = [
        ("Which should the reading circle cover next?", "al-Muwafaqat", "al-Mustasfa"),
        ("Best time for the live session?", "After Maghrib", "After Isha"),
        ("Do you cite through secondary sources?", "Always flag it", "Cite primary only"),
        ("Preferred edition?", "Critical apparatus", "Readable print"),
    ]
    votes = 0
    for i, s in enumerate(W.stories[:len(poll_specs)]):
        q, a, b = poll_specs[i]
        res = API.post("/api/v1/stories/%s/poll" % s["id"], W.tok(s["author"]),
                       json_body=dict(question=q, optionA=a, optionB=b),
                       label="story poll", quiet=True)
        pid = (res or {}).get("pollId") or (res or {}).get("id")
        if not pid:
            continue
        W.polls.append(dict(id=pid, storyId=s["id"]))
        for v in pick([k for k in W.keys if k != s["author"]], random.randint(4, 10)):
            if API.post("/api/v1/polls/%s/vote" % pid, W.tok(v),
                        params=dict(choice=random.choice(["A", "B"])),
                        quiet=True) is not None:
                votes += 1
    ok("%d story polls · %d votes" % (len(W.polls), votes))

    for a in ["sh_ahmad", "r_fatima", "u_aisha"]:
        mine = [s for s in W.stories if s["author"] == a]
        if not mine:
            continue
        res = API.post("/api/v1/highlights", W.tok(a),
                       json_body=dict(title=random.choice(
                           ["Halaqah", "Manuscripts", "Reading room", "Teaching"]),
                           coverUrl=IMG("hl%s" % a, 400, 400)),
                       label="highlight %s" % a, quiet=True)
        hid = (res or {}).get("highlightId") or (res or {}).get("id")
        if hid:
            W.highlights.append(hid)
            for s in mine:
                API.post("/api/v1/highlights/%s/stories/%s" % (hid, s["id"]),
                         W.tok(a), quiet=True)
    ok("%d story highlights" % len(W.highlights))

    for k in pick(W.keys, 6):
        API.get("/api/v1/stories/by-author/%s" % W.id("sh_ahmad"), W.tok(k), quiet=True)


# ═══════════════════════════════════════════════════════════════════════════
#  PHASE 7 — RESEARCH
# ═══════════════════════════════════════════════════════════════════════════

def phase7_research():
    phase("PHASE 7 — RESEARCH  ·  papers, contributors, publication, engagement")

    researchers = ["sh_ahmad", "sh_maryam", "r_bilal", "r_fatima", "r_sara", "sh_ibrahim"]

    # this phase owns W.research; papers are identified by title so a re-run
    # reuses them instead of publishing the same study twice
    W.research.clear()
    existing = {}
    browse = API.get("/api/v1/admin/research", W.tok("admin"),
                     params=dict(size=100), quiet=True)
    for row in ((browse or {}).get("content") or []):
        if isinstance(row, dict) and row.get("title"):
            existing[row["title"]] = row

    reused = 0
    for i, spec in enumerate(RESEARCH_PAPERS):
        author = researchers[i % len(researchers)]
        prior = existing.get(spec["title"])
        if prior:
            W.research.append(dict(id=prior["id"], author=author,
                                   title=spec["title"], tags=spec["tags"],
                                   published=prior.get("status") == "PUBLISHED"))
            reused += 1
            continue
        co = [k for k in researchers if k != author]
        data = dict(
            title=spec["title"],
            description=spec["body"],
            abstractText=spec["abstract"],
            bodyFormat="MARKDOWN",
            keywords=spec["keywords"],
            citation="%s %s. (2026). %s. IRC Working Papers."
                     % (W.u(author)["fname"], W.u(author)["lname"], spec["title"]),
            visibility="PUBLIC",
            commentsEnabled=True,
            downloadsEnabled=True,
            tags=spec["tags"],
            sources=[
                dict(sourceType="ISBN",
                     title="al-Muwafaqat fi Usul al-Shariah",
                     citationText="al-Shatibi, Ibrahim. al-Muwafaqat. ed. Mashhur "
                                  "Hasan Salman. Dar Ibn Affan, 1997.",
                     isbn="9789960899152", displayOrder=1),
                dict(sourceType="URL",
                     title="Reconsidering the Common Link",
                     citationText="Journal of Islamic Studies 34(2), 2023, 145–178.",
                     url="https://doi.org/10.1093/jis/example", displayOrder=2),
                dict(sourceType="MANUAL",
                     title="Süleymaniye MS Fatih 1428, fols. 14b–19a",
                     citationText="Consulted in situ, Istanbul, March 2026.",
                     displayOrder=3),
            ],
            contributors=[dict(userId=W.id(c), role="CO_AUTHOR",
                               displayOrder=n + 1,
                               contributionNote="Reviewed the source apparatus.")
                          for n, c in enumerate(pick(co, 2))],
        )
        res = API.post("/api/v1/researches", W.tok(author),
                       parts=[("data", None, "application/json",
                               json.dumps(data))],
                       label="research: %s" % spec["title"][:40], quiet=True)
        if res and res.get("id"):
            W.research.append(dict(id=res["id"], author=author,
                                   title=spec["title"], tags=spec["tags"]))
    ok("%d research papers (%d new, %d reused)"
       % (len(W.research), len(W.research) - reused, reused))

    published = sum(1 for r in W.research if r.get("published"))
    for i, r in enumerate(W.research):
        if i == len(W.research) - 1 or r.get("published"):
            continue  # leave the last one as a draft for the dashboard
        if API.post("/api/v1/researches/%s/publish" % r["id"], W.tok(r["author"]),
                    label="publish", quiet=True, tolerate=(400,)) is not None:
            published += 1
            r["published"] = True
    ok("%d published, %d left in draft" % (published, len(W.research) - published))

    pubs = [r for r in W.research if r.get("published")]
    v = rx = cm = sv = ct = 0
    for r in pubs:
        for k in pick([x for x in W.keys if x != r["author"]], random.randint(5, 12)):
            if API.post("/api/v1/researches/%s/view" % r["id"], W.tok(k), quiet=True) is not None:
                v += 1
        for k in pick([x for x in W.keys if x != r["author"]], random.randint(3, 9)):
            if API.post("/api/v1/researches/%s/reactions" % r["id"], W.tok(k),
                        json_body=dict(reactionType="LIKE"), rate="reaction",
                        actor=k, quiet=True) is not None:
                rx += 1
        for k in pick([x for x in W.keys if x != r["author"]], random.randint(1, 4)):
            if API.post("/api/v1/researches/%s/save" % r["id"], W.tok(k),
                        params=dict(collection=random.choice(
                            ["To cite", "Literature review", None])),
                        quiet=True) is not None:
                sv += 1
        for k in pick([x for x in W.keys if x != r["author"]], random.randint(1, 3)):
            if API.post("/api/v1/researches/%s/cite" % r["id"], W.tok(k), quiet=True) is not None:
                ct += 1
        for k in pick([x for x in W.keys if x != r["author"]], random.randint(1, 4)):
            res = API.post("/api/v1/researches/%s/comments" % r["id"], W.tok(k),
                           json_body=dict(content=random.choice(COMMENTS)),
                           rate="comment", actor=k, label="research comment", quiet=True)
            if res and res.get("id"):
                cm += 1
                if random.random() < 0.5:
                    API.post("/api/v1/researches/%s/comments" % r["id"], W.tok(r["author"]),
                             json_body=dict(content=random.choice(REPLIES),
                                            parentId=res["id"]),
                             rate="comment", actor=r["author"], quiet=True)
                for liker in pick([x for x in W.keys if x != k], random.randint(0, 2)):
                    API.post("/api/v1/researches/%s/comments/%s/reactions"
                             % (r["id"], res["id"]), W.tok(liker),
                             json_body=dict(reactionType="LIKE"),
                             rate="reaction", actor=liker, quiet=True)
    ok("%d views · %d reactions · %d comments · %d saves · %d citations"
       % (v, rx, cm, sv, ct))

    # attach a downloadable artefact so the downloads panel has rows
    dl = 0
    for r in pubs:
        pdf = ("%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n"
               "%%EOF\n").encode()
        media = API.post("/api/v1/researches/%s/media" % r["id"], W.tok(r["author"]),
                         parts=[("file", "%s.pdf" % r["id"], "application/pdf", pdf)],
                         params=dict(caption="Author manuscript (preprint)",
                                     altText="Full text PDF"),
                         label="research media", quiet=True)
        mid_ = (media or {}).get("id") or (media or {}).get("mediaId")
        if not mid_:
            continue
        for k in pick([x for x in W.keys if x != r["author"]], random.randint(2, 5)):
            if API.post("/api/v1/researches/%s/download" % r["id"], W.tok(k),
                        params=dict(mediaId=mid_), quiet=True) is not None:
                dl += 1
    ok("%d research downloads recorded" % dl)

    API.get("/api/v1/researches/feed", W.tok("u_hamza"), quiet=True)
    API.get("/api/v1/researches/tags/trending", W.tok("u_hamza"), quiet=True)
    API.get("/api/v1/researches/search/tags", W.tok("u_hamza"),
            params={"tags": ["usul", "fiqh"]}, quiet=True)


# ═══════════════════════════════════════════════════════════════════════════
#  PHASE 8 — Q&A
# ═══════════════════════════════════════════════════════════════════════════

def phase8_qna():
    phase("PHASE 8 — Q&A  ·  questions, answers, re-answers, sources, accepts")

    # Authoring gates (QuestionServiceImpl): only SCHOLAR/ADMIN may open a
    # question; SCHOLAR/RESEARCHER/ADMIN may answer.
    askers = ["sh_ahmad", "sh_maryam", "sh_ibrahim", "admin",
              "sh_ahmad", "sh_maryam", "sh_ibrahim", "admin"]
    W.questions.clear()
    prior_q = {}
    page = API.get("/api/v1/admin/qna/questions", W.tok("admin"),
                   params=dict(size=100), quiet=True)
    for row in ((page or {}).get("content") or []):
        if isinstance(row, dict) and row.get("title"):
            prior_q[row["title"]] = row.get("id")

    reused_q = 0
    for i, q in enumerate(QUESTIONS):
        asker = askers[i % len(askers)]
        if q["title"] in prior_q:
            W.questions.append(dict(id=prior_q[q["title"]], author=asker,
                                    title=q["title"]))
            reused_q += 1
            continue
        body = dict(title=q["title"], body=q["body"], tags=q["tags"],
                    keywords=", ".join(q["tags"]))
        if i == len(QUESTIONS) - 1:
            body["maxAnswers"] = 5
        res = API.post("/api/v1/questions", W.tok(asker), json_body=body,
                       label="question#%d" % i, quiet=True)
        if res and res.get("id"):
            W.questions.append(dict(id=res["id"], author=asker, title=q["title"]))
    ok("%d questions (%d new, %d reused)"
       % (len(W.questions), len(W.questions) - reused_q, reused_q))

    answerers = ["sh_ahmad", "sh_maryam", "sh_ibrahim",
                 "r_fatima", "r_bilal", "r_sara", "admin"]
    n_a = n_ra = n_src = 0
    for i, q in enumerate(W.questions):
        for j, who in enumerate(pick([a for a in answerers if a != q["author"]],
                                     random.randint(2, 4))):
            payload = dict(body=ANSWERS[(i + j) % len(ANSWERS)])
            if j == 0:
                payload["sources"] = [
                    dict(sourceType="ISBN", title="al-Mustasfa min Ilm al-Usul",
                         citationText="al-Ghazali. al-Mustasfa. Dar al-Kutub "
                                      "al-Ilmiyyah, 1993, vol. 1, p. 283.",
                         isbn="9782745100016"),
                    dict(sourceType="URL", title="Isnad Analysis Revisited",
                         citationText="Islamic Law and Society 31(1), 2024.",
                         url="https://doi.org/10.1163/ils.example"),
                ]
            res = API.post("/api/v1/questions/%s/answers" % q["id"], W.tok(who),
                           json_body=payload, rate="comment", actor=who,
                           label="answer", quiet=True)
            if res and res.get("id"):
                n_a += 1
                if payload.get("sources"):
                    n_src += len(payload["sources"])
                aid = res["id"]
                W.answers.append(dict(id=aid, qid=q["id"], author=who))
                # re-answers (threaded follow-ups)
                if random.random() < 0.4:
                    ra = API.post("/api/v1/questions/%s/answers/%s/reanswers"
                                  % (q["id"], aid), W.tok(q["author"]),
                                  json_body=dict(body=random.choice(REPLIES)),
                                  rate="comment", actor=q["author"], quiet=True)
                    if ra:
                        n_ra += 1
                for liker in pick([k for k in W.keys if k != who], random.randint(1, 5)):
                    API.post("/api/v1/questions/%s/answers/%s/react" % (q["id"], aid),
                             W.tok(liker), json_body=dict(reactionType="LIKE"),
                             rate="reaction", actor=liker, quiet=True)
    ok("%d answers · %d re-answers · %d sources attached" % (n_a, n_ra, n_src))

    accepted = 0
    for q in W.questions:
        cands = [a for a in W.answers if a["qid"] == q["id"]]
        if cands and random.random() < 0.7:
            a = random.choice(cands)
            if API.post("/api/v1/questions/%s/answers/%s/accept" % (q["id"], a["id"]),
                        W.tok(q["author"]), label="accept", quiet=True) is not None:
                accepted += 1
    ok("%d answers accepted by the asker" % accepted)

    sv = 0
    for q in W.questions:
        for k in pick([x for x in W.keys if x != q["author"]], random.randint(1, 4)):
            if API.post("/api/v1/questions/%s/save" % q["id"], W.tok(k),
                        params=dict(collection=random.choice(["Follow up", None])),
                        quiet=True) is not None:
                sv += 1
    # lock one thread — gives the admin QnA dashboard a locked example
    if W.questions:
        API.post("/api/v1/questions/%s/lock-answers" % W.questions[-1]["id"],
                 W.tok(W.questions[-1]["author"]), quiet=True)
    ok("%d question saves, 1 thread locked" % sv)


# ═══════════════════════════════════════════════════════════════════════════
#  PHASE 9 — DIRECT MESSAGING
# ═══════════════════════════════════════════════════════════════════════════

def mid(res):
    """MessageResponse keys its identifier `messageId` (a Snowflake long),
    not `id` like every other chat DTO."""
    if not isinstance(res, dict):
        return None
    return res.get("messageId") or res.get("id")


def media_ref(kind, i):
    if kind == "IMAGE":
        return dict(kind="IMAGE", storageKey="seed/chat/img%d.jpg" % i,
                    url=IMG("chat%d" % i, 1080, 810), mime="image/jpeg",
                    bytes=248_000, width=1080, height=810,
                    thumbnailUrl=IMG("chat%d" % i, 320, 240))
    if kind == "VIDEO":
        return dict(kind="VIDEO", storageKey="seed/chat/vid%d.mp4" % i,
                    url=VIDEO_URLS[i % len(VIDEO_URLS)], mime="video/mp4",
                    bytes=4_800_000, width=1280, height=720, durationMs=32_000,
                    thumbnailUrl=IMG("chatv%d" % i, 320, 180))
    if kind in ("VOICE", "AUDIO"):
        return dict(kind=kind, storageKey="seed/chat/aud%d.mp3" % i,
                    url=AUDIO_URLS[i % len(AUDIO_URLS)], mime="audio/mpeg",
                    bytes=1_400_000, durationMs=41_000,
                    waveform=",".join(str(random.randint(2, 40)) for _ in range(48)))
    return dict(kind="FILE", storageKey="seed/chat/doc%d.pdf" % i, url=PDF_URL,
                mime="application/pdf", bytes=92_000, fileName="draft-chapter-%d.pdf" % i)


def phase9_direct():
    phase("PHASE 9 — DIRECT MESSAGING  ·  DMs, media, reactions, pins, forwards")

    # this phase owns W.dms — drop anything hydration guessed at, otherwise a
    # partial run would message into declined/stranger threads it did not build
    W.dms.clear()

    # ChatPermissionEngine routes a stranger's first DM into a Message Request
    # and caps it at 3 messages. Mutual follow ⇒ SendDecision.ALLOW, so make
    # the conversational pairs connected first (the request/stranger path is
    # seeded deliberately at the end of this phase).
    pairs = [("sh_ahmad", "r_fatima"), ("sh_maryam", "r_bilal"),
             ("sh_ibrahim", "r_sara"), ("u_hamza", "u_khadija"),
             ("admin", "sh_ahmad"), ("r_fatima", "u_khadija"),
             ("mod", "u_zayd"), ("r_bilal", "u_hamza")]

    for a, b in pairs:
        API.post("/api/v1/users/%s/follow" % W.id(b), W.tok(a), quiet=True)
        API.post("/api/v1/users/%s/follow" % W.id(a), W.tok(b), quiet=True)

    for a, b in pairs:
        res = API.post("/api/v1/conversations", W.tok(a),
                       json_body=dict(type="DIRECT", recipientId=W.id(b)),
                       label="dm %s↔%s" % (a, b), quiet=True)
        if res and res.get("id"):
            W.dms.append(dict(id=res["id"], a=a, b=b))
    ok("%d direct conversations (pairs connected by mutual follow)" % len(W.dms))

    sent = 0
    for idx, c in enumerate(W.dms):
        speakers = [c["a"], c["b"]]
        last_id = None
        for i, line in enumerate(CHAT_LINES[:random.randint(10, 20)]):
            who = speakers[i % 2]
            body = dict(clientNonce=nonce(), type="TEXT", body=line)
            if last_id and random.random() < 0.3:
                body["replyToId"] = last_id
            res = API.post("/api/v1/conversations/%s/messages" % c["id"], W.tok(who),
                           json_body=body, label="dm msg", quiet=True)
            if mid(res):
                sent += 1
                last_id = mid(res)
                W.messages.append(dict(id=mid(res), conv=c["id"], author=who))

        # one of each rich type per conversation
        rich = [("IMAGE", "IMAGE"), ("VOICE", "VOICE"), ("FILE", "FILE")]
        if idx % 2 == 0:
            rich.append(("VIDEO", "VIDEO"))
        for mt, kind in rich:
            who = speakers[random.randint(0, 1)]
            res = API.post("/api/v1/conversations/%s/messages" % c["id"], W.tok(who),
                           json_body=dict(clientNonce=nonce(), type=mt,
                                          body={"IMAGE": "Manuscript folio, recto.",
                                                "VIDEO": "Clip from the session.",
                                                "VOICE": None,
                                                "FILE": "Draft chapter attached."}.get(mt),
                                          media=[media_ref(kind, idx)]),
                           label="dm %s" % mt, quiet=True)
            if mid(res):
                sent += 1
                W.messages.append(dict(id=mid(res), conv=c["id"], author=who))

        # location + contact cards
        who = speakers[0]
        API.post("/api/v1/conversations/%s/messages" % c["id"], W.tok(who),
                 json_body=dict(clientNonce=nonce(), type="LOCATION",
                                location=dict(latitude=30.0444, longitude=31.2357,
                                              name="Al-Azhar Library",
                                              address="Al-Azhar St, Cairo")),
                 label="dm LOCATION", quiet=True)
        API.post("/api/v1/conversations/%s/messages" % c["id"], W.tok(speakers[1]),
                 json_body=dict(clientNonce=nonce(), type="CONTACT",
                                contact=dict(phone="+201234567890",
                                             firstName="Reading", lastName="Room",
                                             userId=W.id("admin"))),
                 label="dm CONTACT", quiet=True)

        # a poll in one DM
        if idx == 0:
            API.post("/api/v1/conversations/%s/messages" % c["id"], W.tok(speakers[0]),
                     json_body=dict(clientNonce=nonce(), type="POLL",
                                    poll=dict(question="Meet Thursday or Friday?",
                                              options=["Thursday 7pm", "Friday 5pm"],
                                              anonymous=False)),
                     label="dm POLL", quiet=True)

    ok("%d direct messages sent" % sent)

    # reactions / stars / pins / read markers / edits / deletes / forwards
    rx = st = pn = fw = 0
    emojis = ["👍", "❤️", "😊", "🤲", "📚", "✅"]
    for c in W.dms:
        msgs = [m for m in W.messages if m["conv"] == c["id"]]
        if not msgs:
            continue
        for m in pick(msgs, min(6, len(msgs))):
            other = c["b"] if m["author"] == c["a"] else c["a"]
            if API.post("/api/v1/messages/%s/react" % m["id"], W.tok(other),
                        json_body=dict(emoji=random.choice(emojis)),
                        rate="reaction", actor=other, quiet=True) is not None:
                rx += 1
        for m in pick(msgs, 2):
            if API.post("/api/v1/messages/%s/star" % m["id"], W.tok(m["author"]),
                        quiet=True) is not None:
                st += 1
        pin_target = msgs[len(msgs) // 2]
        if API.post("/api/v1/conversations/%s/messages/%s/pin" % (c["id"], pin_target["id"]),
                    W.tok(c["a"]), quiet=True) is not None:
            pn += 1
        last = msgs[-1]
        for who in (c["a"], c["b"]):
            API.post("/api/v1/conversations/%s/read" % c["id"], W.tok(who),
                     json_body=dict(lastReadMessageId=last["id"]), quiet=True)
            API.post("/api/v1/messages/%s/delivered" % last["id"], W.tok(who), quiet=True)

    # forward a few messages — the target must be a conversation the forwarder
    # is actually a member of
    member_convs = defaultdict(list)
    for c in W.dms:
        member_convs[c["a"]].append(c["id"])
        member_convs[c["b"]].append(c["id"])
    for m in W.messages[:12]:
        targets = [x for x in member_convs.get(m["author"], []) if x != m["conv"]]
        if not targets:
            continue
        if API.post("/api/v1/messages/%s/forward" % m["id"], W.tok(m["author"]),
                    json_body=dict(targetConversationId=random.choice(targets),
                                   clientNonce=nonce()),
                    label="forward", quiet=True) is not None:
            fw += 1
    ok("%d reactions · %d stars · %d pins · %d forwards · read receipts sent"
       % (rx, st, pn, fw))

    # edit + delete + draft + disappearing + scheduled
    if W.messages:
        m = W.messages[3]
        API.patch("/api/v1/messages/%s" % m["id"], W.tok(m["author"]),
                  json_body=dict(body="Reading it now, about halfway through. (edited)"),
                  quiet=True)
        m2 = W.messages[5]
        API.delete("/api/v1/messages/%s" % m2["id"], W.tok(m2["author"]),
                   params=dict(scope="everyone"), quiet=True)
    if W.dms:
        API.put("/api/v1/conversations/%s/draft" % W.dms[0]["id"], W.tok(W.dms[0]["a"]),
                json_body=dict(body="Was going to ask about the sigla convention…"),
                quiet=True)
        API.post("/api/v1/conversations/%s/disappearing" % W.dms[2]["id"],
                 W.tok(W.dms[2]["a"]), json_body=dict(seconds=86400), quiet=True)
        API.post("/api/v1/conversations/%s/mute" % W.dms[3]["id"], W.tok(W.dms[3]["b"]),
                 json_body=dict(mutedUntil=None, muted=True), quiet=True)
        API.post("/api/v1/conversations/%s/pin" % W.dms[0]["id"], W.tok(W.dms[0]["a"]),
                 json_body=dict(pinned=True), quiet=True)
        API.post("/api/v1/conversations/%s/archive" % W.dms[-1]["id"],
                 W.tok(W.dms[-1]["a"]), json_body=dict(archived=True), quiet=True)
        # Jackson is configured with yyyy-MM-dd'T'HH:mm:ss.SSS'Z'
        when = (datetime.now(timezone.utc) + timedelta(hours=6)).replace(tzinfo=None)
        API.post("/api/v1/conversations/%s/messages/schedule" % W.dms[0]["id"],
                 W.tok(W.dms[0]["a"]),
                 json_body=dict(scheduledAt=when.strftime("%Y-%m-%dT%H:%M:%S.000Z"),
                                clientNonce=nonce(), type="TEXT",
                                body="Reminder: reading circle tonight at 7."),
                 label="schedule message", quiet=True)
        API.get("/api/v1/conversations/%s/scheduled" % W.dms[0]["id"],
                W.tok(W.dms[0]["a"]), quiet=True)
    ok("edit / delete / draft / disappearing / mute / pin / archive / scheduled applied")

    seed_message_requests()


def seed_message_requests():
    """Strangers (no mutual follow) land in the recipient's Message Requests
    tray, capped at 3 messages until accepted. Seed all three terminal states."""
    scenarios = [
        ("u_zayd", "sh_maryam", "accept",
         "Assalamu alaikum — may I ask about your hadith methodology seminar?"),
        ("u_khadija", "sh_ibrahim", "pending",
         "Assalamu alaikum, I catalogue Ottoman prints and had a question about "
         "your Marmara seminar."),
        ("u_aisha", "sh_ahmad", "decline",
         "Salam — is there a recording of the usul lecture series?"),
    ]
    accepted = pending = declined = 0
    for sender, recipient, outcome, opening in scenarios:
        # guarantee stranger status: the recipient must not follow the sender
        API.delete("/api/v1/users/%s/follow" % W.id(sender), W.tok(recipient),
                   quiet=True, tolerate=(404,))
        conv = API.post("/api/v1/conversations", W.tok(sender),
                        json_body=dict(type="DIRECT", recipientId=W.id(recipient)),
                        label="stranger dm %s→%s" % (sender, recipient), quiet=True)
        if not conv or not conv.get("id"):
            continue
        cid = conv["id"]
        # on a re-run the request may already be resolved — that 403 is the
        # correct answer, not a seeding failure
        API.post("/api/v1/conversations/%s/messages" % cid, W.tok(sender),
                 json_body=dict(clientNonce=nonce(), type="TEXT", body=opening),
                 quiet=True, tolerate=(403,))

        reqs = API.get("/api/v1/message-requests", W.tok(recipient),
                       params=dict(status="PENDING", size=50), quiet=True)
        items = (reqs or {}).get("content") if isinstance(reqs, dict) else reqs
        req = next((r for r in (items or [])
                    if isinstance(r, dict) and str(r.get("conversationId")) == str(cid)), None)
        if not req:
            continue

        if outcome == "accept":
            API.post("/api/v1/message-requests/%s/accept" % req["id"], W.tok(recipient),
                     label="accept request", quiet=True)
            accepted += 1
            # now that it is accepted the thread flows normally
            for i, line in enumerate(["Wa alaikum assalam — yes, ask away.",
                                      "Jazak Allah khayran. It is about the "
                                      "common-link argument.",
                                      "Good question. Come to Thursday's session."]):
                who = recipient if i % 2 == 0 else sender
                r = API.post("/api/v1/conversations/%s/messages" % cid, W.tok(who),
                             json_body=dict(clientNonce=nonce(), type="TEXT", body=line),
                             quiet=True)
                if mid(r):
                    W.messages.append(dict(id=mid(r), conv=cid, author=who))
        elif outcome == "decline":
            API.post("/api/v1/message-requests/%s/decline" % req["id"], W.tok(recipient),
                     label="decline request", quiet=True)
            declined += 1
        else:
            pending += 1
    ok("message requests: %d accepted · %d left pending · %d declined"
       % (accepted, pending, declined))


# ═══════════════════════════════════════════════════════════════════════════
#  PHASE 10 — GROUPS
# ═══════════════════════════════════════════════════════════════════════════

def find_conversation(owner_key, title, ctype="GROUP"):
    """Re-runs must not litter the inbox with duplicate groups — look the
    conversation up by (type, title) in the owner's inbox first."""
    for path in ("/api/v1/conversations", "/api/v1/conversations/archived"):
        page = API.get(path, W.tok(owner_key), params=dict(size=100), quiet=True)
        items = (page or {}).get("content") if isinstance(page, dict) else page
        for c in (items or []):
            if isinstance(c, dict) and c.get("type") == ctype and c.get("title") == title:
                return c["id"]
    return None


def phase10_groups():
    phase("PHASE 10 — GROUPS  ·  members, roles, invites, restrictions")

    W.groups.clear()      # this phase owns W.groups (see phase 9)

    specs = [
        ("Usul Reading Circle", "Weekly reading of al-Muwafaqat, vol. 2.",
         "sh_ahmad", ["r_fatima", "r_bilal", "u_hamza", "u_zayd", "r_sara", "u_aisha"]),
        ("Hadith Methodology Seminar", "Isnad criticism working group.",
         "sh_maryam", ["r_fatima", "r_sara", "u_khadija", "sh_ibrahim", "u_hamza"]),
        ("IRC Research Staff", "Internal coordination for the research team.",
         "admin", ["sh_ahmad", "sh_maryam", "sh_ibrahim", "r_fatima", "r_bilal",
                   "r_sara", "mod", "analyst"]),
        ("Manuscripts Working Group", "Codicology, provenance, digitisation.",
         "r_fatima", ["sh_ibrahim", "u_khadija", "sh_ahmad", "u_aisha"]),
    ]

    reused = 0
    for title, desc, owner, members in specs:
        existing = find_conversation(owner, title, "GROUP")
        if existing:
            W.groups.append(dict(id=existing, owner=owner, members=members, title=title))
            reused += 1
            continue
        res = API.post("/api/v1/conversations", W.tok(owner),
                       json_body=dict(type="GROUP", title=title, description=desc,
                                      memberIds=[W.id(m) for m in members]),
                       label="group %s" % title, quiet=True)
        if res and res.get("id"):
            W.groups.append(dict(id=res["id"], owner=owner, members=members, title=title))
    ok("%d groups (%d new, %d reused)" % (len(W.groups), len(W.groups) - reused, reused))

    # a reused group may have members who LEFT on an earlier run — restore the
    # roster before messaging so every speaker is an active member
    if reused:
        for g in W.groups:
            API.post("/api/v1/conversations/%s/members" % g["id"], W.tok(g["owner"]),
                     json_body=dict(userIds=[W.id(m) for m in g["members"]]),
                     quiet=True, tolerate=(400, 403, 409))

    sent = 0
    for g in W.groups:
        roster = [g["owner"]] + g["members"]
        for i, line in enumerate(GROUP_LINES[:random.randint(9, 17)]):
            who = roster[i % len(roster)]
            res = API.post("/api/v1/conversations/%s/messages" % g["id"], W.tok(who),
                           json_body=dict(clientNonce=nonce(), type="TEXT", body=line),
                           label="group msg", quiet=True)
            if mid(res):
                sent += 1
                W.messages.append(dict(id=mid(res), conv=g["id"], author=who))
        # a file drop and a group poll
        API.post("/api/v1/conversations/%s/messages" % g["id"], W.tok(g["owner"]),
                 json_body=dict(clientNonce=nonce(), type="FILE",
                                body="Scans for this week's session.",
                                media=[media_ref("FILE", 7)]), quiet=True)
        pres = API.post("/api/v1/conversations/%s/messages" % g["id"], W.tok(g["owner"]),
                        json_body=dict(clientNonce=nonce(), type="POLL",
                                       poll=dict(question="Next session time?",
                                                 options=["Thu 7pm", "Fri 5pm", "Sat 10am"],
                                                 anonymous=False,
                                                 allowsMultipleAnswers=False)),
                        label="group poll", quiet=True)
        if mid(pres):
            for voter in pick(roster, min(6, len(roster))):
                API.post("/api/v1/messages/%s/poll/votes" % mid(pres), W.tok(voter),
                         json_body=dict(optionIndexes=[random.randint(0, 2)]), quiet=True)
    ok("%d group messages (+ files, polls with votes)" % sent)

    for g in W.groups:
        if len(g["members"]) >= 2:
            API.post("/api/v1/conversations/%s/members/%s/role" % (g["id"], W.id(g["members"][0])),
                     W.tok(g["owner"]), json_body=dict(role="ADMIN"),
                     label="promote admin", quiet=True)
        inv = API.post("/api/v1/conversations/%s/invite-link" % g["id"], W.tok(g["owner"]),
                       json_body=dict(expiresInHours=168, memberLimit=50),
                       label="invite link", quiet=True)
        if inv:
            g["invite"] = inv.get("token") or inv.get("inviteToken")
        API.post("/api/v1/conversations/%s/invite-links" % g["id"], W.tok(g["owner"]),
                 json_body=dict(expiresInHours=24, memberLimit=5), quiet=True)
        msgs = [m for m in W.messages if m["conv"] == g["id"]]
        if msgs:
            senders = {m["author"] for m in msgs} | {g["owner"]}
            for who in senders:
                API.post("/api/v1/conversations/%s/read" % g["id"], W.tok(who),
                         json_body=dict(lastReadMessageId=msgs[-1]["id"]),
                         quiet=True, tolerate=(403,))

    # restrict one member and have one leave — populates member-state surfaces
    g = W.groups[0]
    API.post("/api/v1/conversations/%s/members/%s/restrict" % (g["id"], W.id("u_zayd")),
             W.tok(g["owner"]),
             json_body=dict(canSendMessages=False, untilEpochMs=now_ms() + 86_400_000),
             label="restrict member", quiet=True)
    API.post("/api/v1/conversations/%s/members" % W.groups[1]["id"],
             W.tok(W.groups[1]["owner"]),
             json_body=dict(userIds=[W.id("u_khadija")]), quiet=True)
    API.post("/api/v1/conversations/%s/leave" % W.groups[1]["id"], W.tok("u_khadija"),
             label="leave group", quiet=True, tolerate=(403,))
    # add a late joiner
    API.post("/api/v1/conversations/%s/members" % g["id"], W.tok(g["owner"]),
             json_body=dict(userIds=[W.id("u_khadija")]), label="add member", quiet=True)
    ok("roles, invite links, restriction, leave + late join applied")


# ═══════════════════════════════════════════════════════════════════════════
#  PHASE 11 — CHANNELS
# ═══════════════════════════════════════════════════════════════════════════

def phase11_channels():
    phase("PHASE 11 — CHANNELS  ·  broadcast, subscribers, posts, views, comments")

    W.channels.clear()    # this phase owns W.channels (see phase 9)

    specs = [
        ("IRC Announcements", "Official announcements from the Islamic Research Center.",
         "ircnews", "Institutional", "admin"),
        ("Usul al-Fiqh Daily", "One legal-theory note a day.",
         "usuldaily", "Education", "sh_ahmad"),
        ("Manuscript Finds", "Codicology, provenance, and new acquisitions.",
         "mssfinds", "Research", "r_fatima"),
    ]
    reused = 0
    for title, desc, handle, cat, owner in specs:
        existing = API.get("/api/v1/channels/by-handle/%s" % handle, W.tok(owner),
                           quiet=True)
        if isinstance(existing, dict) and existing.get("id"):
            W.channels.append(dict(id=existing["id"], owner=owner,
                                   title=title, handle=handle))
            reused += 1
            continue
        res = API.post("/api/v1/channels", W.tok(owner),
                       json_body=dict(title=title, description=desc, handle=handle,
                                      publicChannel=True, category=cat),
                       label="channel %s" % title, quiet=True)
        if res and res.get("id"):
            W.channels.append(dict(id=res["id"], owner=owner, title=title, handle=handle))
    ok("%d channels (%d new, %d reused)" % (len(W.channels),
                                            len(W.channels) - reused, reused))

    subs = 0
    for ch in W.channels:
        ch["subs"] = []
        for k in [x for x in W.keys if x != ch["owner"]]:
            if random.random() < 0.85:
                if API.post("/api/v1/channels/%s/subscribe" % ch["id"], W.tok(k),
                            quiet=True) is not None:
                    subs += 1
                    ch["subs"].append(k)
    ok("%d channel subscriptions" % subs)

    posts = views = comments = 0
    for ch in W.channels:
        ch["posts"] = []
        for i, text in enumerate(CHANNEL_POSTS):
            body = dict(clientNonce=nonce(), type="TEXT", body=text)
            if i % 3 == 1:
                body = dict(clientNonce=nonce(), type="IMAGE", body=text,
                            media=[media_ref("IMAGE", 20 + i)])
            res = API.post("/api/v1/conversations/%s/messages" % ch["id"],
                           W.tok(ch["owner"]), json_body=body,
                           label="channel post", quiet=True)
            if mid(res):
                posts += 1
                ch["posts"].append(mid(res))
        # a channel poll
        pres = API.post("/api/v1/conversations/%s/messages" % ch["id"], W.tok(ch["owner"]),
                        json_body=dict(clientNonce=nonce(), type="POLL",
                                       poll=dict(question="What should we cover next?",
                                                 options=["Usul", "Hadith", "Tafsir",
                                                          "Manuscripts"],
                                                 anonymous=True)),
                        label="channel poll", quiet=True)
        if mid(pres):
            ch["posts"].append(mid(pres))
            for voter in pick(ch.get("subs") or W.keys, 9):
                API.post("/api/v1/messages/%s/poll/votes" % mid(pres), W.tok(voter),
                         json_body=dict(optionIndexes=[random.randint(0, 3)]), quiet=True)

        # views (batched, as the client does)
        audience = ch.get("subs") or [x for x in W.keys if x != ch["owner"]]
        if ch["posts"]:
            for k in audience:
                r = API.post("/api/v1/channels/%s/posts/views" % ch["id"], W.tok(k),
                             json_body=dict(messageIds=ch["posts"][:20]), quiet=True)
                if r is not None:
                    views += 1
        # reactions on channel posts
        for pid in ch["posts"][:5]:
            for k in pick(audience, random.randint(3, min(8, len(audience)))):
                API.post("/api/v1/messages/%s/react" % pid, W.tok(k),
                         json_body=dict(emoji=random.choice(["👍", "📚", "❤️", "🤲"])),
                         rate="reaction", actor=k, quiet=True)

    # discussion group linked to the first channel + comments on posts
    if W.channels:
        ch = W.channels[0]
        dg_title = "IRC Announcements — Discussion"
        dg_id = find_conversation(ch["owner"], dg_title, "GROUP")
        if not dg_id:
            dg = API.post("/api/v1/conversations", W.tok(ch["owner"]),
                          json_body=dict(type="GROUP", title=dg_title,
                                         description="Comments on channel posts.",
                                         memberIds=[W.id(k) for k in W.keys
                                                    if k != ch["owner"]][:10]),
                          label="discussion group", quiet=True)
            dg_id = (dg or {}).get("id")
        if dg_id:
            API.put("/api/v1/channels/%s/discussion-group/%s" % (ch["id"], dg_id),
                    W.tok(ch["owner"]), label="link discussion", quiet=True)
            for pid in ch["posts"][:4]:
                for k in pick(ch.get("subs") or [x for x in W.keys
                                                 if x != ch["owner"]], 3):
                    r = API.post("/api/v1/channels/%s/posts/%s/comments" % (ch["id"], pid),
                                 W.tok(k),
                                 json_body=dict(clientNonce=nonce(), type="TEXT",
                                                body=random.choice(COMMENTS)),
                                 label="channel comment", quiet=True)
                    if r is not None:
                        comments += 1
    ok("%d channel posts · %d viewer batches · %d comments" % (posts, views, comments))

    # verify one channel + gather stats (admin surfaces)
    if W.channels:
        API.put("/api/v1/channels/%s/verified" % W.channels[0]["id"], W.tok("admin"),
                params=dict(verified="true"), label="verify channel", quiet=True)
        for ch in W.channels:
            API.get("/api/v1/channels/%s/stats" % ch["id"], W.tok(ch["owner"]), quiet=True)
    API.get("/api/v1/channels/discover", W.tok("u_hamza"), quiet=True)


# ═══════════════════════════════════════════════════════════════════════════
#  PHASE 12 — CALLS
# ═══════════════════════════════════════════════════════════════════════════

def phase12_calls():
    phase("PHASE 12 — CALLS  ·  answered, declined (missed), ended")

    if not W.dms:
        warn("no direct conversations — skipping")
        return

    scenarios = [("VOICE", "accept"), ("VIDEO", "accept"),
                 ("VOICE", "decline"), ("VIDEO", "decline"), ("VOICE", "accept")]
    for i, (ctype, outcome) in enumerate(scenarios):
        c = W.dms[i % len(W.dms)]
        caller, callee = c["a"], c["b"]
        res = API.post("/api/v1/conversations/%s/calls" % c["id"], W.tok(caller),
                       json_body=dict(type=ctype), label="call %s" % ctype, quiet=True)
        if not res or not res.get("id"):
            continue
        cid = res["id"]
        W.calls.append(dict(id=cid, type=ctype, outcome=outcome))
        if outcome == "accept":
            API.post("/api/v1/calls/%s/accept" % cid, W.tok(callee), quiet=True)
            for who, kind in ((caller, "OFFER"), (callee, "ANSWER"),
                              (caller, "ICE"), (callee, "ICE")):
                peer = callee if who == caller else caller
                API.post("/api/v1/calls/%s/signal" % cid, W.tok(who),
                         json_body=dict(kind=kind, toUserId=W.id(peer),
                                        payload=json.dumps(
                                            {"sdp": "v=0 o=- seed 2 IN IP4 127.0.0.1"})),
                         label="call signal %s" % kind, quiet=True)
            time.sleep(0.4)
            API.post("/api/v1/calls/%s/end" % cid, W.tok(caller), quiet=True)
        else:
            API.post("/api/v1/calls/%s/decline" % cid, W.tok(callee), quiet=True)
    ok("%d calls (%d answered, %d declined→missed)" % (
        len(W.calls),
        sum(1 for c in W.calls if c["outcome"] == "accept"),
        sum(1 for c in W.calls if c["outcome"] == "decline")))


# ═══════════════════════════════════════════════════════════════════════════
#  PHASE 13 — LIVE STREAMS
# ═══════════════════════════════════════════════════════════════════════════

def phase13_streams():
    phase("PHASE 13 — LIVE STREAMS  ·  go live, viewers, chat, gifts, stage")

    specs = [
        ("sh_ahmad", "The Structure of Legal Disagreement",
         "A live session on reading ikhtilaf without picking a side first.", True),
        ("sh_maryam", "Isnad Criticism — Live Q&A",
         "Bring your questions on the common-link theory.", False),
        ("r_fatima", "Manuscript Show & Tell",
         "Three folios, three provenance puzzles.", False),
    ]
    for host, title, desc, record in specs:
        res = API.post("/api/v1/streams", W.tok(host),
                       json_body=dict(title=title, description=desc, record=record),
                       label="go live: %s" % title[:32], quiet=True)
        if res and res.get("id"):
            W.streams.append(dict(id=res["id"], host=host, title=title))
    ok("%d live streams started" % len(W.streams))

    gifts = API.get("/api/v1/streams/gifts/catalog", W.tok("u_hamza"), quiet=True) or []
    gift_ids = [g.get("id") or g.get("giftId") for g in gifts if isinstance(g, dict)]
    gift_ids = [g for g in gift_ids if g] or ["ROSE", "HEART", "CLAP", "STAR", "CROWN"]

    joins = chats = gsent = reacts = 0
    for s in W.streams:
        viewers = [k for k in W.keys if k != s["host"]]
        for v in viewers:
            if API.post("/api/v1/streams/%s/join" % s["id"], W.tok(v), quiet=True) is not None:
                joins += 1
        for i in range(random.randint(8, 14)):
            v = random.choice(viewers)
            if API.post("/api/v1/streams/%s/chat" % s["id"], W.tok(v),
                        json_body=dict(text=random.choice(LIVE_CHAT)), quiet=True) is not None:
                chats += 1
        for v in pick(viewers, random.randint(4, 8)):
            if API.post("/api/v1/streams/%s/reactions" % s["id"], W.tok(v),
                        json_body=dict(type=random.choice(["HEART", "CLAP", "FIRE", "WOW"])),
                        rate="reaction", actor=v, quiet=True) is not None:
                reacts += 1
        for v in pick(viewers, random.randint(3, 6)):
            if API.post("/api/v1/streams/%s/gifts" % s["id"], W.tok(v),
                        json_body=dict(giftId=random.choice(gift_ids)), quiet=True) is not None:
                gsent += 1
        API.get("/api/v1/streams/%s/gifts/top" % s["id"], W.tok(s["host"]), quiet=True)

    # multi-guest stage on the first stream
    if W.streams:
        s = W.streams[0]
        guests = ["r_bilal", "u_hamza", "r_sara"]
        for g in guests:
            API.post("/api/v1/streams/%s/stage/requests" % s["id"], W.tok(g),
                     label="stage request", quiet=True)
        API.get("/api/v1/streams/%s/stage/requests" % s["id"], W.tok(s["host"]), quiet=True)
        for g in guests[:2]:
            API.post("/api/v1/streams/%s/stage/requests/%s/approve" % (s["id"], W.id(g)),
                     W.tok(s["host"]), label="approve guest", quiet=True)
        # host-initiated invite → the guest accepts
        API.post("/api/v1/streams/%s/stage/invites/%s" % (s["id"], W.id("u_aisha")),
                 W.tok(s["host"]), label="invite guest", quiet=True)
        API.post("/api/v1/streams/%s/stage/accept" % s["id"], W.tok("u_aisha"),
                 label="guest accepts invite", quiet=True)
        API.post("/api/v1/streams/%s/stage/requests/%s/deny" % (s["id"], W.id(guests[2])),
                 W.tok(s["host"]), quiet=True)
        API.post("/api/v1/streams/%s/stage/%s/mute" % (s["id"], W.id(guests[0])),
                 W.tok(s["host"]), quiet=True)
        API.post("/api/v1/streams/%s/stage/%s/unmute" % (s["id"], W.id(guests[0])),
                 W.tok(s["host"]), quiet=True)
        API.get("/api/v1/streams/%s/stage" % s["id"], W.tok(s["host"]), quiet=True)
        API.post("/api/v1/streams/%s/stage/leave" % s["id"], W.tok(guests[1]), quiet=True)
    ok("%d joins · %d live chats · %d reactions · %d gifts · stage exercised"
       % (joins, chats, reacts, gsent))

    # the surviving stream records, so the recordings panel has a row
    if W.streams:
        last = W.streams[-1]
        API.post("/api/v1/streams/%s/recording/start" % last["id"], W.tok(last["host"]),
                 label="start recording", quiet=True, tolerate=(400, 409, 502, 503))
        API.get("/api/v1/streams/%s/recording" % last["id"], W.tok(last["host"]),
                quiet=True)

    # end two, leave one live so the "live now" rail has content
    for s in W.streams[:-1]:
        API.post("/api/v1/streams/%s/end" % s["id"], W.tok(s["host"]),
                 label="end stream", quiet=True)
    ok("%d streams ended, 1 left live" % max(0, len(W.streams) - 1))
    API.get("/api/v1/streams/live", W.tok("u_hamza"), quiet=True)


# ═══════════════════════════════════════════════════════════════════════════
#  PHASE 14 — SAFETY / MODERATION
# ═══════════════════════════════════════════════════════════════════════════

def phase14_safety():
    phase("PHASE 14 — SAFETY  ·  reports across every target type")

    targets = []
    if W.posts:
        targets += [("POST", p["id"], "SPAM",
                     "Repeated promotional links unrelated to the discussion.")
                    for p in W.posts[:2]]
        targets.append(("POST", W.posts[2]["id"], "MISINFORMATION",
                        "Attributes a ruling to a school that does not hold it."))
    if W.comments:
        targets.append(("COMMENT", W.comments[0]["id"], "HARASSMENT",
                        "Personal attack on the author rather than the argument."))
    if W.research:
        targets.append(("RESEARCH", W.research[0]["id"], "COPYRIGHT",
                        "Large verbatim block from a copyrighted edition."))
    if W.questions:
        targets.append(("QUESTION", W.questions[0]["id"], "OTHER",
                        "Duplicate of an existing question."))
    if W.answers:
        targets.append(("ANSWER", W.answers[0]["id"], "MISINFORMATION",
                        "Citation does not support the claim made."))
    if W.messages:
        targets.append(("MESSAGE", None, "HARASSMENT",
                        "Unwanted repeated contact after being asked to stop."))
    if W.channels:
        targets.append(("CHANNEL", W.channels[-1]["id"], "SPAM",
                        "Channel is posting unrelated commercial content."))
    if W.stories:
        targets.append(("STORY", W.stories[0]["id"], "NUDITY_SEXUAL",
                        "Reported by a member for inappropriate imagery."))
    targets.append(("USER", W.id("u_zayd"), "IMPERSONATION",
                    "Profile claims an academic title that cannot be verified."))

    reporters = ["u_hamza", "u_aisha", "u_khadija", "r_sara", "r_bilal",
                 "u_zayd", "r_fatima", "mod"]
    made = []
    for i, (ttype, tid, reason, details) in enumerate(targets):
        who = reporters[i % len(reporters)]
        body = dict(targetType=ttype, reason=reason, details=details)
        if tid:
            body["targetId"] = str(tid)
        else:
            body["targetRef"] = str(W.messages[0]["id"])
        res = API.post("/api/v1/safety/reports", W.tok(who), json_body=body,
                       label="report %s/%s" % (ttype, reason), quiet=True,
                       tolerate=(409,))
        if res and res.get("id"):
            made.append(res["id"])
    ok("%d reports filed across %d target types"
       % (len(made), len({t[0] for t in targets})))

    if made:
        API.post("/api/v1/safety/reports/%s/appeal" % made[0], W.tok("u_hamza"),
                 quiet=True, tolerate=(409,))
    for k in pick(W.keys, 4):
        API.get("/api/v1/safety/score", W.tok(k), quiet=True)
        API.get("/api/v1/safety/strikes", W.tok(k), quiet=True)
    W.reports = made


# ═══════════════════════════════════════════════════════════════════════════
#  PHASE 15 — SETTINGS
# ═══════════════════════════════════════════════════════════════════════════

def phase15_settings():
    phase("PHASE 15 — SETTINGS  ·  privacy, notifications, DND, consent, policies")

    for k in W.keys:
        t = W.tok(k)
        API.get("/api/v1/settings", t, quiet=True)
        API.put("/api/v1/settings/appearance", t,
                json_body=dict(theme=random.choice(["LIGHT", "DARK", "SYSTEM"]),
                               fontScale=random.choice([1.0, 1.15, 1.3]),
                               language=random.choice(["en", "ar"])), quiet=True)
        for field in pick(["BIO", "BIRTHDAY", "PROFILE_PICTURE", "LOCATION",
                           "EMAIL_ADDRESS", "PHONE_NUMBER", "FOLLOWERS", "POSTS",
                           "STORIES", "RESEARCH", "WHO_CAN_MESSAGE",
                           "WHO_CAN_MENTION", "WHO_CAN_TAG"], 5):
            API.put("/api/v1/settings/privacy/%s" % field, t,
                    json_body=dict(visibility=random.choice(
                        ["EVERYONE", "FOLLOWERS", "CLOSE_FRIENDS", "ONLY_ME"])),
                    quiet=True)
        API.put("/api/v1/settings/presence", t,
                json_body=dict(showOnlineStatus=random.random() > 0.3,
                               showReadReceipts=random.random() > 0.2), quiet=True)
        API.put("/api/v1/settings/discovery", t,
                json_body=dict(discoverableByPhone=random.random() > 0.4,
                               discoverableByEmail=random.random() > 0.5,
                               appearInSuggestions=True), quiet=True)
        API.post("/api/v1/settings/consent", t,
                 json_body=dict(scope="ANALYTICS", granted=random.random() > 0.25,
                                version="2026-08-01"), quiet=True)
        API.post("/api/v1/app/policies/privacy/accept", t,
                 json_body=dict(version="2026-08-01"), quiet=True)
        API.post("/api/v1/app/policies/terms/accept", t,
                 json_body=dict(version="2026-08-01"), quiet=True)
        API.get("/api/v1/settings/notifications", t, quiet=True)
        API.get("/api/v1/storage/usage", t, quiet=True)
        API.get("/api/v1/settings/discovery/qr", t, quiet=True)

    # DND windows for a few users
    for k in ["sh_ahmad", "r_fatima", "u_aisha"]:
        API.put("/api/v1/settings/notifications/dnd", W.tok(k),
                json_body=dict(enabled=True, timezone="Africa/Cairo",
                               startTime="22:00", endTime="06:00",
                               daysMask=127), quiet=True)
    # a couple of notification-matrix toggles
    for k in pick(W.keys, 5):
        for event in pick(["NEW_FOLLOWER", "POST_REACTED", "POST_COMMENTED",
                           "ANSWER_ACCEPTED", "NEW_MESSAGE", "STREAM_STARTED",
                           "TRENDING_DIGEST", "CHANNEL_NEW_POST"], 3):
            API.put("/api/v1/settings/notifications/%s/%s"
                    % (event, random.choice(["PUSH", "EMAIL", "IN_APP"])),
                    W.tok(k), json_body=dict(enabled=random.random() > 0.35),
                    quiet=True)
    # email preferences + push tokens
    for k in pick(W.keys, 6):
        API.patch("/api/v1/users/me/email-preferences", W.tok(k),
                  json_body=dict(emailTrendingEnabled=random.random() > 0.5,
                                 emailSocialEnabled=True), quiet=True)
        API.post("/api/v1/settings/notifications/push-tokens", W.tok(k),
                 json_body=dict(provider=random.choice(["FCM", "APNS", "WEBPUSH"]),
                                token="seed-push-" + uuid.uuid4().hex,
                                platform=random.choice(["IOS", "ANDROID", "WEB"])),
                 quiet=True)
    # muted keywords + a privacy list
    for k in ["r_sara", "u_khadija"]:
        API.post("/api/v1/settings/privacy/keywords", W.tok(k),
                 json_body=dict(keyword=random.choice(["politics", "spam", "giveaway"])),
                 quiet=True)
    API.post("/api/v1/settings/privacy/lists", W.tok("sh_ahmad"),
             json_body=dict(name="Students"), quiet=True)
    # one data-export job so the privacy dashboard has a row
    for k in ("u_hamza", "sh_ahmad", "r_fatima", "u_aisha"):
        API.post("/api/v1/privacy/export", W.tok(k),
                 json_body=dict(scope="ALL"), quiet=True, tolerate=(429,))
    ok("settings, consent, policies, DND, push tokens and one export job written")


# ═══════════════════════════════════════════════════════════════════════════
#  PHASE 16 — TRAFFIC
# ═══════════════════════════════════════════════════════════════════════════

def phase16_traffic():
    phase("PHASE 16 — TRAFFIC  ·  feeds, search, trending, notifications, activity")

    queries = ["istihsan", "isnad", "maqasid", "manuscript", "tafsir", "al-Muwafaqat",
               "brain death", "hanafi", "codicology", "reading circle", "zzzznothing"]
    reads = 0
    for k in W.keys:
        t = W.tok(k)
        for path, params in [
            ("/api/v1/posts/feed/home", dict(pageSize=20, ranked="true")),
            ("/api/v1/posts/feed", dict(pageSize=20)),
            ("/api/v1/posts/reels/for-you", dict(pageSize=10)),
            ("/api/v1/posts/reels/following", dict(pageSize=10)),
            ("/api/v1/posts/feed/live-now", None),
            ("/api/v1/researches/feed", dict(size=10)),
            ("/api/v1/questions", dict(size=10)),
            ("/api/v1/conversations", dict(size=20)),
            ("/api/v1/tags/trending", dict(scope="ALL", limit=20)),
            ("/api/v1/notifications", dict(size=20)),
            ("/api/v1/notifications/unread/count", None),
            ("/api/v1/mentions/me", dict(size=20)),
            ("/api/v1/users/me/activity", dict(size=20)),
            ("/api/v1/users/me/reels/watched", dict(size=10)),
            ("/api/v1/message-requests/count", None),
        ]:
            if API.get(path, t, params=params, quiet=True) is not None:
                reads += 1
        for q in pick(queries, 4):
            API.get("/api/v1/search", t, params=dict(q=q, size=10), quiet=True)
            reads += 1
    ok("%d authenticated read requests issued" % reads)

    # scope-specific trending + tag pages
    for scope in ["ALL", "POST", "QUESTION", "RESEARCH", "REEL"]:
        API.get("/api/v1/tags/trending", W.tok("admin"),
                params=dict(scope=scope, limit=20), quiet=True)
    for tag in ["usul", "hadith", "maqasid", "manuscripts", "tafsir"]:
        API.get("/api/v1/tags/%s/content" % tag, W.tok("u_hamza"), quiet=True)
        API.get("/api/v1/hashtags/%s/posts" % tag, W.tok("u_hamza"), quiet=True)

    # mark some notifications read so the counters are not uniformly unread
    for k in pick(W.keys, 6):
        n = API.get("/api/v1/notifications/unread", W.tok(k),
                    params=dict(size=5), quiet=True)
        items = (n or {}).get("content") if isinstance(n, dict) else n
        for it in (items or [])[:3]:
            if isinstance(it, dict) and it.get("id"):
                API.patch("/api/v1/notifications/%s/read" % it["id"], W.tok(k), quiet=True)
    API.patch("/api/v1/notifications/read-all", W.tok("u_zayd"), quiet=True)

    # re-login everyone once → login_events + sessions for the admin logs surface
    for k in W.keys:
        API.post("/api/v1/auth/login",
                 json_body=dict(username=W.u(k)["username"], password=PASSWORD),
                 quiet=True)
    ok("trending scopes, tag pages, notification reads and a second login wave done")


# ═══════════════════════════════════════════════════════════════════════════
#  MAIN
# ═══════════════════════════════════════════════════════════════════════════

def phase17_admin_ops():
    phase("PHASE 17 — ADMIN OPERATIONS  ·  moderation decisions, cases, ops")

    A = W.tok("admin")
    A2 = W.tok("admin2") if "admin2" in W.users else None
    step_up("admin", force=True)
    if A2:
        step_up("admin2", force=True)

    def sadmin(path, body=None, token=None, key="admin", label=None, **kw):
        step_up(key)
        kw.setdefault("tolerate", (409,))   # re-runs re-enter settled states
        return API.post(path, token or W.tok(key), json_body=body,
                        label=label or path, quiet=True, **kw)

    # ── 1. safety queue: triage → dismiss / action → strike ───────────────
    # the admin queue aggregates by (target, reason) group; the transitions
    # take a concrete report id, so collect those from the reporters' own lists
    report_ids = []
    for k in W.keys:
        mine = API.get("/api/v1/safety/reports", W.tok(k), params=dict(size=20), quiet=True)
        items = (mine or {}).get("content") if isinstance(mine, dict) else mine
        for r in (items or []):
            if isinstance(r, dict) and r.get("id") and r.get("state") in (
                    None, "SUBMITTED", "TRIAGED"):
                report_ids.append(r["id"])
    triaged = dismissed = actioned = 0
    for i, rid in enumerate(report_ids[:9]):
        if sadmin("/api/v1/admin/safety/reports/%s/triage" % rid,
                  dict(note="Reviewed against the community guidelines.")) is not None:
            triaged += 1
        if i % 3 == 0:
            if sadmin("/api/v1/admin/safety/reports/%s/dismiss" % rid,
                      dict(note="No guideline breach — the citation is accurate.")
                      ) is not None:
                dismissed += 1
        elif i % 3 == 1:
            # inline issueStrike is only valid on USER-target reports, so the
            # strikes are issued explicitly below
            if sadmin("/api/v1/admin/safety/reports/%s/action" % rid,
                      dict(resolution=random.choice(
                          ["WARNING_ISSUED", "CONTENT_REMOVED", "NO_ACTION"]),
                          note="Warned the author; content left in place.")
                      ) is not None:
                actioned += 1
    # strikes straight onto the user record
    for who, why in [("u_zayd", "Repeated off-topic promotion in Q&A threads"),
                     ("u_hamza", "Reposted copyrighted material after a warning")]:
        sadmin("/api/v1/admin/safety/users/%s/strikes" % W.id(who),
               dict(reason=why), label="issue strike %s" % who)
    ok("safety: %d triaged · %d dismissed · %d actioned · strikes issued"
       % (triaged, dismissed, actioned))

    # ── 2. content moderation: keyword blocklist + a takedown/restore ─────
    for kw, sev in [("free money", "BLOCK"), ("guaranteed cure", "BLOCK"),
                    ("click here to win", "FLAG"), ("miracle remedy", "FLAG")]:
        sadmin("/api/v1/admin/content/blocklist",
               dict(keyword=kw, severity=sev,
                    note="Seeded platform keyword rule"), label="blocklist %s" % kw)
    API.post("/api/v1/admin/content/blocklist/test", A,
             json_body=dict(text="Get free money now — guaranteed cure inside!"),
             label="blocklist test", quiet=True)
    if W.posts:
        p = W.posts[-1]
        sadmin("/api/v1/admin/content/posts/%s/remove" % p["id"],
               dict(reason="Duplicate of an earlier post by the same author"),
               label="remove post")
        sadmin("/api/v1/admin/content/posts/%s/restore" % p["id"],
               dict(reason="Author appealed; duplicate was in fact a correction"),
               label="restore post")
    ok("content: 4 keyword rules + one takedown/restore cycle")

    # ── 3. research flags ────────────────────────────────────────────────
    flagged = 0
    for i, r in enumerate(W.research[:3]):
        res = sadmin("/api/v1/admin/research/%s/flags" % r["id"],
                     dict(type="QUALITY" if i % 2 else "PLAGIARISM",
                          note="Reviewer raised a sourcing concern in section 3."),
                     label="flag research")
        if res and res.get("id"):
            flagged += 1
            if i == 0:
                sadmin("/api/v1/admin/research/flags/%s/resolve" % res["id"],
                       dict(reason="Checked against the original — correctly cited."),
                       label="resolve flag")
    ok("research: %d flags raised, 1 resolved" % flagged)

    # ── 4. Q&A moderation ────────────────────────────────────────────────
    if W.questions:
        sadmin("/api/v1/admin/qna/questions/%s/close" % W.questions[-1]["id"],
               dict(reason="Answered comprehensively; closing to new answers."),
               label="close question")
        sadmin("/api/v1/admin/qna/questions/%s/reopen" % W.questions[-1]["id"],
               dict(reason="New evidence submitted — reopening."),
               label="reopen question")
    ok("qna: one thread closed then reopened")

    # ── 5. sound moderation ──────────────────────────────────────────────
    pend = API.get("/api/v1/admin/sounds", A,
                   params=dict(status="PENDING_REVIEW"), quiet=True) or []
    for i, s in enumerate(pend if isinstance(pend, list) else []):
        sid = s.get("id")
        if not sid:
            continue
        if i == 0:
            sadmin("/api/v1/admin/sounds/%s/reject" % sid,
                   dict(reason="Rights unclear"), label="reject sound")
    appr = API.get("/api/v1/admin/sounds", A, params=dict(status="APPROVED"),
                   quiet=True) or []
    if isinstance(appr, list) and appr:
        sadmin("/api/v1/admin/sounds/%s/trending-exclude" % appr[0]["id"],
               dict(excluded=True), label="exclude sound from trending")
        if len(appr) > 1:
            sadmin("/api/v1/admin/sounds/%s/archive" % appr[-1]["id"],
                   dict(reason="Superseded by a higher-quality upload"),
                   label="archive sound")
    ok("sounds: reject / archive / trending-exclude applied")

    # ── 6. channel moderation + channel-level invite links ───────────────
    for ch in W.channels:
        API.post("/api/v1/conversations/%s/invite-links" % ch["id"], W.tok(ch["owner"]),
                 json_body=dict(expiresInHours=72, memberLimit=100),
                 label="channel invite link", quiet=True, tolerate=(403, 400))
    if W.channels:
        step_up("admin")
        API.patch("/api/v1/admin/channels/%s/verified" % W.channels[0]["id"], A,
                  params=dict(verified="true"), label="verify channel", quiet=True)
        sadmin("/api/v1/admin/channels/%s/unlist" % W.channels[-1]["id"],
               dict(reason="Under review for off-topic commercial posts"),
               label="unlist channel")
        sadmin("/api/v1/admin/channels/%s/freeze" % W.channels[-1]["id"],
               dict(reason="Posting paused pending review"), label="freeze channel")
        sadmin("/api/v1/admin/channels/%s/unfreeze" % W.channels[-1]["id"],
               dict(reason="Review closed, no action needed"), label="unfreeze channel")
    ok("channels: verified / unlisted / frozen+unfrozen, invite links issued")

    # ── 7. break-glass dual-control case ─────────────────────────────────
    if A2:
        target = W.id("u_zayd")
        case = sadmin("/api/v1/admin/breakglass/%s" % target,
                      dict(kind="SAFETY_INVESTIGATION",
                           reason="Multiple reports of coordinated off-topic posting; "
                                  "activity review authorised by the safety lead.",
                           caseRef="IRC-SAFETY-2026-014"),
                      label="open breakglass case")
        cid = (case or {}).get("id")
        if cid:
            step_up("admin2")
            approved = API.post("/api/v1/admin/breakglass/cases/%s/approve" % cid, A2,
                                label="approve breakglass (2nd admin)", quiet=True)
            if approved is not None:
                # the activity endpoints are now unlocked for this target
                API.get("/api/v1/admin/users/%s/activity" % target, A,
                        params=dict(size=20), quiet=True)
                API.get("/api/v1/admin/users/%s/activity/summary" % target, A, quiet=True)
                API.get("/api/v1/admin/users/%s/reels/watched" % target, A, quiet=True)
                ok("break-glass case opened, dual-control approved, activity reviewed")
    else:
        warn("no second admin — break-glass dual control skipped")

    # ── 8. legal hold ────────────────────────────────────────────────────
    if W.dms:
        hold = sadmin("/api/v1/admin/chat/legal-holds",
                      dict(conversationId=W.dms[0]["id"],
                           reason="Preservation request IRC-LEGAL-2026-003"),
                      label="open legal hold")
        hid = (hold or {}).get("id")
        if hid and A2:
            step_up("admin2")
            API.post("/api/v1/admin/chat/legal-holds/%s/approve" % hid, A2,
                     json_body=dict(note="Approved by compliance."),
                     label="approve legal hold", quiet=True)
        ok("legal hold opened%s" % (" and approved" if hid and A2 else ""))

    # ── 9. notifications: announcement + digest run ──────────────────────
    sadmin("/api/v1/admin/notifications/announcements",
           dict(title="Reading room opens on the 1st",
                body="The IRC reading room opens next month. Booking opens next week "
                     "for all members; researchers get priority slots.",
                audienceRole="USER", activeSinceDays=90,
                dryRun=False, confirmLargeAudience=True),
           label="announcement")
    sadmin("/api/v1/admin/notifications/digest/run", None, label="run trending digest")
    ok("notifications: announcement published, trending digest run")

    # ── 10. trending overrides ───────────────────────────────────────────
    for tag, typ, rank in [("usul", "PIN", 1), ("hadith", "PIN", 2),
                           ("giveaway", "BAN", None)]:
        sadmin("/api/v1/admin/trending/overrides",
               dict(tag=tag, scope="ALL", type=typ, rank=rank,
                    reason="Editorial curation for the launch week"),
               label="trending override %s" % tag)
    sadmin("/api/v1/admin/trending/rebuild", None, label="rebuild trending")
    ok("trending: 2 pins + 1 block, index rebuilt")

    # ── 11. logs: saved view + alert rule + export ───────────────────────
    sadmin("/api/v1/admin/logs/views",
           dict(name="Failed logins (24h)", query="event=LOGIN_FAILED&window=24h"),
           label="saved log view")
    for kind, name, thr in [("FAILED_LOGIN_PER_ACCOUNT", "Login failures per account", 10),
                            ("FAILED_LOGIN_PER_IP", "Login failures per IP", 25),
                            ("REPORT_PILE_ON", "Report pile-on", 5),
                            ("DLQ_ARRIVALS", "Dead-letter arrivals", 1)]:
        sadmin("/api/v1/admin/logs/alerts",
               dict(kind=kind, name=name, threshold=thr, windowMinutes=15,
                    severity="HIGH", enabled=True), label="alert rule %s" % kind)
    step_up("admin")
    API.post("/api/v1/admin/logs/export", A,
             json_body=dict(query="user:%s stores:audit" % W.u("u_hamza")["username"]),
             accept="text/csv", label="log export", quiet=True)
    ok("logs: saved view, alert rule and export job created")

    # ── 12. media: quotas + reconcile ────────────────────────────────────
    for role in ("USER", "RESEARCHER", "SCHOLAR"):
        step_up("admin")
        API.put("/api/v1/admin/media/quotas/%s" % role, A,
                json_body=dict(maxBytes=5_368_709_120 if role != "USER" else 1_073_741_824,
                               maxAssets=5000),
                label="media quota %s" % role, quiet=True)
    sadmin("/api/v1/admin/media/reconcile", None, label="media reconcile")
    ok("media: per-role quotas set, storage reconcile run")

    # ── 13. search reindex + tag ops ─────────────────────────────────────
    for idx in ("posts", "research", "questions", "users", "channels", "answers",
                "sounds"):
        sadmin("/api/v1/admin/search/%s/reindex" % idx, None,
               label="reindex %s" % idx)
    sadmin("/api/v1/admin/tags/backfill-posts", None, label="tag backfill")
    sadmin("/api/v1/admin/tags/giveaway/hide", None, label="hide tag")
    ok("search: 7 indices reindexed; tag backfill + one hidden tag")

    # ── 14. ops: run jobs so job_runs has history ────────────────────────
    jobs = API.get("/api/v1/admin/ops/jobs", A, quiet=True) or []
    ran = 0
    for j in (jobs if isinstance(jobs, list) else []):
        key = (j.get("job") or j.get("jobKey") or j.get("key")) if isinstance(j, dict) else None
        if not (j.get("triggerable") if isinstance(j, dict) else False):
            continue
        if not key:
            continue
        if sadmin("/api/v1/admin/ops/jobs/%s/run" % key, None,
                  label="run job %s" % key) is not None:
            ran += 1
    paused = None
    for j in (jobs if isinstance(jobs, list) else []):
        key = j.get("job") if isinstance(j, dict) else None
        if key and "cleanup" in key:
            if sadmin("/api/v1/admin/ops/jobs/%s/pause" % key, None,
                      label="pause job %s" % key) is not None:
                paused = key
            break
    ok("ops: %d scheduled jobs triggered on demand%s"
       % (ran, (" · %s paused" % paused) if paused else ""))

    # ── 15. user administration ──────────────────────────────────────────
    step_up("admin")
    API.patch("/api/v1/admin/users/%s/role" % W.id("u_hamza"), A,
              json_body=dict(role="RESEARCHER",
                             reason="Completed the researcher onboarding programme"),
              label="promote to researcher", quiet=True)
    sadmin("/api/v1/admin/users/%s/lock" % W.id("u_zayd"),
           dict(reason="Temporary lock during a safety review"), label="lock user")
    sadmin("/api/v1/admin/users/%s/unlock" % W.id("u_zayd"),
           dict(reason="Review closed"), label="unlock user")
    sadmin("/api/v1/admin/users/invite",
           dict(email="new.researcher@irc.test", role="RESEARCHER",
                note="Invited after the Doha conference"), label="invite user")
    sadmin("/api/v1/admin/users/%s/suggestions/recompute" % W.id("u_aisha"), None,
           label="recompute suggestions")
    ok("users: role change, lock/unlock, invite, suggestion recompute")


def hydrate_world():
    """Load what already exists so a partial (--only) run can build on top of
    it instead of acting on an empty World."""
    phase("HYDRATE — loading existing content for a partial run")

    def page(res):
        if isinstance(res, dict):
            return res.get("content") or []
        return res or []

    by_id = {str(W.id(k)): k for k in W.keys}

    # conversations
    seen = set()
    for k in W.keys:
        for path in ("/api/v1/conversations", "/api/v1/conversations/archived"):
            for c in page(API.get(path, W.tok(k), params=dict(size=100), quiet=True)):
                cid = c.get("id")
                if not cid or cid in seen:
                    continue
                seen.add(cid)
                if c.get("type") == "DIRECT":
                    peer = c.get("peerId") or c.get("otherUserId")
                    other = by_id.get(str(peer))
                    W.dms.append(dict(id=cid, a=k, b=other or k))
                elif c.get("type") == "GROUP":
                    W.groups.append(dict(id=cid, owner=k, members=[], title=c.get("title")))
                elif c.get("type") == "CHANNEL":
                    W.channels.append(dict(id=cid, owner=k, title=c.get("title"),
                                           handle=c.get("handle")))
    # sounds (reels reference them by id)
    for cat in ("NASHEED", "QURAN_RECITATION", "LECTURE_CLIP", "NATURE",
                "ORIGINAL", "PLATFORM_MUSIC"):
        for s in (API.get("/api/v1/sounds/by-category/%s" % cat, W.tok("admin"),
                          quiet=True) or []):
            sid = s.get("soundId") or s.get("id")
            if sid:
                W.sounds.append(dict(id=sid, title=s.get("title"), category=cat,
                                     status="APPROVED"))

    # posts
    for k in W.keys:
        for p in page(API.get("/api/v1/posts/by-author/%s" % W.id(k), W.tok(k),
                              params=dict(pageSize=50), quiet=True)):
            pid = p.get("id") or p.get("postId")
            if pid:
                W.posts.append(dict(id=pid, author=k, type=p.get("postType") or "TEXT"))
    # comments on the first handful of posts
    for p in W.posts[:12]:
        for c in page(API.get("/api/v1/posts/%s/comments" % p["id"], W.tok(p["author"]),
                              params=dict(pageSize=20), quiet=True)):
            cid = c.get("id") or c.get("commentId")
            author = by_id.get(str(c.get("authorId") or c.get("userId")))
            if cid and author:
                W.comments.append(dict(id=cid, postId=p["id"], author=author))
    # stories
    for k in W.keys:
        for s in (API.get("/api/v1/stories/by-author/%s" % W.id(k), W.tok(k),
                          quiet=True) or []):
            sid = s.get("storyId") or s.get("id")
            if sid:
                W.stories.append(dict(id=sid, author=k, type=s.get("storyType") or "IMAGE"))
    # research
    for r in page(API.get("/api/v1/researches/feed", W.tok("admin"),
                          params=dict(size=50), quiet=True)):
        rid = r.get("id")
        author = by_id.get(str(r.get("researcherId") or r.get("authorId")
                               or (r.get("researcher") or {}).get("id")))
        if rid:
            W.research.append(dict(id=rid, author=author or "sh_ahmad",
                                   title=r.get("title"), published=True, tags=[]))
    # questions + answers
    for q in page(API.get("/api/v1/questions", W.tok("admin"),
                          params=dict(size=50), quiet=True)):
        qid = q.get("id")
        author = by_id.get(str(q.get("authorId") or (q.get("author") or {}).get("id")))
        if not qid:
            continue
        W.questions.append(dict(id=qid, author=author or "sh_ahmad", title=q.get("title")))
        for a in page(API.get("/api/v1/questions/%s/answers" % qid, W.tok("admin"),
                              params=dict(size=20), quiet=True)):
            aid = a.get("id")
            aauthor = by_id.get(str(a.get("authorId") or (a.get("author") or {}).get("id")))
            if aid:
                W.answers.append(dict(id=aid, qid=qid, author=aauthor or "sh_ahmad"))
    # a few messages per conversation
    for c in (W.dms + W.groups + W.channels)[:12]:
        res = API.get("/api/v1/conversations/%s/messages" % c["id"],
                      W.tok(c.get("a") or c.get("owner")),
                      params=dict(limit=30), quiet=True)
        for m in page(res) or (res.get("items") if isinstance(res, dict) else []) or []:
            m_id = m.get("messageId") or m.get("id")
            author = by_id.get(str(m.get("senderId")))
            if m_id and author:
                W.messages.append(dict(id=m_id, conv=c["id"], author=author))

    ok("hydrated: %d posts, %d comments, %d stories, %d research, %d questions, "
       "%d answers, %d DMs, %d groups, %d channels, %d messages"
       % (len(W.posts), len(W.comments), len(W.stories), len(W.research),
          len(W.questions), len(W.answers), len(W.dms), len(W.groups),
          len(W.channels), len(W.messages)))


PHASES = [
    (0, "bootstrap", phase0_bootstrap),
    (1, "profiles", phase1_profiles),
    (2, "social", phase2_social),
    (3, "sounds", phase3_sounds),
    (4, "posts", phase4_posts),
    (5, "engagement", phase5_engagement),
    (6, "stories", phase6_stories),
    (7, "research", phase7_research),
    (8, "qna", phase8_qna),
    (9, "direct", phase9_direct),
    (10, "groups", phase10_groups),
    (11, "channels", phase11_channels),
    (12, "calls", phase12_calls),
    (13, "streams", phase13_streams),
    (14, "safety", phase14_safety),
    (15, "settings", phase15_settings),
    (16, "traffic", phase16_traffic),
    (17, "admin-ops", phase17_admin_ops),
]


def summary(started):
    phase("SEED SUMMARY")
    log("  users        %d" % len(W.users))
    log("  posts        %d   (comments %d)" % (len(W.posts), len(W.comments)))
    log("  stories      %d   (polls %d, highlights %d)"
        % (len(W.stories), len(W.polls), len(W.highlights)))
    log("  sounds       %d" % len(W.sounds))
    log("  research     %d" % len(W.research))
    log("  questions    %d   (answers %d)" % (len(W.questions), len(W.answers)))
    log("  chats        %d DMs, %d groups, %d channels"
        % (len(W.dms), len(W.groups), len(W.channels)))
    log("  messages     %d (tracked)" % len(W.messages))
    log("  calls        %d" % len(W.calls))
    log("  streams      %d" % len(W.streams))
    log("  reports      %d" % len(getattr(W, "reports", [])))
    log("")
    log("  HTTP         %d ok, %d failed" % (STATS["ok"], STATS["fail"]))
    if FAILS:
        by_code = Counter(code for _, code, _ in FAILS)
        log("  failures by status: " + ", ".join("%s×%d" % (c, n)
                                                 for c, n in by_code.most_common()))
        log("")
        log("  first 25 distinct failures:")
        seen = set()
        shown = 0
        for tag, code, msg in FAILS:
            key = (tag.split("#")[0], code)
            if key in seen:
                continue
            seen.add(key)
            log("    %-34s %s  %s" % (tag[:34], code, msg[:110]))
            shown += 1
            if shown >= 25:
                break
    log("")
    log("  elapsed      %.1fs" % (time.time() - started))
    log("")
    log("  Login for any seeded account:  <username>@irc.test / %s" % PASSWORD)
    log("  Admin account:                 yusuf_admin / %s" % PASSWORD)


def main():
    global VERBOSE, BASE_URL, API
    ap = argparse.ArgumentParser(description="Seed the IRC platform with full demo data")
    ap.add_argument("--base-url", default=BASE_URL)
    ap.add_argument("--only", default=None,
                    help="comma-separated phase numbers, e.g. 4,5,9")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    VERBOSE = args.verbose
    BASE_URL = args.base_url
    API = Api(BASE_URL)

    wanted = None
    if args.only:
        wanted = {int(x) for x in args.only.split(",") if x.strip()}
        wanted.add(0)  # bootstrap is always required (tokens + ids)

    started = time.time()
    log("\033[1m IRC PLATFORM — FULL DEMO SEEDER \033[0m")
    log(" target: %s" % BASE_URL)

    hydrated = False
    for num, name, fn in PHASES:
        if wanted is not None and num not in wanted:
            continue
        if wanted is not None and num > 0 and not hydrated:
            hydrated = True
            try:
                hydrate_world()
            except Exception as e:
                warn("hydrate failed: %r" % e)
        try:
            fn()
        except KeyboardInterrupt:
            raise
        except Exception as e:
            err("phase %d (%s) crashed: %r" % (num, name, e))
            import traceback
            traceback.print_exc()

    summary(started)


if __name__ == "__main__":
    main()

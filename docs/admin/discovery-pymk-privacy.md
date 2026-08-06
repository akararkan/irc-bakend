# Discovery, PYMK & Contact-Sync Privacy — Admin Dashboard Section 15

The platform's **people-discovery engine** and the **privacy/consent machinery**
that must govern it: the "People You May Know" (PYMK) suggestion pipeline, the
**contact-sync** system (hashed address-book matching), the **discoverability
flags** (findable by username/phone/email), and the **QR-code discovery** tokens.
This is a privacy-, consent-, and abuse-sensitive subsystem — it decides *who the
platform surfaces to whom* and matches people by their address books — yet today it
has **no admin oversight surface** and lives only as a cross-referenced slice of
[search-feed-trending.md](search-feed-trending.md). This is its canonical admin doc.

> [search-feed-trending.md](search-feed-trending.md) §2.4 keeps the PYMK **health
> widget** (source-mix, dismissal rate) as part of the discovery-engines console.
> **This document is the whole-subsystem reference** — the algorithm knob registry,
> contact-sync privacy, discovery/QR controls, and the abuse surface. Where they
> overlap (PYMK health), that section defers here.

Tag legend and ground rules: [README.md](README.md). Underlying mechanics:
`post/cassandra/service/FriendSuggestionService`, `user/service/ContactMatchService`,
`settings/discovery/*`, `settings/contacts/*`. Related:
[safety-reports.md](safety-reports.md) (consent evidence, blocking, abuse),
[users-roles.md](users-roles.md) (per-user discovery state),
[../suggestions/algorithm.md](../suggestions/algorithm.md) (the algorithm itself),
[../settings/README.md](../settings/README.md) (user-facing privacy controls).

Status legend: **[EXISTS]** = real today · **[PARTIAL]** = primitive exists, surface
missing · **[PLANNED]** = proposed here.

---

## 1. Purpose & scope

| In scope | Out of scope (see) |
|----------|--------------------|
| PYMK algorithm knob registry (the 6 sources, ~12 weight constants) | ES relevance search → [search-feed-trending.md](search-feed-trending.md) |
| PYMK health: source mix, dismissal rate, recompute ops | Home-feed ranking → [search-feed-trending.md](search-feed-trending.md) §2.3 |
| **Contact-sync privacy**: hashing, upload caps, identity-hash backfill, consent | The follow/block social graph → [users-roles.md](users-roles.md) |
| **Discoverability flags** (username/phone/email) + the QR-discovery tokens | Report triage / strikes → [safety-reports.md](safety-reports.md) |
| Discovery-driven abuse (scraping, contact-harvesting, enumeration) | Consent *storage* mechanics → [logs-audit.md](logs-audit.md) §3.5 |

**Starting reality:** every endpoint here is **user-scoped** (`isAuthenticated()` on
the current user) — there is **no admin controller** for discovery. So the admin
capabilities are **[PLANNED]** over **[EXISTS]** data/services. The value is that the
knobs and privacy-sensitive data already exist and are un-observed.

---

## 2. The PYMK engine today (ground truth)

`FriendSuggestionService` (~509 lines) computes suggestions from **six candidate
sources**, scores them, diversifies, and stores the top N per user in Cassandra
(`FriendSuggestionEntity`). Recompute is async (`recomputeFor`). **[EXISTS]**

| Source | Signal | Weight constant |
|--------|--------|-----------------|
| `CONTACTS` | matched from a synced address book | `W_CONTACT = 12.0` |
| `MESSAGING` | you've DM'd them | `W_DM = 10.0` |
| `AFFILIATION` | same institution | `W_INSTITUTION = 4.0` |
| `GRAPH` | mutual follows | `W_MUTUAL = 3.0` (capped at 15) |
| `GROUPS` | shared group membership | `W_GROUP = 2.5` |
| `INTERACTIONS` | engagement affinity (`UserAuthorAffinityRepository`) | (affinity-weighted) |

Gates: `MIN_SCORE = 2.0` (below this, dropped), store cap **50**, `DIVERSITY_HEAD = 20`
(no single source dominates the head of the list). **All of these are compile-time
`static final` constants** — there is **no runtime tuning surface**; changing a weight
is a recompile + redeploy.

Dismissals: `FriendSuggestionService.dismiss` writes `SuggestionDismissal`
(persistent) so a dismissed person doesn't reappear. **[EXISTS]**

---

## 3. Contact-sync — the privacy-critical path

`ContactMatchService` (`user/service/ContactMatchService`) matches a user's address
book to platform accounts **without ever receiving raw contacts**. **[EXISTS]**

| Mechanic | Detail | Admin/privacy consequence |
|----------|--------|---------------------------|
| Hash-only upload | client uploads **SHA-256 hashes** of phone/email, never plaintext | the server never sees the address book — state this prominently in the privacy view |
| Upload cap | `MAX_HASHES_PER_SYNC = 5000` | anti-harvesting ceiling; surface the cap + per-user sync counts |
| Bidirectional match | `bidirectionalMatchIds` — match only when both sides are discoverable | ties to the discovery flags (§4) |
| **Identity-hash backfill** | `ensureIdentityHash` / `backfillIdentityHashes` writes an IDENTITY hash of **every active user's email at startup**, so all accounts are matchable | ⚠️ **key privacy fact:** every user is discoverable-by-contact by default unless their discovery flags say otherwise. The admin must be able to see and explain this. |
| Rate limit | `contact:sync` **3 / 24h** (`RateLimiter`) | shown on the abuse tab; fail-open if Redis down (§6) |
| Data | `UserContactHash` (the stored hashes) | retention + right-to-erasure applies (§5) |

Two endpoints exist, both user-scoped:
`POST/DELETE /api/v1/users/contacts/sync` (`UserContactController`) and
`POST/DELETE /api/v1/contacts/sync` (`ContactsController`, the rate-limited one).

**Consent tie-in:** contact-sync is gated by the `CONTACTS` consent scope
(`ConsentEvent`, append-only) — see [logs-audit.md](logs-audit.md) §3.5 and
[safety-reports.md](safety-reports.md). The admin's contact-sync view must join
"has an active CONTACTS consent" against "has synced hashes" — a mismatch is a
compliance finding.

---

## 4. Discoverability flags & QR discovery

`DiscoverySettingsController` — per-user booleans: discoverable **byUsername**,
**byPhone**, **byEmail** (`/api/v1/discovery/...`). These gate whether contact-sync
and search can surface the account. **[EXISTS]** (user-scoped).

**QR discovery** — `QrDiscoveryController`: rotating **opaque** QR tokens with a
resolve endpoint `GET /api/v1/discovery/qr/resolve/{opaque}` — **not public**:
gated `@PreAuthorize("isAuthenticated()")`, so it requires a logged-in user. **[EXISTS]**

> ⚠️ **Recon flag (verified seam):** the QR *resolve* path does **not** currently
> honor the `discover.byQr` preference — i.e. a rotated/stale code may still resolve
> to the user regardless of their QR-discovery setting. This is an
> impersonation / stale-code vector. Surface it on the Config/health tab and file it
> against the discovery build; the fix is to check the preference (and token
> freshness) inside `resolve`.

---

## 5. Dashboard views / widgets

### 5.1 Tab "PYMK health" **[PLANNED]** (mirrors [search-feed-trending.md](search-feed-trending.md) §2.4)

| Widget | Content | Status |
|--------|---------|--------|
| **Knob registry** | read-only table of the 6 sources + ~12 weight constants + gates (`MIN_SCORE`, cap 50, `DIVERSITY_HEAD`), each tagged "recompile-only" | **[PLANNED]** — values are `static final`; a `GET /api/v1/admin/suggestions/knobs` reflects them |
| **Source mix** | stacked share of suggestions by source over time (is CONTACTS drowning GRAPH?) | **[PLANNED]** — needs a counter |
| **Dismissal rate** | dismissals / impressions — high = bad suggestions | **[PLANNED]** |
| **Recompute ops** | manual `recomputeFor(userId)` trigger + last-run age | **[PARTIAL]** — service method exists, no admin trigger |

### 5.2 Tab "Contact-sync & consent" **[PLANNED]**

| Widget | Content |
|--------|---------|
| **Sync volume** | syncs/day, avg hashes/sync, users near the 5000 cap, rate-limit rejections (`contact:sync`) |
| **Match rate** | % of uploaded hashes that matched an account (a spike can indicate enumeration) |
| **Consent join** | synced-hashes ∧ CONTACTS-consent — flags users with hashes but no active consent (compliance) |
| **Identity-backfill status** | last backfill run, coverage — the "everyone is matchable" fact made visible |

### 5.3 Tab "Discovery & QR" **[PLANNED]**

Per-user discovery-flag state (byUsername/byPhone/byEmail), QR-token status, and the
**QR-resolve seam banner** (§4). Population view: distribution of discovery settings.

---

## 6. Abuse surface

Discovery is the platform's main **enumeration / scraping** vector. Signals to surface
(read-only, feeding [safety-reports.md](safety-reports.md)):

| Signal | Pattern | Interpretation |
|--------|---------|----------------|
| Contact-harvesting | repeated near-5000-hash syncs, low match rate | building a shadow directory |
| Account enumeration | high volume of QR-resolve / discovery lookups | scraping who-is-on-the-platform |
| Rate-limit abuse | `contact:sync` rejections clustering on one actor | automated harvesting |

⚠️ **`RateLimiter` fails open** if Redis is down (`common/cache/RateLimiter`) — during
a Redis outage *all* discovery/sync limits are bypassed. This must be an alert
([operations.md](operations.md) §6), because the fail-open window is exactly when
harvesting is unthrottled.

---

## 7. Admin actions **[PLANNED]**

All under `/api/v1/admin/**` (double-gated). Step-up on anything that reveals
cross-user matching or mutates discovery state.

| # | Action | Endpoint | Danger | Step-up |
|---|--------|----------|--------|---------|
| D1 | PYMK knob registry (read) | `GET /api/v1/admin/suggestions/knobs` | read | no |
| D2 | Recompute a user's suggestions | `POST /api/v1/admin/users/{id}/suggestions/recompute` | low | no |
| D3 | Inspect a user's suggestions/dismissals | `GET /api/v1/admin/users/{id}/suggestions` | read (PII) | yes |
| D4 | Contact-sync stats | `GET /api/v1/admin/discovery/contact-sync/stats?window=` | read | no |
| D5 | Consent∧hash compliance report | `GET /api/v1/admin/discovery/contact-sync/compliance` | read (PII) | yes |
| D6 | Purge a user's contact hashes | `POST /api/v1/admin/users/{id}/contact-hashes/purge` | high (irreversible) | yes |
| D7 | Discovery-flag inspection | `GET /api/v1/admin/users/{id}/discovery` | read | no |
| D8 | Force QR-token rotation | `POST /api/v1/admin/users/{id}/qr/rotate` | medium | yes |

**Deliberate non-actions:** no admin editing of PYMK weights via API (they're
recompile-only — changing them at runtime would require a config framework the
platform doesn't have, [operations.md](operations.md) §7); no reverse "who has *me* in
their contacts" lookup (would weaponize the hash graph).

---

## 8. Logs, retention & permissions

- **Contact hashes** (`UserContactHash`) and **suggestion rows** (`FriendSuggestionEntity`)
  are **personal data** — they must be dropped in the account-purge cascade
  ([../settings/data-export-deletion.md](../settings/data-export-deletion.md)). Verify
  this is wired; if not, it's a GDPR gap. **[PLANNED verification]**
- **Consent events** are the legal evidence for contact-sync — append-only, never
  edited, surfaced read-only ([logs-audit.md](logs-audit.md) §3.5).
- **PII discipline:** D3/D5/D6/D8 touch cross-user matching and are step-up-gated and
  audited. Never expose one user's raw synced hashes to another; the whole design is
  hash-only for a reason.
- **The three recon flags to keep visible:** (1) identity-hash backfill makes everyone
  matchable-by-default; (2) QR-resolve ignores `discover.byQr`; (3) rate-limiter
  fails open on Redis loss. All three are "surface it, don't hide it" items.

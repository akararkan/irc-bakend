# Mentions API

Base path: **`/api/v1/mentions`**

Helpers for the compose box's `@`-mention experience, plus a reference for the
server-side mention pipeline that fires when mention-bearing text is actually saved.

Three endpoints:

| Endpoint | Job |
|---|---|
| `GET /mentions/suggest` | Ranked autocomplete candidates for a partial handle |
| `POST /mentions/click` | Record that the user picked a candidate from the picker |
| `POST /mentions/parse` | Extract `@username` / `@followers` tokens with offsets for highlighting |

Auth is Bearer JWT platform-wide; these endpoints tolerate anonymous callers (the
suggest endpoint just skips viewer-aware filtering and activity recording). Errors use
the standard envelope — see [../errors/error-handling.md](../errors/error-handling.md).

Siblings: [tags.md](./tags.md) · [search.md](./search.md) ·
[media-proxy.md](./media-proxy.md) · [activity.md](./activity.md) · [audit.md](./audit.md)

---

## 1. Mention autocomplete

```
GET /api/v1/mentions/suggest
```

**Auth:** Optional Bearer JWT. Works anonymously, but send the token when you have it —
the viewer id drives block filtering and self-exclusion.

Ranked candidate users for a partial handle typed after `@`. Backed by trigram +
prefix indexes on the `users` table. Results are **cached for ~30 seconds** keyed by
`(q, limit, viewerId)`, so a fast typer doesn't generate a per-keystroke query storm —
expect up to 30 s of staleness on brand-new accounts.

Filtering (applied server-side before the limit):

- **Block-aware** — any user in a block relationship with the viewer (either
  direction) is removed: you can't accidentally mention someone who blocked you, and
  blockers never see their target in the picker.
- Deleted accounts and locked profiles are excluded (a locked profile still matches
  for its own owner).
- The viewer themself is excluded.

When the caller is authenticated, the lookup is recorded as a `MENTION_LOOKUP`
activity row (see [activity.md](./activity.md)).

### Query parameters

| Name | Type | Required | Default | Notes |
|---|---|---|---|---|
| `q` | string | yes | — | Partial handle. A leading `@` is stripped; surrounding whitespace trimmed. Blank after normalization → `200 []`. |
| `limit` | int | no | `10` | Max suggestions. Clamped to `[1, 25]`. |

### Response `200`

```json
[
  {
    "id":        "9a2c0000-0000-4000-8000-000000000002",
    "username":  "akram",
    "fullName":  "Akram Hassan",
    "avatarUrl": "https://cdn.example.com/avatars/akram.jpg",
    "role":      "SCHOLAR"
  },
  {
    "id":        "41ee0000-0000-4000-8000-000000000004",
    "username":  "akifa",
    "fullName":  "Akifa Rahman",
    "avatarUrl": null,
    "role":      "USER"
  }
]
```

| Field | Type | Meaning |
|---|---|---|
| `id` | UUID | User id — pass back on `POST /mentions/click`. |
| `username` | string | Handle to insert into the text (`@username`). |
| `fullName` | string | Display name for the picker row. |
| `avatarUrl` | string | Profile image URL; may be `null`. |
| `role` | string | `USER` / `RESEARCHER` / `SCHOLAR` / `ADMIN` — render a badge if desired. |

Ordered by match score (descending), then shorter username first. Compact by design —
no follower counts or bios; hydrate the profile on demand.

### Errors

| Status | `errorCode` | When |
|---|---|---|
| 400 | `MISSING_PARAMETER` | `q` not supplied. |

---

## 2. Record a mention click

```
POST /api/v1/mentions/click
```

**Auth:** Optional Bearer JWT — but effectively required for the call to do anything
(anonymous calls are acknowledged with `recorded: false`).

Lock-in signal: call when the user actually **selects** a candidate from the picker.
Records a `MENTION_LOOKUP` activity row with the chosen target so the activity feed
can render "you mentioned @x" (a suggest call alone only knows the query and hit
count). This does **not** notify the target — notifications fire from the mention
pipeline (§4) when the composed text is actually saved.

### Request body

```json
{
  "query":        "akr",
  "targetUserId": "9a2c0000-0000-4000-8000-000000000002"
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `query` | string | no | What the user had typed when they clicked. |
| `targetUserId` | UUID | yes | The picked user's id (from the suggest response). |

### Response `200`

```json
{ "recorded": true }
```

| Field | Type | Meaning |
|---|---|---|
| `recorded` | boolean | `false` when unauthenticated, body missing, or `targetUserId` absent — never an error status. |

### Errors

None beyond the standard envelope (malformed JSON → `400`).

---

## 3. Parse mention tokens

```
POST /api/v1/mentions/parse
```

**Auth:** Public — no token required.

Extract every `@username` (and the special `@followers` token) from a body of text,
with start/end offsets, so a client can render a highlighted preview without
re-implementing the server's regex. Pure text analysis — no database lookups, no
notification side effects, and no guarantee the returned handles exist as users.

Extraction rules (must match server behaviour exactly, hence this endpoint):

- Handle characters: `[a-zA-Z0-9_.]`, length 2–50.
- Negative look-behind on a word char or `@`: `foo@bar.com` and `@@bob` do not match.
- Handles are normalized to **lowercase**; duplicates are de-duplicated in
  `usernames` (first-occurrence order) but every occurrence appears in `tokens`.
- `@followers` is matched case-insensitively as a reserved sentinel — reported via
  `followers` / `followersSentinel`, never listed in `usernames`.

### Request body

```json
{ "text": "Thanks @Akram and @akifa! cc @followers — email me at foo@bar.com" }
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `text` | string | yes | Text to scan. `null` body or `text` → the empty result, not an error. |

### Response `200`

```json
{
  "usernames": ["akram", "akifa"],
  "followers": true,
  "tokens": [
    { "handle": "akram",     "start": 7,  "end": 13, "followersSentinel": false },
    { "handle": "akifa",     "start": 18, "end": 24, "followersSentinel": false },
    { "handle": "followers", "start": 29, "end": 39, "followersSentinel": true }
  ]
}
```

| Field | Type | Meaning |
|---|---|---|
| `usernames` | string[] | De-duplicated, lowercased handles, in first-occurrence order. Never contains `followers`. |
| `followers` | boolean | `true` iff the literal `@followers` token appeared anywhere. |
| `tokens[].handle` | string | Lowercased handle (or literal `followers`). |
| `tokens[].start` | int | Inclusive offset of the `@` sign in the source text. |
| `tokens[].end` | int | Exclusive end offset. |
| `tokens[].followersSentinel` | boolean | `true` for the `@followers` token. |

### Errors

None beyond the standard envelope (malformed JSON → `400`).

---

## 4. The @mention pipeline (server-side reference)

Nothing in this section is a callable endpoint — it describes what happens
automatically when mention-bearing text is saved (post, comment, research, research
comment, question, answer). Useful for understanding what your users will trigger.

**1. Extraction.** The saved text runs through the same parser as `POST /parse`.

**2. Batched resolution — one query.** All extracted handles are resolved to user
rows in a **single** Postgres round-trip (`findActiveByUsernameIn`), no matter how
many handles the text carries. Unknown handles silently resolve to nothing.

**3. Filtering.** Self-mentions are dropped, and every user in a block relationship
with the author is removed via one batched block-table lookup.

**4. One event, fan-out on the consumer.** If anyone remains (or a permitted
`@followers` was used), a single `UserMentionedEvent` is published to RabbitMQ
*after* the database transaction commits. The consumer then:

- writes one **`USER_MENTIONED` notification** per direct recipient (batch insert)
  and pushes it over the notification SSE stream;
- writes a `USER_MENTIONED` **activity row** per direct recipient (the incoming half;
  the author's own `MENTION_LOOKUP` was recorded at compose time);
- honours per-user restriction settings (a restricted author's notification is
  silently swallowed).

**5. `@followers` fan-out — where allowed.** The `@followers` token notifies all of
the author's followers, paged in batches, de-duplicated against the direct-mention
recipients. It is honoured **only on top-level creates** (post, research, question) —
never on comments/answers and never on edits, to keep it from becoming a spam vector.
Follower fan-out deliberately does **not** write activity rows (it would flood every
follower's feed).

**6. Edits notify only the delta.** Re-saving edited text re-scans it, but only
handles that were **not** present in the previous body get notified — editing a post
doesn't re-ping everyone tagged the first time. `@followers` is never honoured on an
edit.

# Mentions API

Base path: **`/api/v1/mentions`**

The platform-wide `@`-mention system: compose-box helpers, the owner-scoped
"everywhere I was mentioned" feed, and a reference for the server-side pipeline
that fires when mention-bearing text is saved on **any** surface — posts,
comments, research (+ comments), questions, answers, chat messages and channel
posts.

Endpoints:

| Endpoint | Job |
|---|---|
| `GET /mentions/suggest` | Ranked autocomplete candidates for a partial handle |
| `POST /mentions/click` | Record that the user picked a candidate from the picker |
| `POST /mentions/parse` | Extract `@username` / `@followers` tokens with offsets for highlighting |
| `GET /mentions/me` | Unified mentions-of-me feed (hydrated, deep-linked, keyset-paged) |
| `GET /users/{userId}/mentions` | Legacy raw feed — **self-only** (403 for anyone else); prefer `/mentions/me` |

Auth is Bearer JWT platform-wide; suggest/click/parse tolerate anonymous callers
(suggest just skips viewer-aware filtering and activity recording), while
`/mentions/me` requires authentication. Errors use the standard envelope — see
[../errors/error-handling.md](../errors/error-handling.md).

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

## 4. Mentions-of-me feed

```
GET /api/v1/mentions/me
```

**Auth:** Bearer JWT required. Always scoped to the caller — there is no way to
read another user's mention feed.

Everywhere the caller was **directly** `@`-mentioned, newest first, across
posts, post comments/replies, research, research comments, questions and
answers. Backed by the Cassandra `mentions_by_user` table (one partition per
user — a point read at any scale). Two deliberate exclusions:

- **`@followers` fan-outs** are not rows here — only direct, by-name mentions.
- **Chat mentions** surface as [`MESSAGE_MENTION` notifications](#chat--channel-mentions)
  only, never in a browsable feed (messages can be deleted or disappearing).

### Query parameters

| Name | Type | Default | Notes |
|---|---|---|---|
| `limit` | int | `20` | Clamped to `[1, 50]`. |
| `cursor` | ISO instant | — | Keyset cursor: pass the last row's `mentionedAt` to get the next (strictly older) page. |

### Response `200`

```json
[
  {
    "sourceType":  "POST_COMMENT",
    "sourceId":    "7c0d…",
    "parentId":    "2b1a…",
    "snippet":     "pinging @you in a comment",
    "mentionedAt": "2026-07-30T20:41:03.512Z",
    "deepLink":    "/posts/2b1a…",
    "actor": {
      "id":        "b9a7…",
      "username":  "writer",
      "fullName":  "Writer Test",
      "avatarUrl": "https://cdn…/avatars/writer.jpg"
    }
  }
]
```

| Field | Type | Meaning |
|---|---|---|
| `sourceType` | enum | `POST` · `POST_COMMENT` · `RESEARCH` · `RESEARCH_COMMENT` · `QUESTION` · `QUESTION_ANSWER` (legacy rows with no stored type read as `POST`) |
| `sourceId` | UUID | The row that contains the mention (comment id, answer id, …) |
| `parentId` | UUID \| null | Navigable parent for nested sources (comment → its post, answer → its question) |
| `snippet` | string | Text preview captured at mention time |
| `mentionedAt` | instant | Feed ordering key — feed it back as `cursor` |
| `deepLink` | string \| null | Ready-to-navigate path: `/posts/{id}`, `/researches/{id}`, `/questions/{id}` (nested sources link to the parent) |
| `actor` | object \| null | The mentioning user, hydrated (avatar included) in one batched query |

Rows are removed when a post edit drops the mention from the text; removing a
mention elsewhere keeps the historical row (notification-time snapshot).

---

## 5. The @mention pipeline (server-side reference)

Nothing in this section is a callable endpoint — it describes what happens
automatically when mention-bearing text is saved. **One grammar everywhere:**
every surface parses with the same `MentionExtractor` rules documented in §3
(including the email-safe look-behind — `foo@bar.com` never pings a user named
`bar`).

### Surface matrix

| Surface | Fires on | Edit behaviour | `@followers` | Notification kind |
|---|---|---|---|---|
| Post | create | delta-only re-scan; removed handles drop their feed row | ✅ | `USER_MENTIONED` |
| Post comment / reply | create | delta-only | ❌ | `USER_MENTIONED` |
| Research | create/publish | delta-only | ✅ | `USER_MENTIONED` |
| Research comment | create | delta-only | ❌ | `USER_MENTIONED` |
| Question | create | delta-only | ✅ | `USER_MENTIONED` |
| Answer | create | delta-only | ❌ | `USER_MENTIONED` |
| Chat message (DM/group) | send | send-only (edits never ping) | ❌ | `MESSAGE_MENTION` |
| Channel post | send | send-only | ❌ | `MESSAGE_MENTION` |
| Story | — | — | — | no text body — nothing to scan |

### Steps

**1. Extraction.** The saved text runs through the same parser as `POST /parse`.

**2. Batched resolution — one query.** All extracted handles are resolved to user
rows in a **single** Postgres round-trip, no matter how many handles the text
carries. Unknown handles silently resolve to nothing.

**3. Filtering.** Self-mentions are dropped, and every user in a block relationship
with the author is removed via one batched block-table lookup — on every surface,
including the post fast-path (mirror rows are filtered the same way, so a blocked
user's feed never shows the post either).

**4. Delivery — the Cassandra notification pipeline.** Direct recipients get one
**`USER_MENTIONED`** notification each through the shared delivery engine (inbox
row + unread counter + SSE push + `MENTIONS`-gated email; see
[notifications](../notifications/notifications.md#notification-kinds)), one
**`mentions_by_user` feed row** (drives `GET /mentions/me`), and one
`USER_MENTIONED` **activity row** (the incoming half; the author's own
`MENTION_LOOKUP` was recorded at compose time). IG-style **restriction** silently
swallows the notification while the content stays visible. Posts deliver inline
(no broker hop); the other surfaces publish one `UserMentionedEvent` to RabbitMQ
after commit and a consumer fans out.

**5. `@followers` fan-out — where allowed.** The `@followers` token notifies all of
the author's followers, paged in batches, de-duplicated against the direct-mention
recipients. It is honoured **only on top-level creates** (post, research, question) —
never on comments/answers and never on edits, to keep it from becoming a spam vector.
Follower fan-out writes neither activity rows nor `mentions_by_user` rows (it would
flood every follower's feed).

**6. Edits notify only the delta.** Re-saving edited text re-scans it, but only
handles that were **not** present in the previous body get notified — editing a post
doesn't re-ping everyone tagged the first time. `@followers` is never honoured on an
edit. Post edits additionally reconcile the mentions-of-me feed: handles removed
from the text lose their row.

### Chat & channel mentions

Chat mentions are **higher-signal than the surrounding conversation** and get
their own kind, **`MESSAGE_MENTION`** (`MENTIONS` inbox tab, in-app only — a
chat-mention email would leak private message text):

- **Cuts through every chat gate.** The mentioned member is belled even when
  they muted the conversation/channel, even when online, and even in groups
  above the 256-member bell cutoff — a ping's whole purpose is to defeat those
  filters (Telegram semantics).
- **Exactly one bell per message per person.** Mentioned members are excluded
  from the generic `NEW_MESSAGE` / `CHANNEL_NEW_POST` row for that message.
- **Membership required.** Only members who can read the message qualify —
  `@someone` outside the conversation never notifies them.
- **`silent: true` wins.** A silent send/post produces no bells at all,
  mentions included.
- **Deep links:** `/chat/{conversationId}` for DMs/groups, `/channels/{channelId}`
  for channel posts.
- `@followers` has no meaning in chat and is ignored; edits never re-scan.

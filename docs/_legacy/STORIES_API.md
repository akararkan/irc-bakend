# Stories — Complete API Documentation

Full reference for the ephemeral-story subsystem: create, read, delete,
visibility rules, the new author-selectable **lifetime** (8h / 16h / 24h),
attached two-option polls, story views, and the realtime SSE streams.

---

## Table of contents

1. [Overview & architecture](#1-overview--architecture)
2. [Auth & common headers](#2-auth--common-headers)
3. [Lifetime & auto-deletion (8h / 16h / 24h)](#3-lifetime--auto-deletion-8h--16h--24h)
4. [Visibility model](#4-visibility-model)
5. [Create story (JSON)](#5-create-story-json)
6. [Create story (multipart)](#6-create-story-multipart)
7. [Read endpoints](#7-read-endpoints)
8. [Delete a story](#8-delete-a-story)
9. [Story views](#9-story-views)
10. [Polls attached to a story](#10-polls-attached-to-a-story)
11. [Realtime SSE — tray & per-story](#11-realtime-sse--tray--per-story)
12. [Cassandra tables index](#12-cassandra-tables-index)
13. [Schema migration notes](#13-schema-migration-notes)

---

## 1. Overview & architecture

Stories are short-lived, author-published media (TEXT / IMAGE / VIDEO /
LINKED_POST / LINKED_REEL / LINKED_QNA / LINKED_RESEARCH / KNOWLEDGE_PILL).
Each story auto-deletes after a window the author picks at create time —
**8h**, **16h**, or **24h** (default). Auto-deletion is implemented with
**per-row Cassandra TTL** (`INSERT … USING TTL <seconds>`), so the cluster
tombstones the rows on its own — no scheduled cleanup job, no read-time
expiry filter, no orphans.

**Tables involved per story** (every row inherits the same per-row TTL):

| Table | Keying | Purpose |
|---|---|---|
| `stories_by_author` | `(author_id, created_at DESC, story_id)` | Tray feed — "what stories does U have right now?" |
| `story_lookup` | `story_id` (point read) | Resolve story → author + visibility + `expires_at` |
| `story_views_by_story` | `(story_id, viewed_at DESC, viewer_id)` | "Who saw my story?" list — TTL'd to remaining parent lifetime |
| `poll_by_story` | `story_id` | The poll attached to the story (if any) |
| `poll_by_id` | `poll_id` | Reverse index `poll_id → (story_id, author_id, expires_at)` for vote + realtime push |
| `poll_votes_by_poll_user` | `(poll_id, voter_id)` | "Did U vote? Which side?" |
| `poll_voters_by_choice` | `(poll_id, choice, voted_at DESC, voter_id)` | Author-only voter list per side |
| `poll_counters` | `poll_id` | Live A/B tally (Cassandra COUNTER) — see [Counter TTL caveat](#counter-ttl-caveat) |

---

## 2. Auth & common headers

| Header | Value | Required for |
|---|---|---|
| `Authorization` | `Bearer <accessToken>` | Create, delete, votes, "my views", tray SSE |
| — | — | Read PUBLIC stories — anonymous viewers allowed |

The author is **always derived from the JWT** on writes. Any `authorId` in
the request body is ignored.

Rate-limited on the write paths (`RateLimiter.checkSocial` — 30 writes per
minute per user; fail-open if Redis is unreachable).

---

## 3. Lifetime & auto-deletion (8h / 16h / 24h)

The author picks how long their story stays visible. The value is sent on
create as `lifetimeHours`.

| Sent | Story lives | Notes |
|---|---|---|
| `8` | 8h | Same TTL applied to lookup, views, poll, votes |
| `16` | 16h | Same |
| `24` | 24h | Same (default) |
| `null` / omitted | 24h | Default — legacy clients keep current behaviour |
| `12`, `0`, `-5`, etc. | 24h | Silent fallback — server does **not** return 400 |

### How it works under the hood

Each story's chosen window is written into the row's `expires_at` column
**and** applied as the Cassandra per-row TTL via
`InsertOptions.builder().ttl(seconds).build()`. The lookup, poll, and
vote rows mirror the same `expires_at` so derived writes (record-view,
cast-vote) can compute their own remaining TTL with one point-read:

```
ttl(view  )  =  story_lookup.expires_at − now()
ttl(vote  )  =  poll_by_id  .expires_at − now()
ttl(counter) =  table default (24h)   ← Cassandra COUNTER limitation
```

#### Counter TTL caveat

Cassandra `COUNTER` columns **cannot** carry a TTL on `UPDATE`. The
`poll_counters` row therefore keeps the table-default TTL (24h). For an
8h story this leaves the counter row orphaned for up to 16h — but it's
unreachable (`poll_by_id` is already tombstoned), so it doesn't appear
in any user-facing read.

### Manual delete still works

The author can hard-delete before the TTL fires (`DELETE /api/v1/stories/{id}`).
Manual delete wipes every derived row immediately and fires
`STORY_REMOVED` on the tray channel so viewers' rings grey out without a
refresh.

---

## 4. Visibility model

| Value | Who can view |
|---|---|
| `PUBLIC` | Everyone, including anonymous |
| `FOLLOWERS_ONLY` | Authenticated viewers who follow the author |
| `CLOSE_FRIENDS` | Authenticated viewers on the author's close-friends list |
| `ONLY_ME` | Only the author |

The author always sees their own story regardless of visibility (self-fanout).
Resolution lives in `CassandraStoryService.canView(...)`. Failed follow
lookups silently resolve to "not visible" — the read path never throws on
a transient Postgres error.

---

## 5. Create story (JSON)

```
POST /api/v1/stories
Content-Type: application/json
Authorization: Bearer <token>
```

### Request body

```json
{
  "storyType":     "IMAGE",
  "visibility":    "PUBLIC",
  "mediaUrl":      "https://cdn.../media.jpg",
  "thumbnailUrl":  "https://cdn.../thumb.jpg",
  "textContent":   "optional caption",
  "lifetimeHours": 8
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `storyType` | enum | yes | One of `StoryType` values |
| `visibility` | enum | yes | See [§4](#4-visibility-model) |
| `mediaUrl` | string | conditional | Required for IMAGE / VIDEO / LINKED_* |
| `thumbnailUrl` | string | optional | Used by VIDEO and LINKED_* |
| `textContent` | string | optional | TEXT / overlay caption |
| `lifetimeHours` | int | optional | 8, 16, or 24. Anything else → 24 |

### Response — `200 OK`

```json
{
  "authorId":     "…",
  "storyId":      "…",
  "createdAt":    "2026-06-04T08:00:00Z",
  "expiresAt":    "2026-06-04T16:00:00Z",
  "storyType":    "IMAGE",
  "visibility":   "PUBLIC",
  "mediaUrl":     "…",
  "thumbnailUrl": "…",
  "textContent":  "…"
}
```

---

## 6. Create story (multipart)

For direct photo / video upload. Files are streamed to R2; the returned
public URLs are written into the story row.

```
POST /api/v1/stories
Content-Type: multipart/form-data
Authorization: Bearer <token>
```

### Form fields

| Name | Type | Required | Notes |
|---|---|---|---|
| `storyType` | string | optional | Defaults to `PHOTO` |
| `visibility` | string | optional | Defaults to `PUBLIC` |
| `textContent` | string | optional | |
| `lifetimeHours` | int | optional | 8 / 16 / 24 — invalid → 24 |
| `media` | file | optional | The story body (image / video) |
| `thumbnail` | file | optional | Video thumb |

Rate-limit check fires **before** R2 upload — banned users don't burn
storage bandwidth.

---

## 7. Read endpoints

### Active stories for one author

```
GET /api/v1/stories/by-author/{authorId}
```

Visibility-filtered for the JWT subject (or PUBLIC-only for anon).
Cassandra TTL means expired rows are simply not present — no `WHERE
expires_at >` filter required.

### Single story

```
GET /api/v1/stories/{storyId}
```

Returns the story or `404` if expired / deleted / not visible.

---

## 8. Delete a story

```
DELETE /api/v1/stories/{storyId}
Authorization: Bearer <token>
```

**Author-only.** Wipes (in order, best-effort after the first step):

1. `stories_by_author` + `story_lookup` (canonical rows)
2. Attached poll (all 5 poll tables) via `CassandraStoryPollService.deletePollFor`
3. `STORY_REMOVED` fanned out on the tray channel to every viewer whose
   ring was lit (visibility-aware — PUBLIC / FOLLOWERS_ONLY scan up to
   50k followers; CLOSE_FRIENDS scans the close-friends list; ONLY_ME
   notifies the author only)

Story views are intentionally left to TTL — no app-side cleanup latency
on the response.

---

## 9. Story views

Recording a view:

```
POST /api/v1/stories/{storyId}/views
Authorization: Bearer <token>
```

- Visibility-enforced — viewer must be allowed to see the story.
- Self-views are silently ignored (the author doesn't show up in their own viewer list).
- The view row is TTL'd to the **parent story's remaining lifetime**, so an
  8h story's view log dies with the story.

Author-only viewer list:

```
GET /api/v1/stories/{storyId}/viewers?pageSize=20
```

Returns newest viewers first (clustered DESC).

---

## 10. Polls attached to a story

A story can carry one two-option poll. The poll inherits the parent story's
TTL — when the story expires, the poll question, vote rows, and reverse
index disappear with it. (Counter rows are an exception — see
[Counter TTL caveat](#counter-ttl-caveat).)

### Create a poll

```
POST /api/v1/stories/{storyId}/poll
Authorization: Bearer <token>
{
  "question": "Best research method?",
  "optionA":  "Quantitative",
  "optionB":  "Qualitative"
}
```

Only the story author can attach a poll. Returns the `StoryPollEntity` with
the new `pollId`.

### Cast a vote

```
POST /api/v1/polls/{pollId}/vote
Authorization: Bearer <token>
{ "choice": "A" }
```

`choice` must be `"A"` or `"B"`. One vote per user per poll. Re-submitting
the same choice is idempotent; switching sides moves the row and adjusts the
counter atomically. The author receives a `POLL_VOTE_CAST` event on the
story-tray channel for live UI updates.

Response:

```json
{ "choice": "A", "voteA": 17, "voteB": 9 }
```

### Read results / voter list

```
GET /api/v1/polls/{pollId}/results
GET /api/v1/polls/{pollId}/me              ← my vote
GET /api/v1/polls/{pollId}/voters/{choice} ← author-only
```

---

## 11. Realtime SSE — tray & per-story

Two SSE streams:

### A) Story tray — per viewer

```
GET /api/v1/stories/tray/stream?token=<jwt>
```

Pushes events when stories from accounts the viewer follows are
created, deleted, or get a poll vote:

| Event | Payload |
|---|---|
| `connected` | `{viewerId}` |
| `new_story` | `{authorId, storyId, expiresAt, ...}` |
| `story_removed` | `{authorId, storyId}` |
| `poll_vote_cast` | `{storyId, pollId, voteA, voteB, voteTotal}` |
| `heartbeat` | `ping` (every 25s) |

Event names are **lowercase enum values** (snake_case-ish), not the
uppercase enum names.

Per-user connection cap: **5 simultaneous tabs** — beyond that the oldest
emitter is closed and removed (LRU eviction).

### B) Per-story stream

```
GET /api/v1/stories/{storyId}/stream
```

Mirrors the per-post stream for in-story reactions / poll updates / view
counter changes.

Both streams share a single `@Scheduled` heartbeat tick across all
emitters (consolidated to avoid one scheduler thread per connection).

---

## 12. Cassandra tables index

| Table | Owned by | Per-row TTL? | Notes |
|---|---|---|---|
| `stories_by_author` | `CassandraStoryService.createStory` | ✅ chosen lifetime | Tray scan |
| `story_lookup` | same | ✅ chosen lifetime | Carries `expires_at` mirror |
| `story_views_by_story` | `recordView` | ✅ remaining lifetime | Newest first |
| `poll_by_story` | `CassandraStoryPollService.createPoll` | ✅ remaining lifetime | One poll per story |
| `poll_by_id` | same | ✅ remaining lifetime | Carries `expires_at` mirror for `castVote` |
| `poll_votes_by_poll_user` | `castVote` | ✅ remaining lifetime | One row per voter |
| `poll_voters_by_choice` | `castVote` | ✅ remaining lifetime | Author's voter list |
| `poll_counters` | `counterService` | ❌ table default 24h | COUNTER tables can't carry per-write TTL |

---

## 13. Schema migration notes

Two columns added to support per-row TTL on derived writes. Run these
once per Cassandra keyspace before deploying:

```cql
ALTER TABLE story_lookup ADD expires_at timestamp;
ALTER TABLE poll_by_id   ADD expires_at timestamp;
```

The new columns are **null-safe**: rows written before the migration
still read fine. Code that derives a TTL from a null `expires_at`
falls back to 24h (the `StoryLifetime.DEFAULT` value) — so old rows
silently keep the previous behaviour while new rows pick up the
correct per-row TTL.

No data backfill is required.

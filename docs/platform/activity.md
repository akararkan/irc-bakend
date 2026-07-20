# Activity API

Base path: **`/api/v1/users/me/activity`** (activity history) · reel watch history under
**`/api/v1/posts/{postId}/reels/view`** and **`/api/v1/users/me/reels/watched`**

Every meaningful action a user takes — posting, reacting, commenting, saving,
searching, mention lookups, profile views, follows, Q&A and research actions, reel
watches — is recorded to the user's private, Cassandra-backed activity history. The
history is per-user: you can only ever read or delete **your own** rows.

Each write lands in three tables (all-activity feed, per-type feed, and a point-lookup
table for deletes) and is simultaneously broadcast on the user's private SSE channel
(§4), fanned out across app instances via Redis pub/sub.

All endpoints in this file require a Bearer JWT (`Authorization: Bearer <accessToken>`);
requests without a principal get `401`. Errors use the standard envelope — see
[../errors/error-handling.md](../errors/error-handling.md).

Siblings: [tags.md](./tags.md) · [search.md](./search.md) · [mentions.md](./mentions.md) ·
[media-proxy.md](./media-proxy.md) · [audit.md](./audit.md)

### Activity types

`UserActivityType` values (used in the `type`/`types` filters and as SSE event names):

| Group | Types |
|---|---|
| Posts | `POST_CREATED`, `POST_REACTION`, `POST_COMMENT`, `POST_COMMENT_REACTION`, `POST_SHARE`, `POST_SAVED`, `REEL_WATCH` |
| Discovery | `GLOBAL_SEARCH`, `HASHTAG_SEARCH`, `MENTION_LOOKUP` (outgoing), `USER_MENTIONED` (incoming), `PROFILE_VIEW`, `FOLLOWED_USER` |
| Q&A | `QNA_QUESTION_CREATED`, `QNA_QUESTION_SAVED`, `QNA_ANSWER_CREATED`, `QNA_REANSWER_CREATED`, `QNA_ANSWER_REACTION`, `QNA_ANSWER_FEEDBACK` |
| Research | `RESEARCH_PUBLISHED`, `RESEARCH_SAVED`, `RESEARCH_REACTION`, `RESEARCH_COMMENT`, `RESEARCH_COMMENT_REACTION` |
| Stories | `STORY_VIEWED`, `STORY_REACTED`, `STORY_REPLIED`, `STORY_POLL_VOTED` |
| Sounds | `SOUND_USED` |

---

## 1. List my activity

```
GET /api/v1/users/me/activity
```

**Auth:** Bearer JWT required.

Paginated activity history, newest first, with optional type and date-range filters.

### Query parameters

| Name | Type | Required | Default | Notes |
|---|---|---|---|---|
| `type` | enum | no | — | Single activity type (see list above). Legacy — prefer `types`. Ignored when `types` is present. |
| `types` | CSV / repeatable | no | — | Multiple types, unioned (OR). E.g. `types=POST_REACTION,POST_COMMENT`. Under the hood each type is a separate partition scan, k-way merged by `createdAt` descending and trimmed to `size`. |
| `from` | ISO-8601 instant | no | — | Inclusive lower bound on `createdAt`, e.g. `2026-05-01T00:00:00Z`. |
| `to` | ISO-8601 instant | no | — | Inclusive upper bound. |
| `page` | int | no | `0` | **Currently ignored — see the pagination caveat below.** |
| `size` | int | no | `20` | Rows in the returned window. |

> **Pagination caveat (documented honestly).** The storage is cursor-paged Cassandra
> behind a legacy Spring `Page` signature. The `page` number is **not applied** — any
> `page` value returns the same first window of `size` rows, and the response's
> `totalElements` reflects **only the returned window**, not the full history (Cassandra
> has no cheap partition count). Practical upshot: treat this endpoint as "give me the
> newest N rows (optionally filtered)". To walk further back, pass the `createdAt` of
> the last row you received as the `to` bound of the next request (it is inclusive, so
> drop the duplicate first row), rather than incrementing `page`.

### Response `200`

A Spring `Page` envelope. Trimmed example:

```json
{
  "content": [
    {
      "id":           "7f1e0000-0000-4000-8000-00000000000a",
      "activityType": "POST_REACTION",
      "reactionType": "LIKE",
      "post": {
        "id":           "1b2c0000-0000-4000-8000-000000000001",
        "postType":     "STANDARD",
        "textPreview":  "Reflections on the last ten nights…",
        "thumbnailUrl": "https://…/thumb.jpg",
        "author": { "id": "9a2c…", "username": "akram", "fullName": "Akram Hassan", "avatarUrl": "https://…" }
      },
      "createdAt":     "2026-07-19T21:14:03",
      "timeAgo":       "14 hours ago",
      "formattedDate": "Jul 19, 2026",
      "label":         "Liked a post",
      "subtitle":      "You liked Akram Hassan's post"
    },
    {
      "id":           "7f1e0000-0000-4000-8000-00000000000b",
      "activityType": "GLOBAL_SEARCH",
      "query":        "zakat on stocks",
      "searchScope":  "QUESTION,RESEARCH",
      "hitCount":     14,
      "createdAt":    "2026-07-19T20:02:41",
      "timeAgo":      "15 hours ago",
      "formattedDate":"Jul 19, 2026",
      "label":        "Searched",
      "subtitle":     "You searched for \"zakat on stocks\""
    }
  ],
  "totalElements": 2,
  "totalPages": 1,
  "size": 20,
  "number": 0,
  "first": true,
  "last": true,
  "numberOfElements": 2,
  "empty": false
}
```

`UserActivityResponse` fields (null fields present per type; each row populates only
the references relevant to its `activityType`):

| Field | Type | Populated for | Meaning |
|---|---|---|---|
| `id` | UUID | all | Activity row id — pass to `DELETE /{activityId}`. |
| `activityType` | enum | all | See type list above. |
| `label` | string | all | Server-rendered human label ("Saved a post", "Liked a research paper") — render directly, no enum map needed. |
| `subtitle` | string | all | Longer copy for a card subtitle. |
| `createdAt` / `timeAgo` / `formattedDate` | date-time / string / string | all | Timestamp plus pre-rendered relative and absolute forms. |
| `reactionType` | string | `POST_REACTION`, `POST_COMMENT_REACTION` (also reused to carry the post type on `POST_CREATED`) | Reaction used — always `LIKE` on this platform. |
| `watchedSeconds` | int | `REEL_WATCH` | Seconds watched. |
| `post` | object | `POST_*`, `REEL_WATCH`, `USER_MENTIONED` (post sources) | `{id, postType, textPreview, thumbnailUrl, author{id, username, fullName, avatarUrl}}`. |
| `comment` | object | `POST_COMMENT`, `POST_COMMENT_REACTION` | `{id, textPreview}`. |
| `query` | string | `GLOBAL_SEARCH`, `HASHTAG_SEARCH`, `MENTION_LOOKUP`; also carries the source-type label on `USER_MENTIONED` | The search / lookup text. |
| `searchScope` | string | `GLOBAL_SEARCH` | Comma-separated searched types; `null` means "all". |
| `hitCount` | int | search / lookup types | Hits returned at the time. |
| `targetUser` | object | `MENTION_LOOKUP` (clicked user), `PROFILE_VIEW`, `FOLLOWED_USER`, `USER_MENTIONED` (the mentioner) | `{id, username, fullName, avatarUrl}`. |
| `question` / `answer` | object | `QNA_*` | `{id, title, author}` / `{id, parentAnswerId, bodyPreview, accepted, author}`. |
| `qnaReactionType` | string | `QNA_ANSWER_REACTION` | Reaction name (string form). |
| `research` | object | `RESEARCH_*`, `USER_MENTIONED` (research sources) | `{id, ircId, title, coverImageUrl, author}`. |
| `researchComment` | object | `RESEARCH_COMMENT`, `RESEARCH_COMMENT_REACTION` | `{id, textPreview}`. |

### Errors

| Status | `errorCode` | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid Bearer token. |
| 400 | `VALIDATION_FAILED` | Unparseable `type`/`types` value or malformed `from`/`to` instant. |
| 503 | `DATASTORE_UNAVAILABLE` | Cassandra temporarily unreachable. Retryable. |

---

## 2. Delete one activity row

```
DELETE /api/v1/users/me/activity/{activityId}
```

**Auth:** Bearer JWT required. Only the row's owner may delete it.

Removes a single activity row from all three underlying tables.

### Path parameters

| Name | Type | Notes |
|---|---|---|
| `activityId` | UUID | The `id` from a list response. |

### Response `204 No Content`

Empty body.

### Errors

| Status | `errorCode` | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid Bearer token. |
| 403 | `ACCESS_FORBIDDEN` | The row belongs to another user. |
| 404 | `RESOURCE_NOT_FOUND` | No activity with that id. |

---

## 3. Bulk clear activity

```
DELETE /api/v1/users/me/activity
```

**Auth:** Bearer JWT required.

Clears the caller's activity history — everything, or a single type.

> **Changed:** type-filtered clears now drive off the **per-type partition** and sweep
> it fully (in batches of 200, up to 50 batches — **~10,000 rows per call**).
> Previously the filter was applied in memory to the newest 200 rows of the all-types
> feed, which both missed matches deeper in history and made no forward progress on
> repeat calls. If a partition holds more than ~10k rows, call again — `deleted` less
> than 10,000 means the sweep completed.

The untyped (clear-everything) variant is capped at the same ~10k rows per call.

### Query parameters

| Name | Type | Required | Default | Notes |
|---|---|---|---|---|
| `type` | enum | no | — | Restrict the clear to one activity type (e.g. `?type=GLOBAL_SEARCH` to wipe search history only). Omitted = clear everything. |

### Response `200`

```json
{ "deleted": 137 }
```

| Field | Type | Meaning |
|---|---|---|
| `deleted` | int | Rows removed in this call. `10000` means the cap was hit — call again to continue. |

### Errors

| Status | `errorCode` | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid Bearer token. |
| 400 | `VALIDATION_FAILED` | Unknown `type` value. |
| 503 | `DATASTORE_UNAVAILABLE` | Cassandra temporarily unreachable. |

---

## 4. Live activity stream (SSE)

```
GET /api/v1/users/me/activity/stream
```

**Auth:** Bearer JWT principal **or** `?token=<accessToken>` query parameter. Browser
`EventSource` cannot send an `Authorization` header, so the ACCESS token may be passed
as a query param — same pattern as the notification stream. A refresh token is
rejected.

Server-Sent Events stream of the caller's own activity: every recorded action is
pushed the moment its row is committed, across all app instances (Redis pub/sub
fan-out). Multiple tabs/devices may subscribe at once; each receives every event.

**Event names** are the `UserActivityType` enum names verbatim (`POST_REACTION`,
`GLOBAL_SEARCH`, `REEL_WATCH`, …) plus two infrastructure events:

| Event | Payload | Notes |
|---|---|---|
| `connected` | `{ "userId": "…", "timestamp": "…", "subscribers": 2 }` | Sent once on subscribe. |
| `heartbeat` | `{ "timestamp": "…" }` | Every **25 s**; keeps proxies from idling out the connection. |
| *(each `UserActivityType` name)* | `UserActivityRealtimeEvent` (below) | One event per recorded activity; dispatch on the event name. |

Activity event payload (`null` fields omitted):

```json
{
  "activityId":   "7f1e0000-0000-4000-8000-00000000000a",
  "userId":       "9a2c0000-0000-4000-8000-000000000002",
  "activityType": "POST_REACTION",
  "postId":       "1b2c0000-0000-4000-8000-000000000001",
  "reactionType": "LIKE",
  "timestamp":    "2026-07-20T09:14:03"
}
```

| Field | Type | Meaning |
|---|---|---|
| `activityId` / `userId` / `activityType` | UUID / UUID / enum | Row identity. |
| `postId`, `commentId`, `targetUserId` | UUID | Populated per type (as in §1). |
| `query`, `searchScope`, `hitCount` | string / string / int | Search-type events. |
| `reactionType`, `watchedSeconds` | string / int | Reaction / reel-watch events. |
| `timestamp` | date-time | Row creation time (UTC). |
| `activity` | object | Optional compact `UserActivityResponse` mirror for richer rendering, when available. |

Response headers disable proxy buffering (`X-Accel-Buffering: no`,
`Cache-Control: no-cache`), so events arrive immediately.

### Errors

| Status | Body | When |
|---|---|---|
| 401 | `text/plain` — `Authentication required. Pass access token as ?token=<jwt>.` | No principal and no valid `?token=`. Written directly (not the JSON envelope) because the response is already negotiated as `text/event-stream`. |

---

## 5. Reel watch history

Reel watches get a dedicated history (with `watchedSeconds`) alongside the generic
`REEL_WATCH` activity rows. Recording a watch also bumps the reel's unique-viewer
counter (idempotent per `(post, user)` via Redis NX dedupe) — so client retries do not
inflate view counts.

### 5.1 Record a reel watch

```
POST /api/v1/posts/{postId}/reels/view
```

**Auth:** Bearer JWT required.

#### Path & body

| Name | In | Type | Required | Notes |
|---|---|---|---|---|
| `postId` | path | UUID | yes | The reel's post id. |
| `watchedSeconds` | body | int | no | Seconds watched; must be `>= 0`. The whole body is optional. |

```json
{ "watchedSeconds": 17 }
```

#### Response `201 Created`

```json
{
  "id":             "5c3d0000-0000-4000-8000-00000000000f",
  "watchedSeconds": 17,
  "reel": {
    "id":              "1b2c0000-0000-4000-8000-000000000001",
    "textPreview":     "Sunset over the Bosphorus…",
    "thumbnailUrl":    "https://…/thumb.jpg",
    "mediaUrl":        "https://…/reel.mp4",
    "durationSeconds": 42,
    "author": { "id": "9a2c…", "username": "akram", "fullName": "Akram Hassan", "avatarUrl": "https://…" }
  },
  "watchedAt":     "2026-07-20T09:14:03",
  "timeAgo":       "just now",
  "formattedDate": "Jul 20, 2026"
}
```

| Field | Type | Meaning |
|---|---|---|
| `id` | UUID | Watch-entry id — pass to the single-delete endpoint. |
| `watchedSeconds` | int | Echo of the recorded value; may be `null`. |
| `reel` | object | Reel summary: `{id, textPreview, thumbnailUrl, mediaUrl, durationSeconds, author{…}}`. |
| `watchedAt` / `timeAgo` / `formattedDate` | date-time / string / string | When the watch was recorded, plus pre-rendered forms. |

#### Errors

| Status | `errorCode` | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid Bearer token. |
| 400 | `VALIDATION_FAILED` | `watchedSeconds < 0`. |

### 5.2 List my watched reels

```
GET /api/v1/users/me/reels/watched
```

**Auth:** Bearer JWT required.

Newest-first watch history. Same `Page` envelope and the **same pagination caveat**
as §1: the `page` number is ignored (always the newest window of `size` rows,
default 20) and `totalElements` counts only the returned window.

#### Response `200`

```json
{
  "content": [ { "id": "5c3d…", "watchedSeconds": 17, "reel": { "…": "…" }, "watchedAt": "2026-07-20T09:14:03", "timeAgo": "just now", "formattedDate": "Jul 20, 2026" } ],
  "totalElements": 1, "totalPages": 1, "size": 20, "number": 0, "first": true, "last": true, "numberOfElements": 1, "empty": false
}
```

#### Errors

| Status | `errorCode` | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid Bearer token. |

### 5.3 Delete one watch entry

```
DELETE /api/v1/users/me/reels/watched/{reelViewId}
```

**Auth:** Bearer JWT required.

> **Honest limitation:** there is no lookup table for reel views — the server scans
> the caller's **most recent 500** entries for a matching id. An older entry is
> silently missed: the response is still `204`, but nothing is deleted. Use the bulk
> clear (§5.4) to reach deeper history.

#### Response `204 No Content`

Empty body — returned whether or not a matching entry was found.

#### Errors

| Status | `errorCode` | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid Bearer token. |

### 5.4 Clear all watch history

```
DELETE /api/v1/users/me/reels/watched
```

**Auth:** Bearer JWT required.

Deletes the caller's entire reel watch history, looping in batches of 200 until the
partition is empty (no row cap). Does not touch the reels' view counters.

#### Response `200`

```json
{ "deleted": 342 }
```

#### Errors

| Status | `errorCode` | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid Bearer token. |

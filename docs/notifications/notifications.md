# Notifications REST API

Base path: **`/api/v1/notifications`**

The notification inbox is **Cassandra-native**: rows live in `notifications_by_user`
(partitioned per recipient, newest-first), the unread badge is a dedicated Cassandra
counter, and every state change is mirrored to all of the user's open tabs over
SSE (see [realtime.md](./realtime.md)). Email delivery and its preference toggles
are documented in [email-preferences.md](./email-preferences.md).

- **Auth:** every endpoint requires `Authorization: Bearer <accessToken>` (JWT).
  The SSE stream additionally accepts `?token=` — see [realtime.md](./realtime.md).
- **Errors:** all failures use the shared error envelope (`status`, `error`,
  `message`, `path`, `errorCode`, `details`, `traceId`) — see
  [../errors/error-handling.md](../errors/error-handling.md).

Source of truth: `NotificationController`, `NotificationServiceImpl`,
`CassandraNotificationService`, `NotificationKind`, `NotificationType`,
`NotificationCategory`.

---

## Contents

1. [Core concepts](#core-concepts)
   - [Aggregation (coalescing)](#aggregation-coalescing)
   - [The bounded scan window (read this before paging)](#the-bounded-scan-window)
   - [Ownership enforcement & badge accuracy](#ownership-enforcement--badge-accuracy)
2. [Notification kinds](#notification-kinds)
3. [Categories (inbox tabs)](#categories-inbox-tabs)
4. [The `TRENDING_DIGEST` daily digest](#the-trending_digest-daily-digest)
5. [Chat, channel & live notifications](#chat-channel--live-notifications)
6. [`NotificationResponse` shape](#notificationresponse-shape)
7. [Endpoints](#endpoints)
8. [Storage & retention](#storage--retention)

---

## Core concepts

### Aggregation (coalescing)

Repeated events of the same **aggregable** kind on the same resource collapse into
**one inbox row** instead of flooding the inbox. The binding key is the row's
**`groupKey`** (convention: `{KIND}:{resourceId}`, e.g. `POST_REACTED:{postId}`).

While an *active group* exists for `(userId, groupKey)` — tracked in
`notif_active_group_by_user` with a **60-minute TTL** — a new matching event:

- bumps `aggregateCount` (+1),
- replaces `lastActorId` with the newest actor (drives the "and N others" avatar),
- rewrites `body` to the latest event text,
- resets the row to **unread** (it resurfaces at the top of the inbox),
- keeps the **same notification id** — clients receiving the realtime
  `notification` event must **upsert by id**, not append,
- does **not** bump the unread counter again (the row was one badge unit already),
- does **not** send another email (only the first event in a group emails —
  see the [per-group throttle](./email-preferences.md#the-per-group-email-throttle)).

After 60 minutes of inactivity the group closes and the next event starts a fresh
row. Non-aggregable kinds (e.g. `USER_MENTIONED`, `NEW_FOLLOWER`,
`ANSWER_ACCEPTED`) always create a distinct row.

Two suppression rules apply before anything is written:

- **Self-suppression** — you are never notified about your own actions.
- **Block-aware** — a block relationship in either direction between actor and
  recipient drops the notification.

### The bounded scan window

There is no per-filter denormalized table. `category` / `type` / `unread`
filtering (and category-scoped counting / bulk marking) is done **in memory over
a bounded scan of the newest 200 rows** of the user's inbox partition
(`SCAN_HARD_LIMIT = 200` in `NotificationServiceImpl`). Consequences you should
design for:

- **Deep pages are approximate.** The list endpoint scans at most
  `min(200, size × (page + 1))` rows before filtering, so pages that would start
  past the newest 200 rows come back **empty** even if older matching rows exist.
- **`totalElements` is approximate.** The reported total is the number of
  matching rows *within the scan window*, not the true all-time total.
- **Category counts are approximate** (`GET /unread/count?category=` scans the
  same window). The **unfiltered** `GET /unread/count` is exact — it is an O(1)
  Cassandra counter read.
- Bulk operations `PATCH /read-all` and `PATCH /category/{category}/read` also
  sweep only the newest 200 rows per call.

In practice a 200-row window covers virtually every real inbox session; treat
anything deeper as "load more history", not as an exhaustively countable set.

### Ownership enforcement & badge accuracy

Behavior of the per-id endpoints (current — supersedes older docs):

- **`PATCH /{id}/read` returns `404`** when the notification does not exist **or
  belongs to another user**. Ownership is enforced through the
  `notification_lookup` table — a caller can no longer mark (or probe) another
  user's notification by guessing UUIDs. Previously any syntactically valid UUID
  returned `200`.
- **Bulk `PATCH /read`** caps the id list at **200 distinct ids** and **silently
  skips** ids that do not exist or are not owned by the caller. The response
  reports the count actually marked.
- **`DELETE /{id}`** is owner-scoped the same way: only a row owned by the
  caller is removed. (It stays a `204` no-op for foreign/unknown ids — nothing is
  deleted and no events fire.)
- **The unread badge counter no longer drifts:**
  - marking an **already-read** notification does **not** decrement the counter
    (repeated mark-read calls can't push the badge negative);
  - deleting an **unread** notification now **correctly decrements** the counter
    (the badge can't stay stuck above zero after deletes).

---

## Notification kinds

The `type` field of every notification. Verified against the
`NotificationKind` enum (delivery metadata) and the `NotificationType` enum
(wire values). **Pref category** names the email toggle that gates the kind
(see [email-preferences.md](./email-preferences.md)); **Aggregates** marks the
60-minute coalescing behavior; **Email** marks whether the kind is
email-eligible at all.

| Kind (`type`) | Pref category | Aggregates | Email | Meaning |
|---|---|---|---|---|
| `NEW_FOLLOWER` | SOCIAL | no | yes | Someone started following you (one row per follower) |
| `UNBLOCKED` | SOCIAL | no | yes | A user who had blocked you removed the block |
| `POST_NEW` | SOCIAL | no | yes | A followed account published a post (fan-out) |
| `POST_REACTED` | SOCIAL | yes | yes | Someone liked your post |
| `POST_COMMENTED` | SOCIAL | yes | yes | Someone commented on your post |
| `POST_COMMENT_REPLIED` | SOCIAL | yes | yes | Someone replied to your comment (depth-1: replies-to-replies notify the top-level comment author) |
| `POST_COMMENT_REACTED` | SOCIAL | yes | yes | Someone liked your comment |
| `POST_SHARED` | SOCIAL | yes | yes | Someone shared your post |
| `POST_MENTIONED` | MENTIONS | no | yes | Legacy mention type — retained for old rows; new code emits `USER_MENTIONED` |
| `USER_MENTIONED` | MENTIONS | no | yes | You were `@`-mentioned (post / comment / research / answer) |
| `STORY_PUBLISHED` | SOCIAL | no | yes | A followed account published a story |
| `STORY_REACTED` | SOCIAL | yes | yes | Someone reacted to your story |
| `STORY_REPLIED` | SOCIAL | yes | yes | Someone replied to your story |
| `PUBLICATION_LIKED` | SOCIAL | yes | yes | Someone liked your published research |
| `PUBLICATION_COMMENTED` | SOCIAL | yes | yes | Someone commented on your research |
| `PUBLICATION_COMMENT_REACTED` | SOCIAL | yes | yes | Someone liked your comment on a research publication |
| `PUBLICATION_CITED` | SOCIAL | no | yes | Someone cited your publication |
| `RESEARCH_CONTRIBUTOR_ADDED` | SOCIAL | no | yes | A researcher added you as a contributor |
| `QUESTION_NEW` | SOCIAL | no | yes | A new question landed in an area you follow |
| `QUESTION_ANSWERED` | SOCIAL | no | yes | Someone answered your question |
| `ANSWER_REPLIED` | SOCIAL | yes | yes | Someone replied to your answer |
| `ANSWER_REACTED` | SOCIAL | yes | yes | Someone reacted to your answer |
| `ANSWER_ACCEPTED` | SOCIAL | no | yes | The question author accepted your answer |
| `SOUND_APPROVED` | SYSTEM | no | yes | Your uploaded sound passed moderation |
| `SYSTEM_MESSAGE` | SYSTEM | no | yes | Direct system message |
| `SYSTEM_ANNOUNCEMENT` | SYSTEM | no | yes | Broadcast platform announcement |
| `ACCOUNT_WARNING` | SYSTEM | no | yes | Moderation warning on your account |
| `TRENDING_DIGEST` | TRENDING | no | yes | Daily "trending in scholarship" digest — [details below](#the-trending_digest-daily-digest) |
| `NEW_MESSAGE` | SOCIAL | yes | **no** | A new direct/group message while you were offline — [details below](#chat-channel--live-notifications) |
| `MESSAGE_REQUEST` | SOCIAL | no | **no** | A stranger's first message landed in your Requests inbox |
| `ADDED_TO_GROUP` | SOCIAL | no | **no** | Someone added you to a group conversation |
| `CALL_MISSED` | SOCIAL | yes | **no** | A call rang out (or the caller hung up) before you answered |
| `MESSAGE_MENTION` | MENTIONS | no | **no** | You were `@`-mentioned in a chat message / channel post — cuts through mute ([details](#chat-channel--live-notifications)) |
| `CHANNEL_NEW_POST` | SOCIAL | yes | **no** | A channel you subscribe to published a post (mute-aware fan-out) |
| `CHANNEL_JOIN_REQUEST` | SOCIAL | yes | **no** | Someone asked to join a channel you administer |
| `CHANNEL_JOIN_APPROVED` | SOCIAL | no | **no** | Your channel join request was approved |
| `STREAM_STARTED` | SOCIAL | no | **no** | A user you follow went live (follower fan-out) |

> Being email-*eligible* does not mean an email is always sent — the master +
> per-category toggles and the 1-hour per-group throttle still apply. See
> [email-preferences.md](./email-preferences.md).

> **Chat, channel and live kinds are deliberately in-app only** (`Email: no`).
> The SSE stream is the live path and the bell covers offline/backgrounded
> users; emailing per message / channel post / go-live would flood mailboxes.
> Full trigger + fan-out semantics for this family:
> [Chat, channel & live notifications](#chat-channel--live-notifications).

> The `NotificationType` enum also contains legacy values with no live trigger
> (`UNFOLLOWED`, `BLOCKED`, `RESTRICTED`, `CONNECTION_REQUEST`,
> `CONNECTION_ACCEPTED`); they may appear on very old rows only.

---

## Categories (inbox tabs)

Every response carries a `category` derived from its `type` — used for tabbed
inboxes, the `category` list filter, category unread counts, and
`PATCH /category/{category}/read`. Verified against `NotificationCategory`:

| Category | Types it groups |
|---|---|
| `POSTS` | `POST_NEW`, `POST_REACTED`, `POST_COMMENTED`, `POST_COMMENT_REPLIED`, `POST_COMMENT_REACTED`, `POST_SHARED`, `POST_MENTIONED` |
| `QNA` | `QUESTION_NEW`, `QUESTION_ANSWERED`, `ANSWER_REPLIED`, `ANSWER_REACTED`, `ANSWER_ACCEPTED` |
| `RESEARCH` | `PUBLICATION_LIKED`, `PUBLICATION_COMMENTED`, `PUBLICATION_CITED` |
| `MENTIONS` | `USER_MENTIONED`, `MESSAGE_MENTION` (always, regardless of where the mention happened) |
| `SOCIAL` | `NEW_FOLLOWER`, `UNFOLLOWED`, `BLOCKED`, `UNBLOCKED`, `RESTRICTED`, `CONNECTION_REQUEST`, `CONNECTION_ACCEPTED`, `STREAM_STARTED` |
| `CHAT` | `NEW_MESSAGE`, `MESSAGE_REQUEST`, `ADDED_TO_GROUP`, `CALL_MISSED`, `CHANNEL_NEW_POST`, `CHANNEL_JOIN_REQUEST`, `CHANNEL_JOIN_APPROVED` |
| `SYSTEM` | `SYSTEM_MESSAGE`, `SYSTEM_ANNOUNCEMENT`, `ACCOUNT_WARNING`, `TRENDING_DIGEST` |

> `STREAM_STARTED` sits in `SOCIAL`, not `CHAT` — it is a follow-driven alert
> (like `NEW_FOLLOWER`), not a conversation event. Everything that lives in the
> messenger — messages, requests, group adds, missed calls, channel activity —
> lands in the `CHAT` tab.

> **Category** (inbox grouping, 6 buckets) is distinct from a kind's
> **preference category** (email gate, 4 buckets: SOCIAL / MENTIONS / SYSTEM /
> TRENDING). They overlap by name but serve different purposes — e.g.
> `TRENDING_DIGEST` renders in the `SYSTEM` inbox tab but is emailed under its
> own independent `trending` toggle.

> **Uncategorized kinds.** A few types are currently in no category bucket:
> `STORY_PUBLISHED`, `STORY_REACTED`, `STORY_REPLIED`, `SOUND_APPROVED`,
> `PUBLICATION_COMMENT_REACTED`, `RESEARCH_CONTRIBUTOR_ADDED`. Rows of these
> kinds come back with `"category": null` in list responses and are **not
> reachable** through `?category=` filters or category-scoped bulk operations
> (filter them via `?type=` instead). Render `category: null` as a generic row.

---

## The `TRENDING_DIGEST` daily digest

An X-style, server-pushed digest of tags trending in scholarly content
(`QUESTION` + `RESEARCH` scopes), fired by `TrendingNotificationJob`:

- **Schedule:** daily at **09:00 UTC** (cron `0 0 9 * * *`; override with
  `irc.trending.notifications.cron`; disable entirely with
  `irc.trending.notifications.enabled=false`).
- **Hard cap — one per user per UTC day.** The group key is
  `TRENDING_DIGEST:{yyyy-MM-dd}`, so even a manual re-fire of the job cannot
  produce a second row for the same day.
- **Composition:** top 5 tags per scope, deduped by tag (highest usage count
  wins), tags with fewer than 3 uses dropped, trimmed to 5 overall. If nothing
  qualifies, the job **skips the day entirely** — users are never notified about
  nothing.
- **Shape:** no actor (`actorId` etc. are `null`), `resourceType: "Trending"`,
  `resourceId: null`, `deepLink: null`, body like
  `"Hot tags from scholars and researchers: #hajj, #ramadan, #tafsir-quran."`.
- **Inbox tab:** `SYSTEM`. **Email gate:** the independent `trending` toggle
  (`emailTrendingEnabled`) — muting the digest email does not mute system
  emails, and vice versa. See [email-preferences.md](./email-preferences.md).

---

## Chat, channel & live notifications

The messaging stack (DMs/groups, Telegram-style channels, calls, live
streaming) plugs into the same delivery engine — self-suppression, block
filtering, 60-minute aggregation, unread counter, SSE push all apply. The whole
family is **in-app only** (never emailed). What differs per kind is *who* is
fanned to and *when*:

| Kind | Fires when | Receiver set | Group key | Notes |
|---|---|---|---|---|
| `NEW_MESSAGE` | A message is delivered in a DM/group | **Offline**, non-muted members of conversations ≤ 256 members | `NEW_MESSAGE:{conversationId}` | Online users get the SSE `message.new` instead; a burst coalesces into one "@alice: latest preview" row |
| `MESSAGE_REQUEST` | A stranger's first message routes to your Requests inbox | The recipient | `MESSAGE_REQUEST:{conversationId}` | One row per request thread |
| `ADDED_TO_GROUP` | Someone adds you to a group (at creation or later) | The added user | `ADDED_TO_GROUP:{conversationId}:{userId}` | |
| `CALL_MISSED` | A call rang out unanswered (60 s ring timeout) **or** the caller hung up while still ringing | Every invitee who never joined nor declined | `CALL_MISSED:{conversationId}` | Body says voice/video + caller ("You missed a video call from @alice"); repeats coalesce into "N missed calls" |
| `MESSAGE_MENTION` | A non-silent message/post `@`-mentions a member | Each mentioned member who can read the message | `MESSAGE_MENTION:{conversationId}:{userId}` | **Overrides mute, presence and group-size gates**; the mentioned member is excluded from `NEW_MESSAGE`/`CHANNEL_NEW_POST` for that message — see [mentions.md](../platform/mentions.md#chat--channel-mentions) |
| `CHANNEL_NEW_POST` | A non-silent post is published in a channel | **Every active, non-muted subscriber**, any channel size | `CHANNEL_NEW_POST:{channelId}` | Body is "Channel title: post preview"; a posting burst is ONE row with a bumped count |
| `CHANNEL_JOIN_REQUEST` | Someone asks to join a channel you can approve for | Channel owner + admins | `CHANNEL_JOIN_REQUEST:{channelId}` | "@alice and 3 others requested to join" |
| `CHANNEL_JOIN_APPROVED` | An admin approves your join request | The requester | `CHANNEL_JOIN_APPROVED:{channelId}:{userId}` | |
| `STREAM_STARTED` | A user you follow goes live | Every follower (keyset fan-out, capped at 50 000) | `STREAM_STARTED:{streamId}` | One row per go-live; the realtime `stream.started` SSE event rides alongside |

### Semantics worth knowing

- **Mute is honored everywhere — except for direct `@mentions`.** A
  conversation/channel muted via `POST /conversations/{id}/mute` produces
  **no bell rows** while `mutedUntil` is in the future — but unread counts
  still accrue (mute silences push, not the count). For channels the mute
  filter is applied **in the fan-out query itself**, so muted subscribers cost
  nothing. The one deliberate exception is `MESSAGE_MENTION`: being
  `@`-mentioned by name pings through mute (Telegram semantics).
- **Silent channel posts** (`silent: true` on send — Telegram's "post without
  notification") deliver, count as unread, and appear in realtime, but skip the
  `CHANNEL_NEW_POST` fan-out entirely.
- **Presence-gating applies only to `NEW_MESSAGE`.** A channel post or a
  go-live is *content* (like `POST_NEW`), so subscribers/followers get the row
  whether online or not; a DM bell for a user actively looking at the app would
  be noise, so it fires only for offline recipients.
- **Scale.** `CHANNEL_NEW_POST` and `STREAM_STARTED` fan out on the async pool
  with a keyset scan in 500-row pages, capped at **50 000** recipients
  (`ChannelPostFanoutService`, `LiveStreamFanoutService`) — the sending/go-live
  request never blocks on the fan-out.
- **Disappearing messages** never leak through the bell: the `NEW_MESSAGE`
  preview for a disappearing message is the neutral
  "🕓 Disappearing message" placeholder.
- **Deep links:** `Conversation` rows navigate to `/chat/{id}`, `Channel` rows
  to `/channels/{id}`, `LiveStream` rows to `/live/{id}`.

Source of truth: `ChatNotificationService`, `ChannelPostFanoutService`,
`LiveStreamFanoutService`, `CallService.notifyMissedInvitees`,
`MessageService.dispatch`.

---

## `NotificationResponse` shape

Returned by every listing endpoint (and mirrored — in condensed form — on the
SSE `notification` event; see [realtime.md](./realtime.md) for the exact wire
payload, which differs slightly).

```json
{
  "id":                "3f1c2f6e-4d0e-4f4e-9d20-6b9f0a2f31aa",
  "type":              "POST_REACTED",
  "category":          "POSTS",
  "title":             "New reaction on your post",
  "body":              "Ahmad Al-Rashid and 3 others reacted to your post",

  "actorId":           "b9a7…",
  "actorUsername":     "ahmad.rashid",
  "actorFullName":     "Ahmad Al-Rashid",
  "actorProfileImage": "https://cdn…/avatars/ahmad.jpg",

  "aggregateCount":    4,
  "lastActorId":       "b9a7…",
  "lastActorUsername": "ahmad.rashid",

  "resourceId":        "7c0d…",
  "resourceType":      "Post",
  "deepLink":          "/posts/7c0d…",

  "isRead":            false,
  "readAt":            null,
  "createdAt":         "2026-07-20T09:45:00"
}
```

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | Stable across coalesced updates of the same group — upsert by it |
| `type` | enum | One of the [kinds](#notification-kinds); `null` for unparseable legacy rows |
| `category` | enum \| null | Derived inbox tab; `null` for [uncategorized kinds](#categories-inbox-tabs) |
| `title` / `body` | string | Pre-composed, render as-is; for aggregated rows `body` reflects the latest event + count |
| `actorId`, `actorUsername`, `actorFullName`, `actorProfileImage` | — | Primary actor (`null` for system rows). Use for the avatar |
| `aggregateCount` | long | `1` for a single event; `> 1` for a coalesced row ("and N others") |
| `lastActorId`, `lastActorUsername` | — | Most recent contributor to an aggregated row (may equal `actor*`) |
| `resourceId` / `resourceType` | — | What the notification is about: `Post`, `Comment`, `Question`, `Answer`, `Research`, `User`, `Conversation`, `Channel`, `LiveStream`, `Trending`, … |
| `deepLink` | string \| null | Ready-to-navigate client path (`/posts/{id}`, `/comments/{id}`, `/questions/{id}`, `/answers/{id}`, `/researches/{id}`, `/users/{id}`, `/chat/{id}`, `/channels/{id}`, `/live/{id}`). Navigate with this; `null` for opaque resources |
| `isRead` | boolean | Read state |
| `readAt` | datetime \| null | Always `null` on the Cassandra read path (not tracked per-row) |
| `createdAt` | datetime | UTC |

---

## Endpoints

### List notifications

```
GET /api/v1/notifications
```

**Auth:** Bearer JWT.

Paged inbox, newest first. All filters **compose (AND)** — `unread=true`
restricts whatever `category`/`type` selected. When both `category` and `type`
are supplied, `category` wins (it already names a fixed type set).

| Query param | Type | Default | Notes |
|---|---|---|---|
| `category` | enum | — | `POSTS` \| `QNA` \| `RESEARCH` \| `MENTIONS` \| `SOCIAL` \| `SYSTEM` |
| `type` | enum, repeatable | — | e.g. `?type=POST_REACTED&type=POST_SHARED` |
| `unread` | boolean | — | `true` → unread rows only |
| `page` | int | `0` | Zero-based |
| `size` | int | `20` | Rows per page |

**Response `200`** — Spring `Page<NotificationResponse>`:

```json
{
  "content": [ { "id": "…", "type": "POST_REACTED", "…": "…" } ],
  "number": 0,
  "size": 20,
  "totalElements": 3,
  "totalPages": 1,
  "first": true,
  "last": true
}
```

> **Accuracy caveat:** filtering happens over the
> [bounded scan window](#the-bounded-scan-window) of the **newest 200 rows**.
> Pages that would begin beyond that window return empty, and
> `totalElements` / `totalPages` count matches *within the window only* —
> treat them as approximate. Sorting is fixed (newest first); `sort` params
> are ignored.

**Errors**

| Status | `errorCode` | When |
|---|---|---|
| 400 | `TYPE_MISMATCH` | `category` / `type` / `unread` value isn't a valid enum/boolean |
| 401 | `AUTH_UNAUTHORIZED` / `AUTH_*` | Missing or invalid token |

**Side effects:** none.

---

### List unread

```
GET /api/v1/notifications/unread
```

**Auth:** Bearer JWT.

Shorthand for `GET /api/v1/notifications?unread=true` (no category/type filter).

| Query param | Type | Default |
|---|---|---|
| `page` | int | `0` |
| `size` | int | `20` |

**Response `200`** — `Page<NotificationResponse>` (unread only, newest first).
Same [scan-window caveat](#the-bounded-scan-window) as the main list.

**Errors:** 401 `AUTH_UNAUTHORIZED` / `AUTH_*`.

**Side effects:** none.

---

### Unread count (badge)

```
GET /api/v1/notifications/unread/count
```

**Auth:** Bearer JWT.

| Query param | Type | Notes |
|---|---|---|
| `category` | enum | Optional — count unread in one category only |

**Response `200`**

```json
{ "count": 7 }
```

- **Without `category`:** an **O(1) point read** of the Cassandra
  `notification_unread_counter` — exact and cheap; poll/seed the badge from
  this freely.
- **With `category`:** an in-memory count over the newest-200-row
  [scan window](#the-bounded-scan-window) — approximate for very full inboxes.

**Errors**

| Status | `errorCode` | When |
|---|---|---|
| 400 | `TYPE_MISMATCH` | Unknown `category` value |
| 401 | `AUTH_UNAUTHORIZED` / `AUTH_*` | Missing or invalid token |

**Side effects:** none.

---

### Mark all read

```
PATCH /api/v1/notifications/read-all
```

**Auth:** Bearer JWT.

Marks every unread row **within the newest-200-row scan window** as read and
decrements the unread counter once per row actually marked.

**Response `200`** — empty body.

**Errors:** 401 `AUTH_UNAUTHORIZED` / `AUTH_*`.

**Side effects (SSE, all of the caller's tabs):**
- `read` — `{ "ids": [<marked ids>], "allRead": true, "deleted": false }`

---

### Mark one read

```
PATCH /api/v1/notifications/{id}/read
```

**Auth:** Bearer JWT. **Owner-scoped.**

| Path param | Type | Notes |
|---|---|---|
| `id` | UUID | Notification id |

Marks a single notification read.

- Returns **`404`** when the id doesn't exist **or the notification belongs to
  another user** — ownership is enforced (previously any UUID returned `200`).
- Idempotent for the badge: marking an **already-read** row succeeds (`200`) but
  does **not** decrement the unread counter again.

**Response `200`** — empty body.

**Errors**

| Status | `errorCode` | When |
|---|---|---|
| 400 | `TYPE_MISMATCH` | `id` is not a UUID |
| 401 | `AUTH_UNAUTHORIZED` / `AUTH_*` | Missing or invalid token |
| 404 | `RESOURCE_NOT_FOUND` | Id doesn't exist, or is owned by another user |

**Side effects (SSE):**
- `read` — `{ "ids": ["<id>"], "allRead": false, "deleted": false }`

---

### Mark many read (bulk)

```
PATCH /api/v1/notifications/read
```

**Auth:** Bearer JWT. **Owner-scoped.**

**Request body**

```json
{ "ids": ["3f1c2f6e-…", "9a41d7b0-…"] }
```

| Field | Type | Notes |
|---|---|---|
| `ids` | UUID[] | Capped at **200 distinct ids** per request; extras beyond the cap are ignored. `null`/empty → `{"updated": 0}` |

Rules:

- Duplicate ids are collapsed before processing.
- Ids that don't exist or are **not owned by the caller are silently skipped**
  (no error) — the response reports the count **actually marked**.
- Already-read rows are skipped and don't decrement the badge.

**Response `200`**

```json
{ "updated": 2 }
```

| Field | Type | Notes |
|---|---|---|
| `updated` | int | Number of rows that transitioned unread → read (or were newly touched) for the caller |

**Errors**

| Status | `errorCode` | When |
|---|---|---|
| 400 | `MALFORMED_JSON` | Body isn't valid JSON / `ids` isn't a UUID array |
| 401 | `AUTH_UNAUTHORIZED` / `AUTH_*` | Missing or invalid token |

**Side effects (SSE):**
- `read` — `{ "ids": [<ids actually marked>], "allRead": false, "deleted": false }`

---

### Mark a category read

```
PATCH /api/v1/notifications/category/{category}/read
```

**Auth:** Bearer JWT.

| Path param | Type | Notes |
|---|---|---|
| `category` | enum | `POSTS` \| `QNA` \| `RESEARCH` \| `MENTIONS` \| `SOCIAL` \| `SYSTEM` |

Marks every unread row of the category **within the newest-200-row scan window**
as read.

**Response `200`**

```json
{ "updated": 5 }
```

**Errors**

| Status | `errorCode` | When |
|---|---|---|
| 400 | `TYPE_MISMATCH` | Unknown `category` |
| 401 | `AUTH_UNAUTHORIZED` / `AUTH_*` | Missing or invalid token |

**Side effects (SSE):**
- `read` — `{ "ids": [<marked ids>], "allRead": false, "deleted": false }`

---

### Delete one

```
DELETE /api/v1/notifications/{id}
```

**Auth:** Bearer JWT. **Owner-scoped.**

| Path param | Type | Notes |
|---|---|---|
| `id` | UUID | Notification id |

Hard-deletes one notification (row + lookup entry). Ownership is enforced the
same way as mark-one-read: an id that doesn't exist or belongs to another user
deletes **nothing** — the response is still `204`, but no row is touched and no
SSE events fire.

Badge accuracy: when the deleted row was still **unread**, the unread counter is
**correctly decremented** — the badge can't stay inflated after deletes.

**Response `204`** — no content (in all cases).

**Errors**

| Status | `errorCode` | When |
|---|---|---|
| 400 | `TYPE_MISMATCH` | `id` is not a UUID |
| 401 | `AUTH_UNAUTHORIZED` / `AUTH_*` | Missing or invalid token |

**Side effects (SSE, only when a row was actually removed):**
- `deleted` — `{ "ids": ["<id>"], "allRead": false, "deleted": true }`

---

### Purge read (delete all read)

```
DELETE /api/v1/notifications/read
```

**Auth:** Bearer JWT.

Hard-deletes every already-read notification in the inbox. Unread rows are never
touched.

**Implementation (single range-tombstone strategy):** the service scans the
newest page (up to 500 rows), finds the `createdAt` of the **oldest unread** row,
and issues **one Cassandra range delete** for everything strictly older than that
boundary — every older row is guaranteed read since rows arrive in time order.
This collapses up to N row tombstones into a single range tombstone, so
subsequent inbox reads aren't slowed by ghost-row scans during the gc-grace
window. Read rows *newer* than the oldest unread (the rare "read a middle
notification" case) fall back to per-row deletes, bounded by the page size. If
the whole page is read, the entire range is swept in one delete. Lookup rows are
not scatter-deleted; they expire via their own TTL.

**Response `200`**

```json
{ "deleted": 12 }
```

| Field | Type | Notes |
|---|---|---|
| `deleted` | int | Read rows removed (counted within the scanned page) |

**Errors:** 401 `AUTH_UNAUTHORIZED` / `AUTH_*`.

**Side effects (SSE, only when `deleted > 0`):**
- `deleted` — `{ "ids": [], "allRead": true, "deleted": true }` — note `ids` is
  **empty** here; `allRead: true` + `deleted: true` means "drop every read row
  from your local cache".

---

## Storage & retention

| Table | Key | Role |
|---|---|---|
| `notifications_by_user` | partition `user_id`, clustering `created_at DESC, notification_id` | The inbox, newest first |
| `notification_lookup` | `notification_id` | id → `(user_id, created_at)` so per-id endpoints can locate the clustered row **and verify ownership**; ~90-day TTL |
| `notif_active_group_by_user` | partition `user_id`, clustering `group_key` | The open coalescing head per group; **60-min TTL = the aggregation window** |
| `notification_unread_counter` | `user_id` (counter `unread`) | O(1) unread badge; `+1` on fresh insert, `-1` on unread→read and on deleting an unread row |

Retention: a daily cleanup job prunes notifications that have been **read** for
more than 90 days; unread rows are never pruned. Cassandra is eventually
consistent — a read immediately after a write can be a beat behind; the
[SSE stream](./realtime.md) is the live truth.

---

**See also:** [Realtime SSE stream](./realtime.md) ·
[Email preferences & delivery](./email-preferences.md) ·
[Error envelope](../errors/error-handling.md)

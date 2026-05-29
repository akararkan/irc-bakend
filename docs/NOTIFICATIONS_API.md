# Notifications — full reference

The notification system: how events become notifications, how they're stored,
aggregated, pushed live, emailed, and read. Covers the REST + SSE API the
frontend integrates against and the server-side pipeline behind it.

Base path: **`/api/v1/notifications`**  ·  Email prefs: **`/api/v1/users/me/email-preferences`**

Related: [`USER_API.md`](./USER_API.md) (auth, the `/users` surface), [`POST_ACTIONS_API.md`](./POST_ACTIONS_API.md), [`QNA_API.md`](./QNA_API.md), [`RESEARCH_API.md`](./RESEARCH_API.md) (the actions that trigger notifications).

---

## Table of contents

1. [Architecture at a glance](#1-architecture-at-a-glance)
2. [Core concepts](#2-core-concepts)
3. [Notification catalog (kinds)](#3-notification-catalog-kinds)
4. [Categories (inbox tabs)](#4-categories-inbox-tabs)
5. [Triggers: what creates a notification](#5-triggers-what-creates-a-notification)
6. [`NotificationResponse` shape](#6-notificationresponse-shape)
7. [REST endpoints](#7-rest-endpoints)
8. [Realtime SSE stream](#8-realtime-sse-stream)
9. [Email integration](#9-email-integration)
10. [Cassandra storage](#10-cassandra-storage)
11. [Frontend integration guide](#11-frontend-integration-guide)
12. [Cross-cutting rules](#12-cross-cutting-rules)

---

## 1. Architecture at a glance

Notifications are **Cassandra-native**, fed by **RabbitMQ** domain events, pushed
live over **SSE** with **Redis** pub/sub fan-out, and optionally **emailed**.

**Write path** (an action somewhere → a notification):

```
domain action (like / comment / follow / answer / mention / …)
   → publisher emits a RabbitMQ event (exchange irc.events, routing key e.g. post.social.reacted)
   → NotificationEventConsumer (@RabbitListener on irc.queue.notifications)
        builds a DeliverRequest (recipient, kind, title, body, actor, resource, groupKey)
   → CassandraNotificationService.deliverSync
        ├─ suppress?  (self-action OR blocked between actor↔recipient → drop)
        ├─ aggregate? (active group for (user, groupKey) within 60 min → coalesce, else insert fresh)
        ├─ write Cassandra: notifications_by_user (+ notification_lookup, + notif_active_group_by_user)
        ├─ notification_unread_counter += 1   (fresh insert only)
        ├─ publish to Redis  irc:notifications:{userId}   (AFTER tx commit)
        └─ email?     (kind email-eligible AND user prefs allow AND not throttled this hour)
```

**Read / live path** (client ← notifications):

```
Redis irc:notifications:{userId}
   → every app instance's NotificationRedisSubscriber
   → NotificationSseService.push(userId, eventName, data)
   → fan-out to ALL open SseEmitters for that user (every tab / device)

REST: GET /notifications (paged inbox) · GET /unread/count (badge) · PATCH/DELETE (state)
```

### Storage: Cassandra-native (one legacy Postgres table)

- **Live store:** Cassandra `notifications_by_user` (+ supporting tables, §10). All new notifications write here.
- **Legacy:** a Postgres `notifications` JPA table still exists for old rows (read-only history); **no new writes** go there. Ignore it for integration.

---

## 2. Core concepts

### Aggregation (coalescing)
Repeated events of the same kind on the same resource **collapse into one inbox
row** instead of flooding it. The key is the **`groupKey`** (e.g.
`POST_REACTED:{postId}`). While an active group exists for `(userId, groupKey)`
— tracked in `notif_active_group_by_user` with a **60-minute** TTL — a new
matching event:

- bumps **`aggregateCount`** (`+1`),
- swaps **`lastActor`** to the newest actor (drives the "and N others" avatar),
- refreshes the **`body`** to the latest event text,
- resets **`isRead` → false** (the row resurfaces as unread),
- **does not** send another email (only the first event in the group emails).

After 60 minutes idle, the group "closes" and the next event starts a fresh row.
Non-aggregable kinds (e.g. `USER_MENTIONED`, `ANSWER_ACCEPTED`) always create a
distinct row.

### Self-suppression & block-aware
A notification is **dropped** (never written) when:
- the actor **is** the recipient (you don't get notified about your own actions), or
- the actor and recipient have a **block** relationship in either direction.

### Deferred enrichment (async)
Building a body often needs extra reads (post author name, comment preview…).
Delivery runs **off the request thread** on an executor — `deliverAsync(Supplier)`
runs the enrichment supplier on the worker, and `deliverAllAsync(Supplier)` builds
a whole batch (e.g. one task for all @mentions in a post). The triggering action's
HTTP response never waits on notification work.

### Realtime = push the row, badge = absolute count
The SSE `notification` event carries the **full `NotificationResponse`** (patch it
into the list in place). The `unread-count` event carries the **absolute** count —
**set** the badge to it, don't increment.

### Transaction safety
The Redis push fires on `@TransactionalEventListener(AFTER_COMMIT)` — if the
delivering transaction rolls back, no phantom SSE event is sent.

---

## 3. Notification catalog (kinds)

Every notification has a **kind** (the `type` field on the response). Each kind
declares a **preference category** (gates email — §9), whether it **aggregates**,
and whether it's **email-eligible**. Source of truth: `NotificationKind`.

| Kind (`type`) | Category | Aggregates | Email | Group key | Meaning |
|---|---|---|---|---|---|
| `NEW_FOLLOWER` | SOCIAL | no | ✅ | `NEW_FOLLOWER:{actorId}` | Someone followed you |
| `POST_NEW` | SOCIAL | no | ❌ | — | A followed account posted (fan-out) |
| `POST_REACTED` | SOCIAL | ✅ | ❌ | `POST_REACTED:{postId}` | Someone liked your post |
| `POST_COMMENTED` | SOCIAL | ✅ | ✅ | `POST_COMMENTED:{postId}` | Someone commented on your post |
| `POST_COMMENT_REPLIED` | SOCIAL | ✅ | ✅ | `POST_COMMENT_REPLIED:{parentCommentId}` | Someone replied to your comment |
| `POST_COMMENT_REACTED` | SOCIAL | ✅ | ❌ | `POST_COMMENT_REACTED:{commentId}` | Someone liked your comment |
| `POST_SHARED` | SOCIAL | ✅ | ✅ | `POST_SHARED:{postId}` | Someone shared your post |
| `USER_MENTIONED` | MENTIONS | no | ✅ | — | You were @mentioned |
| `STORY_PUBLISHED` | SOCIAL | no | ❌ | — | A followed account posted a story |
| `STORY_REACTED` | SOCIAL | ✅ | ❌ | `STORY_REACTED:{storyId}` | Someone reacted to your story |
| `STORY_REPLIED` | SOCIAL | ✅ | ✅ | `STORY_REPLIED:{storyId}` | Someone replied to your story |
| `PUBLICATION_LIKED` | SOCIAL | ✅ | ❌ | `PUBLICATION_LIKED:{researchId}` | Someone reacted to your research |
| `PUBLICATION_COMMENTED` | SOCIAL | ✅ | ✅ | `PUBLICATION_COMMENTED:{researchId}` | Someone commented on your research |
| `PUBLICATION_COMMENT_REACTED` | SOCIAL | ✅ | ❌ | `PUBLICATION_COMMENT_REACTED:{commentId}` | Someone liked your research comment |
| `PUBLICATION_CITED` | SOCIAL | no | ✅ | — | Your research was cited |
| `RESEARCH_CONTRIBUTOR_ADDED` | SOCIAL | no | ✅ | — | You were added as a contributor |
| `QUESTION_NEW` | SOCIAL | no | ❌ | — | New question (scholars + followers fan-out) |
| `QUESTION_ANSWERED` | SOCIAL | no | ✅ | — | Your question got an answer |
| `ANSWER_REPLIED` | SOCIAL | ✅ | ✅ | `ANSWER_REPLIED:{answerId}` | Someone replied to your answer |
| `ANSWER_REACTED` | SOCIAL | ✅ | ❌ | `ANSWER_REACTED:{answerId}` | Someone liked your answer |
| `ANSWER_ACCEPTED` | SOCIAL | no | ✅ | — | Your answer was accepted |
| `ANSWER_BEST_VOTED` | SOCIAL | ✅ | ✅ | `ANSWER_BEST_VOTED:{answerId}` | A scholar endorsed your answer as a best answer |
| `SOUND_APPROVED` | SYSTEM | no | ✅ | — | Your uploaded sound was approved |
| `SYSTEM_MESSAGE` | SYSTEM | no | ✅ | — | Direct system message |
| `SYSTEM_ANNOUNCEMENT` | SYSTEM | no | ✅ | — | Broadcast announcement |
| `ACCOUNT_WARNING` | SYSTEM | no | ✅ | — | Moderation warning |
| `TRENDING_DIGEST` | TRENDING ⟶ SYSTEM tab | no | ✅ | `TRENDING_DIGEST:{yyyy-MM-dd}` | Daily "what scholars and researchers are talking about" digest. Inbox category is `SYSTEM`; **email opt-out is its own toggle** (`emailTrendingEnabled`) so muting the digest doesn't silence account warnings. |

> The catalog is the full set of kinds the platform can emit. Which ones are
> wired to a live trigger today is the table in §5. "Aggregates ✅ / no" matters
> for the frontend: aggregated rows have `aggregateCount > 1` and an updating
> `lastActor`; non-aggregated rows are always one event each.

---

## 4. Categories (inbox tabs)

Each notification carries a **`category`** (derived from its `type`) for tabbed
inboxes and category-scoped reads:

| Category | Kinds it groups |
|---|---|
| `POSTS` | `POST_NEW`, `POST_REACTED`, `POST_COMMENTED`, `POST_COMMENT_REPLIED`, `POST_COMMENT_REACTED`, `POST_SHARED` |
| `QNA` | `QUESTION_NEW`, `QUESTION_ANSWERED`, `ANSWER_REPLIED`, `ANSWER_REACTED`, `ANSWER_ACCEPTED`, `ANSWER_BEST_VOTED` |
| `RESEARCH` | `PUBLICATION_LIKED`, `PUBLICATION_COMMENTED`, `PUBLICATION_COMMENT_REACTED`, `PUBLICATION_CITED` |
| `MENTIONS` | `USER_MENTIONED` (always — regardless of where the mention happened) |
| `SOCIAL` | `NEW_FOLLOWER` and other relationship events |
| `SYSTEM` | `SOUND_APPROVED`, `SYSTEM_MESSAGE`, `SYSTEM_ANNOUNCEMENT`, `ACCOUNT_WARNING`, `TRENDING_DIGEST` |

> **Category** (inbox grouping, 6 buckets) is distinct from a kind's **preference
> category** (email gate, 4 buckets: SOCIAL / MENTIONS / SYSTEM / TRENDING). They
> overlap by name but serve different purposes. The trending digest lives in the
> `SYSTEM` inbox tab so it shows up alongside platform announcements, but its
> email gate is **separate** (`emailTrendingEnabled`) so opting out of digests
> doesn't accidentally mute account warnings.

---

## 5. Triggers: what creates a notification

Domain events arrive on the `irc.queue.notifications` queue and are handled by
`NotificationEventConsumer`. The live mappings:

| Action | Kind | Recipient(s) | Body (example) |
|---|---|---|---|
| Follow a user | `NEW_FOLLOWER` | the followed user | "Ahmad Al-Rashid (@ahmad) started following you." |
| Create a post (PUBLIC) | `POST_NEW` | the author's followers (fan-out) | "@ahmad posted a new POST." |
| React to a post | `POST_REACTED` | post author | "@ahmad reacted 👍 to your post." |
| Comment on a post | `POST_COMMENTED` | post author | "@ahmad commented on your post." |
| Reply to a comment | `POST_COMMENT_REPLIED` | parent comment author | "@ahmad replied to your comment." |
| React to a comment | `POST_COMMENT_REACTED` | comment author | "@ahmad reacted 👍 to your comment." |
| Share a post | `POST_SHARED` | post author | "@ahmad shared your post." |
| @mention (post/comment/research/answer) | `USER_MENTIONED` | each mentioned user | "@ahmad mentioned you: \"…\"" |
| Publish research | `POST_NEW` | researcher's followers (fan-out) | "@ahmad published: \"…\"" |
| React to research | `PUBLICATION_LIKED` | researcher | "@ahmad reacted 👍 to: \"…\"" |
| Comment on research | `PUBLICATION_COMMENTED` | researcher | "@ahmad commented: \"…\"" |
| React to a research comment | `PUBLICATION_COMMENT_REACTED` | comment author | "@ahmad reacted to your comment on: \"…\"" |
| Create a question | `QUESTION_NEW` | scholars + author's followers (deduped fan-out) | "@ahmad asked: \"…\"" |
| Answer a question | `QUESTION_ANSWERED` | question author | "@ahmad answered: \"…\"" |
| React to an answer | `ANSWER_REACTED` | answer author | "@ahmad reacted 👍 on your answer." |
| Accept an answer | `ANSWER_ACCEPTED` | answer author | "@ahmad accepted your answer on: \"…\"" |
| Endorse a best answer (scholar vote) | `ANSWER_BEST_VOTED` | answer author | "@ahmad endorsed your answer as a best answer to: \"…\"" |
| Daily trending digest (server-pushed, no actor) | `TRENDING_DIGEST` | every active user | "Hot tags from scholars and researchers: #hajj, #ramadan, #tafsir-quran." |

**Trending digest schedule.** Server-pushed by `TrendingNotificationJob` at
`09:00 UTC` daily (override with `irc.trending.notifications.cron`). The job:
- Pulls the top 5 trending tags from each of the `QUESTION` and `RESEARCH`
  scopes (these scopes are inherently scholar/researcher because only those
  roles can publish there).
- Deduplicates by tag name, keeps the higher usage count, drops anything below
  `MIN_USAGE_FLOOR` (3 uses) and trims to the top 5 overall.
- **Skips entirely if the resulting list is empty** — users are never woken
  for nothing.
- Fans out to every active+enabled user (`u.deletedAt IS NULL AND u.isEnabled`).
  groupKey is `TRENDING_DIGEST:{yyyy-MM-dd}` → a user receives at most one
  digest per UTC day even if the job is re-fired manually.
- Disable the whole pipeline with `irc.trending.notifications.enabled=false`.

**No notification fired** for: un-like / un-save / un-share, deletes (post,
comment, question, answer), unfollow (the prior `NEW_FOLLOWER` row is *removed*),
block/unblock (silent), and best-answer **un-votes** (retracting an endorsement
is silent; the up-vote fires `ANSWER_BEST_VOTED`).

> Bodies are built with the actor's display name/handle and a short resource
> preview. They're snapshots — stored on the row, not re-rendered.

---

## 6. `NotificationResponse` shape

```json
{
  "id":                "uuid",
  "type":              "POST_REACTED",
  "category":          "POSTS",
  "title":             "New reaction on your post",
  "body":              "Ahmad Al-Rashid and 3 others reacted to your post",

  "actorId":           "uuid",
  "actorUsername":     "ahmad.rashid",
  "actorFullName":     "Ahmad Al-Rashid",
  "actorProfileImage": "https://cdn…/avatars/ahmad.jpg",

  "aggregateCount":    4,
  "lastActorId":       "uuid",
  "lastActorUsername": "someone.else",

  "resourceId":        "post-uuid",
  "resourceType":      "POST",
  "deepLink":          "/posts/post-uuid",

  "isRead":            false,
  "readAt":            null,
  "createdAt":         "2026-05-28T09:45:00"
}
```

| Field | Notes |
|---|---|
| `type` | The kind (§3). |
| `category` | Inbox bucket (§4). |
| `title` / `body` | Pre-composed, ready to render. For aggregated rows, `body` reflects the latest event + count. |
| `actor*` | The **primary** actor — for an aggregated row this is the *most recent* contributor (use for the avatar). |
| `aggregateCount` | `1` for a single event; `> 1` for a coalesced row ("and N others"). |
| `lastActorId` / `lastActorUsername` | Most recent contributor (may equal `actor*`). |
| `resourceId` / `resourceType` | What it's about (`POST`, `QUESTION`, `RESEARCH`, `COMMENT`, …). |
| `deepLink` | Ready-to-navigate path (e.g. `/posts/{id}`). **Navigate with this** — don't build URLs from `resourceType`+`resourceId`. May be `null` for opaque/system rows. |
| `isRead` / `readAt` | Read state. |

---

## 7. REST endpoints

All require auth (`Authorization: Bearer <jwt>`) **except** the SSE stream (§8),
which also accepts `?token=`.

### 7.1 List notifications
```
GET /api/v1/notifications?category=&type=&unread=&page=0&size=20
```
| Param | Notes |
|---|---|
| `category` | `POSTS` \| `QNA` \| `RESEARCH` \| `MENTIONS` \| `SOCIAL` \| `SYSTEM` (optional) |
| `type` | one or more kinds, repeatable (`?type=POST_REACTED&type=POST_SHARED`) |
| `unread` | `true` → restrict to unread (composes with `category`/`type`) |
| `page` / `size` | standard paging (default size 20) |

→ `200` `Page<NotificationResponse>` (newest first). **Filters compose (AND).** `unread` no longer shadows the others — `?unread=true&category=QNA` returns only **unread Q&A** items. When both `category` and `type` are sent, `category` wins (it already names a fixed type set).

### 7.2 Unread list
```
GET /api/v1/notifications/unread?page=0&size=20
```
→ `200` `Page<NotificationResponse>` (unread only).

### 7.3 Unread count (badge)
```
GET /api/v1/notifications/unread/count?category=
```
→ `200` `{ "count": 7 }`. With `category`, counts that category only; otherwise the total (an O(1) read of the `notification_unread_counter`).

### 7.4 Mark all read
```
PATCH /api/v1/notifications/read-all
```
→ `200` (empty). Marks every row read; emits a `read` SSE event with `allRead: true`.

### 7.5 Mark one read
```
PATCH /api/v1/notifications/{id}/read
```
→ `200` (empty). Emits `read` with `ids: [id]`.

### 7.6 Mark many read
```
PATCH /api/v1/notifications/read
Body: { "ids": ["uuid", "uuid"] }
```
→ `200` `{ "updated": 2 }` (already-read rows are skipped).

### 7.7 Mark a category read
```
PATCH /api/v1/notifications/category/{category}/read
```
→ `200` `{ "updated": 5 }`.

### 7.8 Delete one
```
DELETE /api/v1/notifications/{id}
```
→ `204`. Emits `deleted` with `ids: [id]`.

### 7.9 Delete all read
```
DELETE /api/v1/notifications/read
```
→ `200` `{ "deleted": 12 }`. Purges already-read rows; emits `deleted`.

> Every state-changing endpoint also pushes a fresh `unread-count` SSE event so
> all of a user's tabs stay in sync (§8).

---

## 8. Realtime SSE stream

```
GET /api/v1/notifications/stream            Content-Type: text/event-stream
GET /api/v1/notifications/stream?token=<jwt>   (browsers — EventSource can't send headers)
```

**Auth:** Bearer header **or** `?token=<accessToken>`. On failure the server writes
a bare `401` (plain text) and closes — it does **not** emit a JSON error (that would
break the negotiated `text/event-stream`). Connection lifetime: **24 h**; a
**heartbeat every 15 s** keeps proxies (nginx / Cloudflare / Railway) from idling
it out. The stream sets `X-Accel-Buffering: no` so events aren't buffered.

**Named events** (use `addEventListener`, not just `onmessage`):

| Event | Payload | Do |
|---|---|---|
| `connected` | `{ userId, timestamp, tabs, message }` | handshake — mark stream live |
| `notification` | a full `NotificationResponse` | prepend to the list; if the `id` already exists (aggregation), **replace** it (count/actors changed) and float to top |
| `unread-count` | `{ count }` | **set** the badge to `count` (absolute, not a delta) |
| `read` | `{ ids:[…], allRead, deleted:false }` | mark those ids read locally (multi-tab sync) |
| `deleted` | `{ ids:[…], allRead, deleted:true }` | remove those ids locally |
| `heartbeat` | `{ timestamp }` | ignore |

**Multi-tab & multi-instance:** all events go through Redis (`irc:notifications:{userId}`),
so every open tab on every app instance receives them. Open one `EventSource` per tab.

---

## 9. Email integration

An email is sent for a notification only when **all** hold:

1. the **kind is email-eligible** (§3 — e.g. `POST_REACTED` is in-app only),
2. the recipient's **master** email toggle is on, **and**
3. the recipient's **category** toggle for the kind's preference category is on:
   - `SOCIAL` → social toggle, `MENTIONS` → mentions toggle, `SYSTEM` → system toggle,
     **`TRENDING` → trending toggle (separate from `system`)**.
4. it isn't **throttled**: a Redis key `notif:email:throttle:{userId}:{groupKey}`
   with a **1-hour** TTL means only the **first** event per group emails within an
   hour (aggregated bursts don't spam the inbox). Sending is async, fail-silent.

### Email preference endpoints

```
GET   /api/v1/users/me/email-preferences      → { master, social, mentions, system, trending }
PATCH /api/v1/users/me/email-preferences       Body: any subset of { master, social, mentions, system, trending }
POST  /api/v1/users/me/email-preferences/test            → sends a test email; { queued, to }
POST  /api/v1/users/me/email-preferences/unsubscribe-all → master off; { emailNotificationsEnabled: false }
```

`master` is the kill-switch — off means no email of any kind. The four category
toggles map to the kind preference categories above. Defaults are all `true`,
including `trending` (the daily digest).

---

## 10. Cassandra storage

| Table | Key | Role |
|---|---|---|
| `notifications_by_user` | part. `user_id`, clust. `created_at DESC, notification_id` | The inbox — newest first. Columns: `type, title, body, actor_id, last_actor_id, aggregate_count, resource_type, resource_id, group_key, read`. ~90-day TTL. |
| `notification_lookup` | `notification_id` | Maps an id → `(user_id, created_at)` so mark-read/delete (which only have the id) can locate the clustered row. |
| `notif_active_group_by_user` | part. `user_id`, clust. `group_key` | The "open" coalescing row per group → `(notification_id, created_at)`. **60-min TTL** = the aggregation window. |
| `notification_unread_counter` | `user_id` (COUNTER `unread`) | O(1) unread badge. `+1` on fresh insert, `-1` on mark-read/delete. |

Eventually consistent (Cassandra). The unread counter is a true counter, so the
badge endpoint is a single-key read.

---

## 11. Frontend integration guide

### 11.1 On app start (authenticated)
1. `GET /unread/count` → seed the badge.
2. Open the SSE stream: `new EventSource('/api/v1/notifications/stream?token=' + accessToken)`.
3. Lazy-load the inbox (`GET /notifications?page=0`) when the panel opens.

```ts
const es = new EventSource(`/api/v1/notifications/stream?token=${accessToken}`);
es.addEventListener('notification', e => upsertRow(JSON.parse(e.data)));        // prepend or replace by id
es.addEventListener('unread-count', e => setBadge(JSON.parse(e.data).count));   // SET, not +1
es.addEventListener('read',    e => markReadLocally(JSON.parse(e.data).ids));
es.addEventListener('deleted', e => removeLocally(JSON.parse(e.data).ids));
```

### 11.2 Rendering a row
- Use `title` / `body` directly — they're pre-composed (incl. "and N others" for aggregated rows).
- Avatar ← `actorProfileImage` (the most-recent actor). For `aggregateCount > 1`, show the "+N" treatment.
- Unread dot ← `!isRead`.
- **Navigate via `deepLink`** on tap. If `deepLink` is null, it's an opaque/system row.

### 11.3 Aggregation
- The same notification id can arrive again on the `notification` event with a higher `aggregateCount` and a new `lastActor`. **Upsert by id** — replace the existing row and move it to the top; don't append a duplicate.

### 11.4 Mark-read / delete (optimistic)
- Optimistically flip `isRead` / remove the row, then call the endpoint.
- You'll also receive a `read`/`deleted` + `unread-count` SSE event (that's how your *other* tabs sync) — make your local apply idempotent so the echo is a no-op.
- Badge: trust the `unread-count` event (absolute). Don't hand-maintain the count across actions.

### 11.5 Reconnect & token expiry
- `EventSource` auto-reconnects on a dropped connection.
- But an expired token makes the stream return `401` and close — detect via `onerror` with `readyState === CLOSED`, refresh the access token, then open a **new** `EventSource`.
- `es.close()` on logout and teardown.

### 11.6 Trending digest (`TRENDING_DIGEST`)

The daily **"trending in scholarship"** digest arrives once per UTC day per
user — same shape as every other notification, but worth special UI:

- **Inbox category:** `SYSTEM` (the System tab). The row looks like any other
  `NotificationResponse`. The backend deliberately leaves `deepLink: null`
  because the trending landing route is a frontend concern — the FE app
  resolves it client-side. Current FE convention: route to **`/explore`**
  (the existing trending-strip page); if a dedicated `/explore/trending`
  route is added later, only the FE `notifFrom()` resolver changes — the
  backend payload stays the same.
- **No actor.** `actorId`, `actorUsername`, `actorFullName`, `actorProfileImage`
  are all null. Render as a system row (your logo / a "Trending" icon) rather
  than a user avatar.
- **Body shape.** `body` is a comma-joined hashtag list:
  `"Hot tags from scholars and researchers: #hajj, #ramadan, #tafsir-quran."`
  You can render it verbatim, OR parse for chips client-side:
  ```ts
  const tags = row.body.match(/#[\p{L}\p{N}_-]+/gu) ?? [];
  // → ["#hajj", "#ramadan", "#tafsir-quran"]
  ```
  Render each tag as a chip that routes to `/tags/{tag}`.
- **One per day, hard.** Server enforces this via `groupKey =
  TRENDING_DIGEST:{date}`. You don't need to dedupe client-side, but be
  prepared for the row to arrive once at ~09:00 UTC (or whatever
  `irc.trending.notifications.cron` is set to).
- **Email opt-out.** Settings page should expose the dedicated `trending`
  toggle alongside `social` / `mentions` / `system`. PATCH
  `/users/me/email-preferences { "trending": false }` mutes the email side
  only — users still see the in-app row (which is the right default; the
  in-app inbox isn't intrusive). To silence both surfaces, the user just
  filters their inbox or you can offer a "no trending at all" preset (call
  PATCH with `trending: false` and ignore `TRENDING_DIGEST` types in the
  inbox renderer).

### 11.7 Checklist
- [ ] Badge seeded from `/unread/count`, then **set** from `unread-count` events.
- [ ] Stream opened with `?token=`; named events via `addEventListener`.
- [ ] `notification` upserts by id (handles aggregation); navigate via `deepLink`.
- [ ] Mark-read/delete optimistic + idempotent against the echoed SSE event.
- [ ] Stream reopened after token refresh; closed on logout.
- [ ] Settings page exposes **four** email toggles: master, social, mentions, system, **trending**.
- [ ] `TRENDING_DIGEST` rows render with a system/logo glyph (no avatar), and the body's `#tag` tokens link to the tag feed.

---

## 12. Cross-cutting rules

- **No self-notifications.** Acting on your own content never notifies you.
- **Block-aware.** A block in either direction suppresses delivery.
- **Aggregation window = 60 min** per `(user, groupKey)`; idle past that → a new row next time.
- **Unread badge is authoritative server-side** — always reflect the `unread-count` event / endpoint, never a locally-derived total.
- **Realtime is transaction-safe** — pushes fire only `AFTER_COMMIT`.
- **Delivery is async** — triggering actions never block on notification work; expect the row/SSE a moment after the action's HTTP response.
- **Emails are throttled per group (1 h)** and gated by master + category prefs; aggregated bursts email at most once per hour per group.
- **Eventually consistent** — a counter or list read immediately after a write can be a beat behind; the SSE stream is the live truth.

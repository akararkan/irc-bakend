# Backend Enhancement Plan

Strategic roadmap of what to build next on the IRC platform. Grouped by
impact tier, with social-media (post) features prioritised.

Today the platform ships **3 fully-fledged domains** (Posts / Stories /
Reels, Q&A, Research) and a strong cross-cutting layer (Notifications,
Activity feed, Search, Realtime SSE, Social graph, Sound library). This
document captures **what's missing or could level up** — written so a
new engineer can pick any item, scope it, and ship it.

---

## Table of contents

1. [P0 — Product/market fit blockers](#p0--productmarket-fit-blockers)
   - 1.1 [Direct Messages (1-to-1 + Groups)](#11-direct-messages)
   - 1.2 [Push Notifications (FCM + APNs)](#12-push-notifications)
   - 1.3 [Trending feed](#13-trending-feed)
   - 1.4 [Algorithmic For-You feed](#14-algorithmic-for-you-feed)
   - 1.5 [Moderation & Reporting](#15-moderation--reporting)
2. [P1 — High value, scoped delivery](#p1--high-value-scoped-delivery)
   - 2.1 [Drafts + scheduled posts](#21-drafts--scheduled-posts)
   - 2.2 [Quote-repost](#22-quote-repost)
   - 2.3 [Follow hashtags](#23-follow-hashtags)
   - 2.4 [Pinned posts](#24-pinned-posts)
   - 2.5 [Stories — finish the surface](#25-stories--finish-the-surface)
   - 2.6 [Sounds — search + trending + favorites](#26-sounds--search--trending--favorites)
   - 2.7 [Image / video processing pipeline](#27-image--video-processing-pipeline)
   - 2.8 [Counter reconciliation job](#28-counter-reconciliation-job)
   - 2.9 [Post archive](#29-post-archive)
3. [P2 — Medium scope tools](#p2--medium-scope-tools)
   - 3.1 [Post analytics / insights](#31-post-analytics--insights)
   - 3.2 [Saved-collections management](#32-saved-collections-management)
   - 3.3 [Two-factor auth endpoints](#33-two-factor-auth-endpoints)
   - 3.4 [Rate limiting wired into post endpoints](#34-rate-limiting-wired-into-post-endpoints)
   - 3.5 [GDPR data export + delete-with-grace-period](#35-gdpr-data-export--delete-with-grace-period)
   - 3.6 [OpenAPI / Swagger spec](#36-openapi--swagger-spec)
4. [P3 — Scale-out & polish](#p3--scale-out--polish)
   - 4.1 [SSE reconnect-resume (Last-Event-ID)](#41-sse-reconnect-resume-last-event-id)
   - 4.2 [Celebrity-push fan-out](#42-celebrity-push-fan-out)
   - 4.3 [CDN edge-caching strategy](#43-cdn-edge-caching-strategy)
   - 4.4 [Outbound webhooks](#44-outbound-webhooks)
   - 4.5 [Admin dashboard consolidation](#45-admin-dashboard-consolidation)
   - 4.6 [Audit log surface](#46-audit-log-surface)
5. [Recommended shipping order](#recommended-shipping-order)
6. [Cross-cutting principles](#cross-cutting-principles)

---

## P0 — Product/market fit blockers

These are the features that, if missing, make IRC feel half-built next
to a mature social platform.

### 1.1 Direct Messages

> **Status:** completely missing.
> **Why P0:** most users on a social platform spend more time in DMs
> than feeds. Stories already have `CLOSE_FRIENDS` visibility implying a
> private layer but there's no channel.

#### Surface (MVP)

```
POST   /api/v1/dms/conversations                          create / find DM thread with userId
GET    /api/v1/dms/conversations?cursor=&pageSize=        list my threads (newest activity first)
GET    /api/v1/dms/conversations/{id}                     thread details + members
GET    /api/v1/dms/conversations/{id}/messages?cursor=    page messages
POST   /api/v1/dms/conversations/{id}/messages            send text + media + voice
PATCH  /api/v1/dms/messages/{id}                          edit (15-min window)
DELETE /api/v1/dms/messages/{id}                          delete-for-everyone
POST   /api/v1/dms/conversations/{id}/typing              typing indicator (Redis 5s TTL)
POST   /api/v1/dms/conversations/{id}/read                mark-read up to messageId
GET    /api/v1/dms/stream                                 per-user SSE for every thread
GET    /api/v1/dms/unread/count                           total unread DMs across threads
```

#### Cassandra schema

| Table | PK / clustering | Purpose |
|-------|-----------------|---------|
| `conversations_by_user` | `(user_id) (last_activity_at DESC, conversation_id)` | My thread list — single partition scan |
| `messages_by_conversation` | `(conversation_id) (sent_at DESC, message_id)` | Page through a thread |
| `dm_members_by_conversation` | `(conversation_id) (user_id)` | Group membership; 1-to-1 = exactly 2 rows |
| `dm_read_cursors` | `(conversation_id, user_id)` | Last message id the user has read |
| `dm_unread_counter` | `(user_id)` (counter table) | Cross-thread unread total |
| `dm_lookup_pair` | `((min_user_id, max_user_id))` | "Find 1:1 thread with U" — point lookup |

Group DMs add a `role` column (`ADMIN` / `MEMBER`) on `dm_members_by_conversation`.

#### Infrastructure re-use

- **Realtime:** Redis pub/sub `irc:dm:{userId}` — same pattern as the
  notification stream (`NotificationRedisPublisher`).
- **Attachments:** R2 via the existing `S3StorageService.upload(file, "dm/media")`.
- **Notifications:** route DM-receive events through `CassandraNotificationService`
  with a new `DM_RECEIVED` `NotificationKind` (aggregable per
  `DM_RECEIVED:{conversationId}`, no email — too noisy).
- **Activity:** add a `DM_SENT` / `DM_RECEIVED` activity type, OR skip
  for privacy (most platforms don't log DMs in the activity feed —
  recommend skip).

#### Estimated effort

1–2 weeks for the MVP (1:1 messaging only), +1 week for groups, +1 week
for read-receipts / typing / reactions on messages.

---

### 1.2 Push Notifications

> **Status:** missing. Today: email + in-app SSE only.
> **Why P0:** without push, mobile users with the app closed get zero
> notifications.

#### Schema

```sql
CREATE TABLE user_devices (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL,
    device_id       VARCHAR(255) NOT NULL,           -- client-generated, stable per install
    platform        VARCHAR(20) NOT NULL,            -- IOS | ANDROID | WEB_PUSH
    fcm_token       VARCHAR(4096) NOT NULL,          -- FCM device token (also used for APNs proxy)
    voip_token      VARCHAR(4096),                   -- iOS-only, for incoming call notifications later
    app_version     VARCHAR(50),
    os_version      VARCHAR(50),
    last_seen_at    TIMESTAMP,
    created_at      TIMESTAMP,
    UNIQUE (user_id, device_id)
);
```

#### Endpoints

```
POST   /api/v1/devices             { deviceId, platform, fcmToken, voipToken?, appVersion?, osVersion? }
GET    /api/v1/devices             list my registered devices
DELETE /api/v1/devices/{id}        unregister on logout
DELETE /api/v1/devices             unregister ALL on logout-all
PATCH  /api/v1/devices/{id}        refresh fcmToken (frontend calls when FCM rotates the token)
```

#### Wiring into the notification pipeline

Add a `PushService.deliver(userId, notification)` step inside
`CassandraNotificationService.deliverSync(...)`, right after the in-app
row write. Honor the same gating model as email:

| User flag | Effect |
|-----------|--------|
| `pushNotificationsEnabled` | Master kill-switch |
| `pushSocialEnabled` | Like / comment / follow / reaction kinds |
| `pushMentionsEnabled` | `USER_MENTIONED` |
| `pushSystemEnabled` | Sound approved / account warnings / announcements |

Add these four columns to the `users` table (mirror the existing `email*Enabled` quad).

Aggregation: skip the very-noisy kinds (`POST_NEW` feed fan-out — already
in-app-only — stays in-app-only).

---

### 1.3 Trending feed

> **Status:** missing.
> **Why P0:** cold-start users with empty home feeds need somewhere to
> discover content. The existing search is intent-driven; trending is
> ambient.

#### Endpoints

```
GET /api/v1/trending/posts?window=24h|7d|30d&pageSize=20
GET /api/v1/trending/hashtags?window=24h|7d|30d&limit=20
GET /api/v1/trending/sounds?window=24h|7d|30d&limit=20
GET /api/v1/trending/creators?window=24h|7d|30d&limit=20
```

#### Storage

```cql
CREATE TABLE trending_posts_by_window (
    window         TEXT,                -- "24h" | "7d" | "30d"
    score          DOUBLE,
    post_id        UUID,
    PRIMARY KEY (window, score, post_id)
) WITH CLUSTERING ORDER BY (score DESC, post_id ASC);
```

Same shape for hashtags / sounds / creators with their respective ids.

#### Scoring

`TrendingScorer` `@Scheduled(fixedDelay = 10min)` job:

```
score = (reactionCount + 2*commentCount + 0.5*viewCount + 3*shareCount)
        * exp(-ageHours / halfLifeHours)
```

| Window | `halfLifeHours` |
|--------|------------------|
| `24h`  | 8 |
| `7d`   | 48 |
| `30d`  | 168 |

The job:
1. Reads `post_counters` rows touched in the last `window`.
2. Filters out `REMOVED` / `ARCHIVED` posts.
3. Computes the score, picks top 200 per window.
4. Truncates + rewrites `trending_posts_by_window` for that window.

Counter reads are point-reads on the partition key; the full job is
under 30 seconds for 1M tracked posts.

---

### 1.4 Algorithmic For-You feed

> **Status:** missing — only chronological fanout-on-write today.
> **Why P0:** engagement plateaus without re-ranking on big follow graphs.

#### MVP (no ML)

Heuristic re-rank of the existing `feed_by_user` candidates by:

```
score = recencyFactor × engagementFactor × affinityFactor
```

Where:

- `recencyFactor = exp(-ageHours / 24)` — half-life of one day.
- `engagementFactor = log(reactionCount + commentCount + 1)` from `post_counters`.
- `affinityFactor` = number of recent interactions between viewer and
  author over the last 30 days (mined from `reactions_by_user` /
  `views_by_post` / `activity_by_user`). Clamped to `[1, 10]`.

#### Endpoint

```
GET /api/v1/posts/feed/for-you?pageSize=20&cursor=<opaque>
```

The cursor is opaque (a stamped JSON blob containing `{ lastScore,
lastCreatedAt }`) — the next page picks up below the last score.

#### Frontend toggle

Frontend toggles between **Following** (chronological — existing
`/api/v1/posts/feed`) and **For You** (ranked — new endpoint), like
Twitter/Threads.

#### Later — ML model

Don't ship a model until you have data. The MVP heuristic above gives
~80% of the value at <5% of the cost.

---

### 1.5 Moderation & Reporting

> **Status:** `PostStatus.REMOVED` exists in the enum; no user-facing
> report flow, no admin triage queue.
> **Why P0:** safety. Mandatory before public beta.

#### User-facing

```
POST /api/v1/reports
{
  "resourceType": "POST" | "COMMENT" | "USER" | "STORY" | "RESEARCH" | "QNA_ANSWER",
  "resourceId":   "<uuid>",
  "reason":       "SPAM" | "HARASSMENT" | "HATE" | "IMPERSONATION" | "NUDITY" | "VIOLENCE" | "OTHER",
  "notes":        "..."   // ≤ 1000 chars, optional
}
```

#### Admin-facing

```
GET  /api/v1/admin/reports?status=PENDING|RESOLVED&page=&size=
GET  /api/v1/admin/reports/{id}
POST /api/v1/admin/reports/{id}/resolve
{
  "action": "REMOVE_CONTENT" | "WARN_USER" | "SUSPEND_USER" | "DISMISS",
  "note":   "..."
}

POST /api/v1/admin/posts/{id}/remove          flip status=REMOVED + audit
POST /api/v1/admin/users/{id}/suspend         { reason, until: ISO?, permanent: bool }
```

#### Storage

```sql
CREATE TABLE content_reports (
    id             UUID PRIMARY KEY,
    reporter_id    UUID NOT NULL,
    resource_type  VARCHAR(40),
    resource_id    UUID NOT NULL,
    reason         VARCHAR(40),
    notes          TEXT,
    status         VARCHAR(20),       -- PENDING | UNDER_REVIEW | RESOLVED
    resolved_by    UUID,
    resolved_at    TIMESTAMP,
    action_taken   VARCHAR(40),
    created_at     TIMESTAMP NOT NULL,
    INDEX idx_reports_status (status, created_at)
);
```

JPA (Postgres) — rare-write, read-mostly. No need for Cassandra here.

#### Auto-moderation hooks

- Reports auto-promote a resource into `UNDER_REVIEW` once `≥ 3` distinct
  reports land on the same resource within 24h.
- Notification `ACCOUNT_WARNING` already exists for warning users.

---

## P1 — High value, scoped delivery

### 2.1 Drafts + scheduled posts

> **Status:** `PostStatus.DRAFT` is in the enum but no endpoint exposes
> it. Stories have `scheduledPublishAt`, posts don't.

#### Endpoints

```
POST  /api/v1/posts/drafts                   { ...CreatePostCommand } (JSON or multipart)
GET   /api/v1/posts/drafts?page=&size=       my drafts
PATCH /api/v1/posts/drafts/{id}              partial-update (same body as PATCH /posts/{id})
POST  /api/v1/posts/drafts/{id}/publish      publish now
POST  /api/v1/posts/drafts/{id}/schedule     { publishAt: ISO }
POST  /api/v1/posts/drafts/{id}/unschedule   cancel a scheduled publish
DELETE /api/v1/posts/drafts/{id}             remove draft
```

#### Schema changes

- Add `scheduled_publish_at` column to `posts_by_id`.
- Drafts live in `posts_by_id` with `status='DRAFT'`. Every feed / search
  query already filters by `status='PUBLISHED'` (or should — verify).

#### Scheduler

`PostScheduleJob` `@Scheduled(fixedDelay = 60s)`:

```java
posts_by_id where status='DRAFT' and scheduled_publish_at <= now
  → flip status='PUBLISHED', publishedAt=now
  → re-run the createPost fan-out (feed, reels-by-day, ES index, sounds adoption, hashtags)
  → fire notifications same as live publish
```

Mirror what `ResearchService.processScheduledPublications` already does.

---

### 2.2 Quote-repost

> **Status:** schema supports it (`textContent` + `sharedPostId`) but
> feeds don't render it well because `sharedPostId` isn't embedded in
> `FeedItemResponse`.

This pairs with the **frontend P0 #1 + #18 enhancements**: embed a
shallow `sharedPost` on `FeedItemResponse` and `PostResponse`. Once
done, quote-repost is just a post with both `textContent` AND
`sharedPostId` set — the frontend renders it as a card with the
original embedded.

No new endpoint needed; just the DTO change.

---

### 2.3 Follow hashtags

> **Status:** missing.
> Today: users can search `#x` but they can't subscribe to it.

#### Schema

```cql
CREATE TABLE hashtag_follows_by_user (
    user_id      UUID,
    hashtag      TEXT,
    followed_at  TIMESTAMP,
    PRIMARY KEY (user_id, hashtag)
);

CREATE TABLE hashtag_followers_by_tag (
    hashtag      TEXT,
    user_id      UUID,
    followed_at  TIMESTAMP,
    PRIMARY KEY (hashtag, user_id)
);
```

#### Endpoints

```
POST   /api/v1/hashtags/{tag}/follow
DELETE /api/v1/hashtags/{tag}/follow
GET    /api/v1/users/me/hashtags                  list my followed tags
GET    /api/v1/hashtags/{tag}/followers?pageSize=  who follows this tag
```

#### Feed wiring

When fanning out a post that includes `#x`, ALSO fan out into
`feed_by_user` for everyone in `hashtag_followers_by_tag[x]`, capped at
the same `MAX_FANOUT_FOLLOWERS = 50_000` ceiling.

---

### 2.4 Pinned posts

> **Status:** missing.
> Profile shows posts chronologically; popular profiles want to pin a
> specific post to the top.

- Add `pinned_post_id` column to `UserProfile` (Postgres).
- `POST /api/v1/users/me/profile/pinned-post/{postId}` — set.
- `DELETE /api/v1/users/me/profile/pinned-post` — clear.
- Constraint: the post must be owned by the user and not deleted.
- `ProfileResponse` carries `pinnedPost: PostSummary` (shallow).
- Profile-feed query prepends the pinned post (if any) to page 1.

---

### 2.5 Stories — finish the surface

> **Status:** SSE infra exists in code (`StoryRealtimeService`,
> `StoryTrayRealtimeService`, broadcaster/publisher) but no controller
> endpoints. Reactions and replies don't exist at all.

#### Endpoints to add

```
GET  /api/v1/stories/tray                          followee stories grouped by author
GET  /api/v1/stories/tray/stream                   SSE for "new story posted by followee"
GET  /api/v1/stories/{storyId}/stream              SSE — view/reaction/reply count live
POST /api/v1/stories/{storyId}/reactions           single LIKE per project rule
POST /api/v1/stories/{storyId}/replies             DM-style reply
GET  /api/v1/stories/{storyId}/replies             author-only — list replies received
```

#### Schema additions

```cql
CREATE TABLE story_reactions_by_story (
    story_id   UUID,
    user_id    UUID,
    created_at TIMESTAMP,
    PRIMARY KEY (story_id, user_id)
) WITH default_time_to_live = 86400;            -- 24h, matches story TTL

CREATE TABLE story_replies_by_story (
    story_id    UUID,
    sent_at     TIMESTAMP,
    reply_id    UUID,
    sender_id   UUID,
    text        TEXT,
    PRIMARY KEY (story_id, sent_at, reply_id)
) WITH CLUSTERING ORDER BY (sent_at DESC, reply_id ASC)
  AND default_time_to_live = 86400;

ALTER TABLE story_counters ADD reaction_count COUNTER;   -- new counter
ALTER TABLE story_counters ADD reply_count COUNTER;
```

#### Notification wiring

`STORY_REACTED` and `STORY_REPLIED` notification kinds already exist
in `NotificationKind`. Just fire them from the new endpoints with
group-keys `STORY_REACTED:{storyId}` and `STORY_REPLIED:{storyId}`.

---

### 2.6 Sounds — search + trending + favorites

#### Endpoints to add

```
GET  /api/v1/sounds/search?q=...                  ES full-text on title + artistName
GET  /api/v1/sounds/trending?window=24h|7d&limit=20
POST /api/v1/sounds/{id}/save                     bookmark a sound
DELETE /api/v1/sounds/{id}/save
GET  /api/v1/users/me/sounds/saved?pageSize=      saved sounds list
```

#### Storage

- Index `SoundEntity` in ES (`title` + `artistName` text, `category` keyword).
- Re-use the trending pattern from §1.3.
- `sound_saves_by_user` Cassandra table for favorites.

---

### 2.7 Image / video processing pipeline

> **Status:** uploads go straight to R2 as-is. No transforms.

#### What to add (async)

| Source | Transform |
|--------|-----------|
| `image/*` | thumbnail variants (200px, 800px, 1600px) → save as `<key>-thumb-{size}.jpg`. Strip EXIF (PII risk). Detect dominant color → store on `media_by_post.dominantColor` for skeleton placeholders. |
| `video/*` | extract poster frame (1s mark) → upload as `<key>-poster.jpg`. Already extracting duration with `mp4parser`. For reels > 30s, queue HLS transcoding (out-of-process worker). |
| Any | optional NSFW classification — flag for moderation if score > threshold. |

#### Storage

Add to `media_by_post`:

```cql
ALTER TABLE media_by_post ADD processed_status TEXT;     -- PENDING | READY | FAILED
ALTER TABLE media_by_post ADD dominant_color TEXT;
ALTER TABLE media_by_post ADD thumbnail_variants MAP<INT, TEXT>;  -- size → url
ALTER TABLE media_by_post ADD nsfw_score FLOAT;
```

Frontend shows skeleton + dominant color until `processed_status = READY`.

#### Worker

Spring `@Async` for the cheap transforms (thumbnails, EXIF strip).
Hand off transcoding to a dedicated worker via RabbitMQ
(`media-jobs` queue) so the web tier doesn't block.

---

### 2.8 Counter reconciliation job

> **Status:** mentioned in comments throughout the codebase ("a periodic
> reconciler sweeps drift") but never seen wired.
> **Risk:** Cassandra counter drift accumulates. `reactionCount`
> displayed on the post slowly diverges from the actual row count in
> `reactions_by_post`.

#### `CounterReconcileJob`

```
@Scheduled(cron = "0 0 4 * * *")           # 4 AM nightly, off-peak
```

For each post touched in the last 24h:
1. `SELECT COUNT(*) FROM reactions_by_post WHERE post_id = ?`
2. `SELECT reaction_count FROM post_counters WHERE post_id = ?`
3. If `|delta| > 0`, issue `UPDATE post_counters SET reaction_count = reaction_count + <delta>`.

Same shape for `commentCount` (count `comments_by_post` + `replies_by_comment`), `saveCount` (count `saves_by_post_user`), `shareCount` (count `shares_by_post`), `viewCount` (count `views_by_post`).

**Bounded:** process at most 100k posts per night. Pick the hottest first
(those with `updated_at > now - 24h`).

Same job covers comment counters (`reaction_count`, `reply_count`).

---

### 2.9 Post archive

> **Status:** `PostStatus.ARCHIVED` exists in the enum; no endpoint.

```
POST   /api/v1/posts/{id}/archive       move to ARCHIVED — hidden from feeds, kept by URL
DELETE /api/v1/posts/{id}/archive       restore to PUBLISHED
GET    /api/v1/users/me/posts/archived  list my archived posts (paginated)
```

Archived posts:
- Hidden from `home/feed`, `profile/by-author`, `reels`, ES search.
- Still resolvable by direct URL (`/posts/{id}` returns the post with `status=ARCHIVED`).
- Counters frozen (no new reactions/comments — enforced by service guard).

---

## P2 — Medium scope tools

### 3.1 Post analytics / insights

```
GET /api/v1/posts/{id}/insights?from=&to=
```

Author-only. Returns:

```json
{
  "post":           { "id": "...", "createdAt": "..." },
  "totalViews":     12345,
  "uniqueViewers":  9876,
  "viewsByDay":     [{ "day": "2026-05-15", "count": 234 }, ...],
  "engagementByDay":[{ "day": "...", "reactions": ..., "comments": ..., "shares": ..., "saves": ... }],
  "topReferrers":   { "HOME_FEED": 4000, "PROFILE": 1200, "SEARCH": 800, "HASHTAG": 500, "DIRECT": 7845 },
  "viewerDemographics": { "byLanguage": {...}, "byCountry": {...} }   // only when viewers opted in
}
```

Backing tables:

```cql
CREATE TABLE post_views_by_day (
    post_id  UUID,
    day      DATE,
    PRIMARY KEY (post_id, day)
);
-- counter cols: view_count, unique_viewer_count

CREATE TABLE post_engagement_by_day (
    post_id  UUID,
    day      DATE,
    PRIMARY KEY (post_id, day)
);
-- counter cols: reactions, comments, shares, saves
```

Populated by a daily `@Scheduled` job that aggregates the raw event
tables.

---

### 3.2 Saved-collections management

> **Status:** post saves carry `collection_name`; no endpoints to list /
> rename / delete collections (QnA + Research have rename, posts don't).

```
GET    /api/v1/users/me/saves/collections                    list { name, count }[]
PATCH  /api/v1/users/me/saves/collections?oldName=&newName=  rename across every save row
DELETE /api/v1/users/me/saves/collections?name=              remove collection (rows revert to "Default")
```

Backing storage:

```cql
CREATE TABLE saves_collections_by_user (
    user_id          UUID,
    collection_name  TEXT,
    save_count       COUNTER,
    PRIMARY KEY (user_id, collection_name)
);
```

Increment / decrement on save / unsave + rename.

---

### 3.3 Two-factor auth endpoints

> **Status:** schema fields (`twoFactorEnabled`, `twoFactorSecret`) exist
> on `User`; `VerificationToken.TWO_FACTOR_OTP` exists in the enum; no
> endpoints.

```
POST /api/v1/auth/2fa/enroll        returns provisioning URI + recovery codes
POST /api/v1/auth/2fa/verify        confirm with first TOTP code, sets enabled=true
POST /api/v1/auth/2fa/disable       requires current password + TOTP code
GET  /api/v1/auth/2fa/recovery-codes regenerate (one-shot 10 codes)
```

`POST /api/v1/auth/login` needs to accept an additional `code` field
when the target user has `twoFactorEnabled = true`. If `code` is missing
or wrong, return `401 AUTH_2FA_REQUIRED`. Frontend then shows the OTP prompt.

---

### 3.4 Rate limiting wired into post endpoints

> **Status:** `RateLimiter` exists with pre-tuned bursts; not called by
> the post controllers today.

Add one-line `rateLimiter.checkXxx(userId)` calls at:

| Endpoint | Limiter | Burst |
|----------|---------|-------|
| `POST /posts` (JSON or multipart) | `checkSocial` | 30 / 60s |
| `POST /posts/{id}/reactions` | `checkReaction` | 30 / 10s |
| `POST /posts/{id}/comments` | `checkComment` | 10 / 30s |
| `POST /posts/comments/{id}/replies` | `checkComment` | 10 / 30s |
| `POST /posts/{id}/saves` | `checkSocial` | 30 / 60s |
| `POST /posts/{id}/shares` | `checkSocial` | 30 / 60s |
| `POST /posts/{id}/comments/{id}/reactions` | `checkReaction` | 30 / 10s |
| `POST /stories` | new `checkStoryCreate` | 5 / 60s |
| `POST /polls/{id}/vote` | `checkReaction` | 30 / 10s |

Returns `429 RATE_LIMITED` per the unified error catalogue.

---

### 3.5 GDPR data export + delete-with-grace-period

#### Export

```
POST /api/v1/users/me/data-export     enqueue
GET  /api/v1/users/me/data-export     status + download URL
```

Worker walks every user-owned row across:
- Postgres (`User`, `UserProfile`, `Notification`, JPA entities)
- Cassandra (posts, comments, reactions, saves, shares, views, stories,
  highlights, DMs, activity, mentions)
- R2 (media files manifest)

Packages as ZIP (JSON files + linked media URLs), uploads to R2, signed
URL expires in 7 days. Sends `SYSTEM_MESSAGE` notification when ready.

#### Account delete with grace period

Today: `DELETE /users/me` flips `deletedAt`. Add a 30-day grace period:

```
DELETE /api/v1/users/me                  schedule purge (deletedAt = now)
POST   /api/v1/users/me/restore          undo, if within 30 days
```

`UserPurgeJob` `@Scheduled(cron = "0 0 5 * * *")` hard-deletes any user
where `deletedAt < now - 30d`. Cascades to every row owned by the user.

---

### 3.6 OpenAPI / Swagger spec

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.6.0</version>
</dependency>
```

Mount at `/swagger-ui.html` + `/v3/api-docs`. Annotate `@Operation` /
`@ApiResponse` over time — start with the post / qna / research
controllers since they have the most endpoints.

The frontend stops asking "what does this endpoint return?" — they read
the auto-generated spec.

---

## P3 — Scale-out & polish

### 4.1 SSE reconnect-resume (Last-Event-ID)

`EventSource` auto-reconnects, but the server doesn't replay events the
client missed during disconnect. Today the client backfills via the
REST list endpoint — fine, slightly stale.

**Add:** every SSE event includes `id: <activity-uuid>`. On reconnect
the browser sends `Last-Event-ID` header. Server reads it and replays
every activity row since that id (capped at 100) before continuing live.

---

### 4.2 Celebrity-push fan-out

`FeedTimelineService` already has a TODO comment for this. Beyond 1M
followers, fanout-on-write hits limits.

- Add `celebrity_posts` table (partition per creator, cluster by
  `created_at DESC`).
- `homeFeed(userId)` merges normal `feed_by_user` rows with the most
  recent N rows from each celebrity the user follows.
- Toggle creator into "celebrity mode" once they cross a configurable
  follower threshold (default 1M). Migration is online — historical
  fanout rows TTL out at 30 days.

---

### 4.3 CDN edge-caching strategy

- Wrap `R2StorageService.publicUrl(key)` to return a CDN domain in prod
  (Bunny / Cloudflare CDN / R2 Public).
- Set `Cache-Control: public, max-age=31536000` on R2 upload metadata
  (everything cacheable forever — keys are unique).
- Thumbnails use a predictable path scheme: `posts/media/<id>/thumb-200.jpg`
  so CDNs can layer rules cleanly.
- For SSE (streams): set `Cache-Control: no-cache, no-store` (already
  done in the SSE controllers).

---

### 4.4 Outbound webhooks

```
POST   /api/v1/webhooks       { url, secret, events: [...] }
GET    /api/v1/webhooks
DELETE /api/v1/webhooks/{id}
```

Events the platform fires:
`POST_PUBLISHED` · `RESEARCH_PUBLISHED` · `RESEARCH_CITED` ·
`QUESTION_ANSWERED` · `USER_REGISTERED` · `SCHOLAR_APPROVED`.

Payload signed with HMAC-SHA256 using the per-subscription secret in
header `X-IRC-Signature`. Retry with exponential backoff up to 24h on
non-2xx. Dead-letter to `webhook_failures` after 10 retries.

---

### 4.5 Admin dashboard consolidation

Scattered today:
- `/api/v1/admin/users/{id}/account-type`
- `/api/v1/admin/verification/queue`
- (planned) `/api/v1/admin/reports`

Consolidate under `/api/v1/admin/...` and add:

```
GET /api/v1/admin/posts?status=&author=&from=&to=&page=&size=
GET /api/v1/admin/users?suspended=&role=&page=&size=
GET /api/v1/admin/metrics                            DAU, posts/day, reports/day, sign-ups/day
```

---

### 4.6 Audit log surface

`BaseAuditEntity` stamps every JPA entity write but the audit trail is
not surfaced.

```
GET /api/v1/admin/audit?resourceType=Post&resourceId=...   timeline for one resource
GET /api/v1/admin/audit?userId=...&from=&to=               everything done by a user
```

---

## Recommended shipping order

For social-media (post) emphasis:

| # | Item | Why first | Rough effort |
|---|------|-----------|--------------|
| 1 | **§1.1 DMs** | Single biggest missing feature. Without DMs IRC feels half-built. | 2–3 weeks |
| 2 | **§1.2 Push notifications** | Engagement collapses on mobile without push. Pipeline already exists. | 1 week |
| 3 | **§1.3 Trending + §1.4 For-You** | Cold-start discovery + engagement boost. Pair them. | 2 weeks |
| 4 | **§2.5 Stories — finish** | Closes a partly-shipped surface you already designed for. | 1 week |
| 5 | **§2.2 Quote-repost** + frontend P0 #1/#18 | One PR — also kills the frontend's N+1 reposts hydration. | 3 days |
| 6 | **§2.1 Drafts + scheduled posts** | Creator quality-of-life. Schema almost ready. | 1 week |
| 7 | **§1.5 Moderation + reports** | Has to ship before public beta. | 1–2 weeks |
| 8 | **§2.8 Counter reconciler** | Operational hygiene. Will save you a "why is my count off?" Slack thread per month. | 2 days |
| 9 | **§2.4 Pinned posts** + **§2.6 Sounds discovery** | Small, popular. | 2–3 days each |
| 10 | **§3.4 Rate limiter wiring** | Cheap, defensive. | 1 day |

Beyond #10 are improvements you ship after the platform proves traction.

The **biggest single win** is **DMs** — most users on a social platform
spend more time in DMs than feeds, and you have none.

---

## Cross-cutting principles

Apply these to every new feature, not just the ones above:

| Principle | Reason |
|-----------|--------|
| **Async-first** | Side effects (notifications, activity, ES indexing) go through `@Async` / RabbitMQ — the originating request returns immediately. |
| **Try/catch around side effects** | Recording an activity / firing a notification must NEVER break the originating write. |
| **Cassandra: query-driven schema** | Every read pattern gets its own denormalised table. No `ALLOW FILTERING`. No secondary indexes on hot columns. |
| **JPA: counter columns via atomic JPQL** | `UPDATE … SET col = col + N` — never entity setter + save (racy). |
| **Counter cache layer** | `CounterCache` in Redis mirrors denormalised counters so reads stay sub-millisecond. |
| **Block-aware reads** | Every feed / list endpoint respects `UserBlock` (both directions). |
| **Soft delete with `deletedAt`** | Hard deletes are reserved for explicit admin paths. |
| **Realtime via Redis pub/sub** | Cross-instance fan-out. Per-resource topic. SSE for the last mile. |
| **Notifications coalesce** | Same `(userId, groupKey)` unread rows merge inside a 60-min window. |
| **`afterCommit` event publishing** | Outbound events (RabbitMQ / Redis) fire only after the DB transaction is durable. |
| **Single reaction type** | All entities (post/research/qna) use one reaction = `LIKE`. "Academic not entertainment." |
| **Replies flat at depth 1** | Server hoists deeper attempts. Applies to post comments and QnA reanswers. |
| **JWT-derived authorship** | Every create endpoint reads `authorId` from the JWT principal — body-supplied ids are ignored. |
| **R2 rollback on failed multipart** | If the DB write fails after R2 upload, every R2 key is best-effort deleted to avoid orphans. |
| **`ApiErrorResponse` everywhere** | One unified error shape across the platform — see `POST_ERRORS.md`. |

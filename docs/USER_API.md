# User Package — Full API Documentation

This is the complete reference for everything under `ak.dev.irc.app.user`
— authentication, identity, profile, social graph, scholar verification,
admin account management, close friends, and the **notification inbox &
SSE stream** that all other modules feed into.

It covers:

- [Domain model overview](#1-domain-model-overview)
- [Authentication](#2-authentication)
- [User identity (CRUD)](#3-user-identity-crud)
- [Public & private profile](#4-profile)
- [Links & contacts](#5-links--contacts)
- [Avatars & cover images](#6-avatars--cover-images)
- [Specializations](#7-specializations)
- [Social graph (follow / block / restrict)](#8-social-graph)
- [Close friends](#9-close-friends)
- [Scholar verification (admin-only)](#10-scholar-verification)
- [Admin: change account type](#11-admin-change-account-type)
- [Account deletion](#12-account-deletion)
- [Notifications — inbox & SSE](#13-notifications)
- [Badges](#14-badges)
- [Enums](#15-enums)
- [JPA entities](#16-jpa-entities)
- [DTOs](#17-dtos)
- [Realtime architecture](#18-realtime-architecture)

All endpoints live under `/api/v1/...`. The authenticated user is extracted
from the JWT principal. Tokens are delivered as HttpOnly cookies AND in the
JSON response body — browser clients use the cookies, mobile / API clients
use the body.

---

## 1. Domain model overview

The user module is the platform's identity & social layer. The hierarchy is:

```
User (auth-layer identity, role, accountType, verificationTier)
 ├── UserProfile (1-to-1, public-facing fields)
 │    ├── UserLink            (FACEBOOK, TWITTER, ORCID, ...)
 │    ├── UserContact         (TELEGRAM, WHATSAPP, EMAIL, ...)
 │    ├── UserAttachment      (uploaded credentials / docs)
 │    └── UserTopicSpecialization (links to knowledge Topic)
 ├── UserAuthority            (Spring Security authority strings)
 ├── RefreshToken             (per device)
 ├── VerificationToken        (EMAIL_VERIFY / PASSWORD_RESET / TWO_FACTOR_OTP)
 ├── ScholarVerification      (legacy application — admin reviews)
 └── Notification             (per-user inbox row; cross-domain types)

Social-graph join tables:
 - UserFollow      (follower → following)
 - UserBlock       (blocker → blocked, bidirectional checks)
 - UserRestriction (restrictor → restricted)
 - CloseFriendsList (owner → friend)
```

Key design rules:

- **Auth vs profile split** — `User` carries auth-layer identity
  (`fname`/`lname`/`username`/`email`/`password`/`role`/`accountType`/
  `verificationTier`). `UserProfile` carries public-facing fields
  (display name, bio, avatar, location, counters). One row each, 1-to-1.
- **`AccountType` + `VerificationTier`** drive the **badge system** —
  these are the two inputs to `BadgeType`.
- **Account-type changes go through admin only** — no self-service
  promotion; `/api/v1/admin/users/{id}/account-type` is the only path.
  The legacy `ScholarVerification` application table still exists for
  closing out applications submitted before the policy change.
- **Notifications are cross-domain** — every other module
  (post / qna / research / system) writes into the user-package
  `Notification` model. Listing / mark-read / delete / SSE endpoints all
  live under `/api/v1/notifications`.
- **Coalescing notifications** — same `(userId, groupKey)` unread rows are
  merged into one row (`aggregateCount` bumps, `lastActor` replaces the
  primary actor, `createdAt` is refreshed so the row floats to the top of
  the inbox).
- **Cross-instance SSE** — Redis pub/sub on `irc:notifications:{userId}`
  fans out to every open tab on every running instance.
- **`afterCommit` push** — `@TransactionalEventListener(AFTER_COMMIT)`
  bridges JPA → SSE, so a rolled-back transaction never produces a push.
- **Two-factor TOTP** — `User.twoFactorEnabled` + `twoFactorSecret`;
  `VerificationToken.TWO_FACTOR_OTP` for one-time codes (10-min TTL).
- **Daily inbox prune** — `NotificationCleanupJob` runs at 03:15 local,
  deletes read notifications older than 90 days; unread rows are never
  touched.

---

## 2. Authentication

Base path: `/api/v1/auth`.

Tokens are delivered on TWO channels:

- **HttpOnly cookies** — browser clients.
- **JSON response body** — mobile / API clients use the `accessToken` as a
  `Bearer` token.

There is intentionally **NO** forgot-password / password-reset-token flow.
The only path to rotate a password is the authenticated `/change-password`
endpoint.

### Endpoints

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| `POST` | `/register` | public | Create an account. Body: `RegisterRequest`. Returns `201 AuthResponse` (tokens + user). |
| `POST` | `/login` | public | Authenticate. Body: `LoginRequest` (`username` accepts username OR email). Returns `AuthResponse`. |
| `POST` | `/refresh` | public (token-based) | Exchange refresh for a new pair. Body or cookie. Returns `AuthResponse` (no user payload). |
| `POST` | `/logout` | auth | Revoke the supplied refresh token + clear cookies. |
| `POST` | `/logout-all` | auth | Revoke every refresh token on the user + clear cookies. |
| `POST` | `/change-password` | auth | Re-verifies `currentPassword`, hashes & saves `newPassword`, revokes every refresh token (including the caller's old one), then issues a fresh pair. Keeps THIS device logged in; forces every other device to log in again. |

### `AuthResponse`

```java
{
  "accessToken":  "...jwt...",
  "refreshToken": "...jwt...",
  "tokenType":    "Bearer",
  "expiresIn":    900,             // access token TTL in seconds
  "user":         UserResponse     // null on /refresh
}
```

### Validation

- `username`: 3–50 chars, only `[a-zA-Z0-9._-]`.
- `email`: valid email, unique.
- `password`: 8–128 chars (no other complexity rule at validation layer).
- `fname` / `lname`: 1–80 chars.

### Verification tokens (`TokenType`)

`VerificationToken` rows back three flows — TTLs are application policy,
not enforced at validation:

| `TokenType` | TTL | Used for |
|-------------|-----|----------|
| `EMAIL_VERIFY`    | 24h | Email confirmation on register |
| `PASSWORD_RESET`  | 1h  | Legacy — kept on the enum but not exposed by `AuthController` |
| `TWO_FACTOR_OTP`  | 10min | TOTP code delivery |

---

## 3. User identity (CRUD)

Base path: `/api/v1/users`.

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| `GET`    | `/me` | auth | Get the authenticated user's `UserResponse` (with embedded `profile`). |
| `GET`    | `/{id}` | public | Get user by UUID. |
| `GET`    | `/username/{username}` | public | Get by username. |
| `GET`    | `/email/{email}` | public | Get by email. |
| `PATCH`  | `/me` | auth | Auth-layer identity update — only `fname`, `lname`, `username`. Body: `UpdateProfileRequest`. |
| `GET`    | `/search?q=...&page=&size=` | public | Search by name / username substring. |
| `DELETE` | `/me` | auth | Soft-delete the account (sets `deletedAt`). |

`UpdateProfileRequest` (auth-layer only):

```json
{ "fname": "...",  "lname": "...",
  "username": "..." }    // [a-zA-Z0-9._-] only, 3..50
```

---

## 4. Profile

Base path: `/api/v1/users` (read), `/api/v1/users/me/profile` (write).

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| `GET`    | `/{id}/profile` | public | Public profile of any user (`UserResponse` with `profile`). |
| `GET`    | `/me/profile` | auth | Caller's own profile. |
| `PATCH`  | `/me/profile` | auth | Update profile fields. Body: `UpdateUserProfileRequest`. |

`UpdateUserProfileRequest` (every field optional — null = leave untouched):

```json
{
  "displayName":     "...",   // ≤120
  "profileBio":      "...",
  "selfDescriber":   "...",   // ≤200 tagline
  "location":        "...",   // ≤200
  "academicTitle":   "...",   // ≤150
  "institutionName": "...",   // ≤200
  "madhhabId":       1,
  "websiteUrl":      "...",
  "isForHire":       true,
  "isProfileLocked": false,
  "contentLanguage": "EN"
}
```

`UserProfile` also tracks denormalised counters maintained by
`CounterCache`: `followerCount`, `followingCount`, `researchCount`,
`fatwaCount`, `profileViews`.

---

## 5. Links & contacts

Both live under the profile but the controller routes them off
`/api/v1/users/me/...` for self-management.

### Links — `LinkPlatform` enum

`FACEBOOK`, `TWITTER`, `INSTAGRAM`, `LINKEDIN`, `YOUTUBE`, `GITHUB`,
`ORCID`, `RESEARCHGATE`, `GOOGLE_SCHOLAR`, `TELEGRAM`, `PERSONAL_WEBSITE`,
`OTHER`.

| Method | Path | Body | Purpose |
|--------|------|------|---------|
| `POST`   | `/me/links` | `AddLinkRequest` | Add a link (description ≤200, displayOrder, isPublic). |
| `PATCH`  | `/me/links/{linkId}` | `EditLinkRequest` | Update any subset (all fields optional). |
| `DELETE` | `/me/links/{linkId}` | — | Remove. |

### Contacts — `ContactPlatform` enum

`TELEGRAM`, `WHATSAPP`, `EMAIL`, `PHONE`, `VIBER`, `SIGNAL`, `SKYPE`,
`OTHER`.

| Method | Path | Body | Purpose |
|--------|------|------|---------|
| `POST`   | `/me/contacts` | `AddContactRequest` | Add (`platform`, `value` ≤200, `isPublic`). |
| `PATCH`  | `/me/contacts/{contactId}` | `EditContactRequest` | Update. |
| `DELETE` | `/me/contacts/{contactId}` | — | Remove. |

Both `UserLink` and `UserContact` carry an `is_public` flag — non-public
entries are hidden from anyone except the owner.

---

## 6. Avatars & cover images

Base path: `/api/v1/users/me/profile`.

| Method | Path | Purpose |
|--------|------|---------|
| `POST` (multipart, part `image`) | `/avatar` | Upload / replace. Returns updated `UserResponse`. |
| `DELETE` | `/avatar` | Remove avatar (and the R2 object). |
| `POST` (multipart, part `image`) | `/cover`  | Upload / replace cover image. |
| `DELETE` | `/cover`  | Remove cover. |

Storage: R2 (Cloudflare). Both `avatarUrl`/`avatarS3Key` and
`coverImageUrl`/`coverImageS3Key` are persisted side-by-side so the
service can re-sign and delete on its own.

---

## 7. Specializations

Knowledge-topic specializations shown on the public profile.

`PATCH /api/v1/users/me/profile/specializations`

Body (`UpdateSpecializationsRequest`):

```json
{
  "specializations": [
    { "topicId": 12, "displayOrder": 0 },
    { "topicId": 47, "displayOrder": 1 }
  ]
}
```

The list **REPLACES** the existing set entirely — pass `[]` to clear.
Topic ids resolve against `ak.dev.irc.app.knowledge.entity.Topic`.

---

## 8. Social graph

Base path: `/api/v1/users`. Every mutating endpoint requires auth.

### Follow

| Method | Path | Purpose |
|--------|------|---------|
| `POST`   | `/{id}/follow` | Follow. Returns `SocialActionResponse` with the updated `SocialStatusResponse`. |
| `DELETE` | `/{id}/follow` | Unfollow. |
| `GET`    | `/{id}/followers?page=&size=` | Paginated followers. |
| `GET`    | `/{id}/following?page=&size=` | Paginated followings. |

Side effects on follow:

- `target.profile.followerCount++`, `actor.profile.followingCount++`
  (via `CounterCache`).
- Fires `NEW_FOLLOWER` notification to the target (one-shot, no aggregation).
- Triggers `FeedTimelineService.backfillFollowerFeed` on the post side —
  the new follower's home feed is back-filled with the followee's recent
  posts.

Side effects on unfollow:

- Counters reversed.
- Removes any prior `NEW_FOLLOWER` row pointing actor → target
  (`removeFollowNotification`).

### Block

| Method | Path | Purpose |
|--------|------|---------|
| `POST`   | `/{id}/block` | Block. Implicitly drops any follow edge in both directions. |
| `DELETE` | `/{id}/block` | Unblock. |
| `GET`    | `/me/blocked?page=&size=` | List my blocked users. |

Side effects:

- Blocked viewers see `404` on the target's posts / questions / research.
- Notifications between the two are suppressed at the dispatcher level.
- `sendUnblockNotification` fires on unblock so the target can re-engage.

### Restrict

Soft block — the restricted user can still see the restrictor's content,
but their interactions with the restrictor are quietly hidden (e.g.
comments are invisible to other viewers except themselves and the
restrictor).

| Method | Path | Purpose |
|--------|------|---------|
| `POST`   | `/{id}/restrict` | Restrict. |
| `DELETE` | `/{id}/restrict` | Unrestrict. |
| `GET`    | `/me/restricted?page=&size=` | List. |

The QnA module reads this in its answer listing — restricted answers are
hidden from everyone except the question author and the answer author.

### Social status

`GET /api/v1/users/{id}/social-status` (auth) → `SocialStatusResponse`:

```json
{
  "isFollowing":     true,
  "isBlocking":      false,
  "isRestricting":   false,
  "isBlockedByThem": false,
  "followerCount":   123,
  "followingCount":  87
}
```

---

## 9. Close friends

Mirrors the Cassandra-backed close-friends list used by the story
visibility model — same semantics, JPA mirror for the user-package.

Base path: `/api/v1/users/me/close-friends` (all endpoints `auth`).

| Method | Path | Purpose |
|--------|------|---------|
| `GET`    | `` | Page of my close friends. |
| `POST`   | `/{userId}` | Add a user. |
| `DELETE` | `/{userId}` | Remove. |

Unique constraint `uq_cf_owner_friend (owner_id, friend_id)` — duplicate
adds are silently a no-op.

The list is **privacy-sensitive** — only the owner can read it. Stories
with `CLOSE_FRIENDS` visibility (in the post package) check membership via
this list.

---

## 10. Scholar verification

Legacy admin-only workflow. **Users may no longer submit verification
applications** — promotions are now done directly by admins via
[§11](#11-admin-change-account-type). These endpoints exist to close out
applications submitted before the policy change.

Base path: `/api/v1/admin/verification`. Roles: `ADMIN`, `SUPER_ADMIN`.

| Method | Path | Purpose |
|--------|------|---------|
| `GET`  | `/queue?status=PENDING&page=&size=` | Page of applications by status. |
| `POST` | `/{id}/approve` | Approve. Body (optional): `VerificationReviewRequest` (reviewerNote ≤1000). |
| `POST` | `/{id}/reject` | Reject with the same body shape. |

`VerificationStatus`: `PENDING`, `UNDER_REVIEW`, `APPROVED`, `REJECTED`.

Approving sets the user's `verificationTier` and may upgrade their
`accountType` to `VERIFIED_SCHOLAR`.

---

## 11. Admin: change account type

The ONE path to mutate a user's classification. Roles: `ADMIN`,
`SUPER_ADMIN`.

`PATCH /api/v1/admin/users/{userId}/account-type`

Body (`AdminChangeAccountTypeRequest`):

```json
{
  "accountType":     "VERIFIED_SCHOLAR",  // required
  "verificationTier":"SCHOLAR",           // optional — null leaves it
  "role":            "SCHOLAR",           // optional — null leaves it
  "reason":          "Admin review ..."   // ≤500 chars (audit trail)
}
```

Refuses to mutate the platform's own system account (`isSystemAccount=true`).

The reason is captured in the audit log on `BaseAuditEntity`.

---

## 12. Account deletion

`DELETE /api/v1/users/me` (auth) — soft delete:

- Sets `deletedAt = now`.
- `User.isEnabled()` then returns `false` (the `UserDetails` contract
  rejects any further authentication).
- Cascade orphan-removal on `UserProfile`, `UserAuthority`,
  `RefreshToken`, `Notification` is configured but soft delete keeps
  them in place — they're stripped from feeds and lookups by
  `deletedAt IS NULL` filters at the query layer.

---

## 13. Notifications

The user package owns the cross-domain notification system. Other modules
(post, qna, research, system) write into it; this package owns the
inbox / SSE endpoints and the inbox UI categories.

### `NotificationType` — full catalog

```
NEW_FOLLOWER, UNFOLLOWED, BLOCKED, UNBLOCKED, RESTRICTED,
CONNECTION_REQUEST, CONNECTION_ACCEPTED,

PUBLICATION_LIKED, PUBLICATION_COMMENTED, PUBLICATION_COMMENT_REACTED,
PUBLICATION_CITED, RESEARCH_CONTRIBUTOR_ADDED,

POST_NEW, POST_REACTED, POST_COMMENTED, POST_COMMENT_REPLIED,
POST_COMMENT_REACTED, POST_SHARED,
POST_MENTIONED,         // legacy alias for USER_MENTIONED
USER_MENTIONED,         // canonical mention notification

QUESTION_NEW, QUESTION_ANSWERED, ANSWER_REPLIED, ANSWER_REACTED,
ANSWER_ACCEPTED, ANSWER_FEEDBACK_RECEIVED,

STORY_PUBLISHED, STORY_REACTED, STORY_REPLIED, SOUND_APPROVED,

SYSTEM_MESSAGE, SYSTEM_ANNOUNCEMENT, ACCOUNT_WARNING
```

### `NotificationCategory` — inbox tabs

Derived from `NotificationType` at response time (no storage column).
`USER_MENTIONED` always lands in `MENTIONS` regardless of source.

| Category | Includes |
|----------|----------|
| `POSTS`    | `POST_NEW`, `POST_REACTED`, `POST_COMMENTED`, `POST_COMMENT_REPLIED`, `POST_COMMENT_REACTED`, `POST_SHARED`, `POST_MENTIONED` |
| `QNA`      | `QUESTION_NEW`, `QUESTION_ANSWERED`, `ANSWER_REPLIED`, `ANSWER_REACTED`, `ANSWER_ACCEPTED`, `ANSWER_FEEDBACK_RECEIVED` |
| `RESEARCH` | `PUBLICATION_LIKED`, `PUBLICATION_COMMENTED`, `PUBLICATION_CITED` |
| `MENTIONS` | `USER_MENTIONED` (any source) |
| `SOCIAL`   | `NEW_FOLLOWER`, `UNFOLLOWED`, `BLOCKED`, `UNBLOCKED`, `RESTRICTED`, `CONNECTION_REQUEST`, `CONNECTION_ACCEPTED` |
| `SYSTEM`   | `SYSTEM_MESSAGE`, `SYSTEM_ANNOUNCEMENT`, `ACCOUNT_WARNING` |

### REST API (base: `/api/v1/notifications`, `@PreAuthorize(isAuthenticated)`)

#### Listing

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `` | List. Optional filters: `category=POSTS|QNA|RESEARCH|MENTIONS|SOCIAL|SYSTEM`, `type=POST_REACTED` (repeatable), `unread=true`. Standard `page=`, `size=` (default 20). |
| `GET` | `/unread` | Only unread. |
| `GET` | `/unread/count?category=…` | `{ count: N }` (overall or by category). |

#### Mark-read

| Method | Path | Body | Purpose |
|--------|------|------|---------|
| `PATCH` | `/read-all` | — | Mark every notification read. |
| `PATCH` | `/{id}/read` | — | Mark one. |
| `PATCH` | `/read` | `{ ids: [<uuid>, ...] }` | Bulk; returns `{ updated: N }` (already-read rows skipped). |
| `PATCH` | `/category/{category}/read` | — | Returns `{ updated: N }`. |

#### Delete

| Method | Path | Purpose |
|--------|------|---------|
| `DELETE` | `/{id}` | Delete one. |
| `DELETE` | `/read` | Purge every already-read notification; returns `{ deleted: N }`. |

#### Real-time SSE stream

`GET /api/v1/notifications/stream`  ·  `produces: text/event-stream`

- Auth: JWT principal OR `?token=<accessToken>` (because `EventSource`
  can't send custom headers).
- Headers set: `X-Accel-Buffering: no`, `Cache-Control: no-cache,
  no-store, must-revalidate`, `Pragma: no-cache`, `Connection:
  keep-alive` — so Railway/Nginx/Cloudflare don't buffer.
- 24h server-side timeout (not 0 — some containers treat 0 as 30s).
- 15s heartbeat to stay above proxy idle-timeouts.

Event types delivered:

| Event name      | Payload |
|-----------------|---------|
| `connected`     | Handshake on subscribe. |
| `notification`  | New (or coalesced) `NotificationResponse`. |
| `unread-count`  | `{ count: N }` after every state change. |
| `read`          | `{ ids: [...], allRead, deleted: false }` — cross-tab sync. |
| `deleted`       | `{ ids: [...], allRead, deleted: true }`. |
| `heartbeat`     | Keepalive every ~15–25s. |

### `NotificationResponse`

```java
record NotificationResponse(
    UUID                 id,
    NotificationType     type,
    NotificationCategory category,       // derived from type
    String               title,
    String               body,

    // primary actor (most recent for aggregated rows)
    UUID                 actorId,
    String               actorUsername,
    String               actorFullName,
    String               actorProfileImage,

    // aggregation
    long                 aggregateCount,    // 1 for non-aggregated
    UUID                 lastActorId,
    String               lastActorUsername,

    // resource pointers + deep link
    UUID                 resourceId,
    String               resourceType,
    String               deepLink,           // pre-built client URL, e.g. /posts/abc

    boolean              isRead,
    LocalDateTime        readAt,
    LocalDateTime        createdAt
) {}
```

### Coalescing

Same `(userId, groupKey)` unread rows merge into one row.
`group_key` format is `TYPE:resourceId` (e.g. `POST_REACTED:abc`).
`null` groupKey disables aggregation (system / unblock / accepted / ...).

When coalescing:

- `aggregateCount` bumps.
- `lastActor` replaces the primary `actor` so the inbox row shows the
  most recent actor's avatar.
- `body` is refreshed.
- `createdAt` is touched so the row floats to the top.
- `isRead` is reset to `false`.

A dedicated DB index `idx_notif_group (user_id, group_key, is_read)`
pushes the dedup lookup down to one index seek.

### Daily prune

`NotificationCleanupJob` runs at `cron = "0 15 3 * * *"` (03:15 server
local) and deletes any **read** notification older than 90 days. Unread
rows are never touched.

### `NotificationDispatcher`

Single entry point used by every module. Two public methods:

- `dispatch(Notification draft)` — one-shot, never aggregated.
- `dispatchAggregated(recipient, actor, type, groupKey, title, body,
  resourceId, resourceType)` — coalescing path.

Post-migration the dispatcher routes straight to
`CassandraNotificationService.deliver(LegacyDeliverRequest)` — aggregation,
realtime push, unread counters and email delivery all happen on the
Cassandra side now. The dispatcher returns a synthetic `Notification` with
the Cassandra `notification_id` stamped onto `.getId()` for source-compat
with callers that still read it.

---

## 14. Badges

`BadgeType` — what shows next to a user's name:

```
PLATFORM_OFFICIAL
INSTITUTION
SENIOR_SCHOLAR
VERIFIED_SCHOLAR
VERIFIED_RESEARCHER
MEDIA
EMAIL_VERIFIED
```

Derived from `(User.accountType, User.verificationTier, User.emailVerifiedAt)`
at response time — there's no `badges` table. Multiple badges per user
are returned via `UserResponse.badges` as a list of `BadgeDto`:

```java
record BadgeDto(
    BadgeType type,
    String    label,
    String    colorKey,   // UI hint
    String    icon,
    int       priority    // sort order
) {}
```

Badge → input mapping (simplified):

| Badge | When awarded |
|-------|--------------|
| `PLATFORM_OFFICIAL`   | `accountType == PLATFORM_OFFICIAL` |
| `INSTITUTION`         | `accountType == INSTITUTION` |
| `MEDIA`               | `accountType == MEDIA` |
| `SENIOR_SCHOLAR`      | `verificationTier == SENIOR_SCHOLAR` |
| `VERIFIED_SCHOLAR`    | `verificationTier == SCHOLAR` (or `accountType == VERIFIED_SCHOLAR`) |
| `VERIFIED_RESEARCHER` | `accountType == VERIFIED_RESEARCHER` |
| `EMAIL_VERIFIED`      | `emailVerifiedAt != null` (everyone who has confirmed their email) |

---

## 15. Enums

| Enum | Values | Notes |
|------|--------|-------|
| `Role` | `USER`, `SCHOLAR`, `RESEARCHER`, `MODERATOR`, `EDITOR`, `ADMIN`, `SUPER_ADMIN` | Spring Security maps to `ROLE_*`. `SCHOLAR` also grants `ROLE_RESEARCHER` (composite authority). |
| `AccountType` | `REGULAR`, `VERIFIED_SCHOLAR`, `VERIFIED_RESEARCHER`, `PLATFORM_OFFICIAL`, `INSTITUTION`, `MEDIA` | Drives badges + UI behaviour. |
| `VerificationTier` | `NONE`, `STUDENT_OF_KNOWLEDGE`, `SCHOLAR`, `SENIOR_SCHOLAR` | Gates fatwa-issuing and research review on the platform. |
| `BadgeType` | (see [§14](#14-badges)) | Derived enum, not stored. |
| `LinkPlatform` | `FACEBOOK`, `TWITTER`, `INSTAGRAM`, `LINKEDIN`, `YOUTUBE`, `GITHUB`, `ORCID`, `RESEARCHGATE`, `GOOGLE_SCHOLAR`, `TELEGRAM`, `PERSONAL_WEBSITE`, `OTHER` | Each carries a `displayName`. |
| `ContactPlatform` | `TELEGRAM`, `WHATSAPP`, `EMAIL`, `PHONE`, `VIBER`, `SIGNAL`, `SKYPE`, `OTHER` | Each carries a `displayName`. |
| `TokenType` | `EMAIL_VERIFY`, `PASSWORD_RESET`, `TWO_FACTOR_OTP` | TTLs: 24h / 1h / 10min. |
| `VerificationStatus` | `PENDING`, `UNDER_REVIEW`, `APPROVED`, `REJECTED` | Legacy scholar application states. |
| `NotificationType` | (see [§13](#13-notifications)) | Cross-domain. |
| `NotificationCategory` | `POSTS`, `QNA`, `RESEARCH`, `MENTIONS`, `SOCIAL`, `SYSTEM` | Derived from `NotificationType.of(...)`. |

---

## 16. JPA entities

### `User`

Table: `users`. Indexes on `email`, `username`, `deleted_at`,
`account_type`.

Key columns:

| Column | Notes |
|--------|-------|
| `id` (UUID PK) | |
| `fname`, `lname`, `username` (unique), `email` (unique), `password` | Auth-layer identity |
| `role` (enum `Role`, default `USER`) | |
| `account_type` (enum, default `REGULAR`) | Drives badges |
| `verification_tier` (enum, default `NONE`) | Gates scholar privileges |
| `is_system_account` (bool) | Only `true` for `PLATFORM_OFFICIAL` — blocks login |
| `orcid_id` | ORCID identifier |
| `preferred_language` (enum `Language`) | UI + email language |
| `is_enabled`, `is_account_non_expired`, `is_account_non_locked`, `is_credentials_non_expired` | `UserDetails` flags |
| `email_verified_at` | null = not yet |
| `two_factor_enabled`, `two_factor_secret` | TOTP |
| `email_notifications_enabled`, `email_social_enabled`, `email_mentions_enabled`, `email_system_enabled` | Per-category email gates (mirror the `NotificationKind.prefCategory()` system) |
| `last_login_at`, `deleted_at` | |
| `@OneToOne profile` | `UserProfile` (cascade ALL + orphanRemoval) |
| `@OneToMany authorities, refreshTokens, notifications` | |

Implements `UserDetails`:

- `getUsername()` returns the **email** (not `username`) for the
  authentication subject.
- `getAuthorities()` for a `SCHOLAR` user returns
  `[ROLE_SCHOLAR, ROLE_RESEARCHER]` — scholars implicitly hold researcher
  privileges.
- `isEnabled()` returns `isEnabled && deletedAt == null && !isSystemAccount`
  — system accounts cannot log in.

Convenience accessors delegate to `UserProfile` so the rest of the
codebase doesn't need to navigate the join:
`getProfileImage()`, `getLocation()`, `getProfileBio()`,
`getSelfDescriber()`, `isProfileLocked()`.

### `UserProfile`

Table: `user_profiles` (1-to-1 with `User`).

Key columns:

| Column | Notes |
|--------|-------|
| `id` (UUID PK), `user_id` (unique FK) | |
| `display_name` (≤120) | Public name — for INSTITUTION/MEDIA this is the org name |
| `profile_bio` (TEXT), `self_describer` (≤200), `location` (≤200) | |
| `is_profile_locked` (bool) | Private mode — only followers see content |
| `avatar_url` + `avatar_s3_key` | |
| `cover_image_url` + `cover_image_s3_key` | |
| `academic_title` (≤150), `institution_name` (≤200) | |
| `madhhab_id` (FK → `madhhabs`) | Optional |
| `website_url` (TEXT) | |
| `follower_count`, `following_count`, `research_count`, `fatwa_count` | Denormalised counters (CounterCache) |
| `is_for_hire` (bool) | |
| `content_language` (enum `Language`) | |
| `profile_views` | Incremented via Redis on each profile visit; reconciled daily |
| `@OneToMany specializations, links, contacts, attachments` | |

### `UserFollow`

Table: `user_follows`. Composite PK `(follower_id, following_id)` via
`UserFollowId`. Unique constraint `uk_user_follows`.

### `UserBlock`

Table: `user_blocks`. Composite PK `(blocker_id, blocked_id)` via
`UserBlockId`. Unique constraint `uk_user_blocks`. `blocked_at` is
auto-set.

### `UserRestriction`

Table: `user_restrictions`. Composite PK `(restrictor_id, restricted_id)`.

### `CloseFriendsList`

Table: `close_friends`. Composite PK `(owner_id, friend_id)` via the
inner static class `CloseFriendsId`. Unique constraint
`uq_cf_owner_friend`.

### `UserLink` / `UserContact` / `UserAttachment` / `UserTopicSpecialization`

See [§5](#5-links--contacts) and [§7](#7-specializations).

### `Notification`

Table: `notifications`. Indexes on `user_id`, `(user_id, is_read)`,
`actor_id`, and the hot-path **`idx_notif_group (user_id, group_key,
is_read)`** that backs coalescing.

Key columns:

| Column | Notes |
|--------|-------|
| `id`, `user_id` (recipient), `actor_id` (nullable for system) | |
| `type` (enum), `title` (≤200), `body` (TEXT) | |
| `resource_id`, `resource_type` | Pointer to the source object |
| `is_read`, `read_at` | |
| `group_key` (≤120) | `TYPE:resourceId` — coalescing key |
| `aggregate_count` (Long, default 1) | |
| `last_actor_id` (nullable FK) | Most recent actor in aggregated stream |

`coalesce(newActor, newBody)` is implemented on the entity — it bumps the
count, refreshes the actor + body, touches `createdAt` (to float the row
to the top of the inbox), and resets `isRead = false`.

### `RefreshToken`

Table: `refresh_tokens`. One row per device login. `token` is unique
(512-char hash), carries `device_info` and `ip_address`. `is_revoked` and
`expires_at` drive `isValid()`.

### `VerificationToken`

Table: `verification_tokens`. `token` unique, `type` (enum `TokenType`),
`expires_at`, `is_used`. TTLs: `EMAIL_VERIFY` 24h, `PASSWORD_RESET` 1h,
`TWO_FACTOR_OTP` 10min.

### `ScholarVerification`

Table: `scholar_verifications`. Legacy application table. Fields:
`claimed_tier`, `affiliation`, `evidence_urls` (comma-separated R2 URLs),
`orcid_id`, `status`, `reviewed_by`, `reviewer_note`, `reviewed_at`.

### `UserAuthority`

Table: `user_authorities`. Free-form Spring Security authority strings
beyond `ROLE_*`. Examples: `WRITE_PUBLICATION`, `REVIEW_SUBMISSION`.

---

## 17. DTOs

### Request DTOs

| DTO | Used by |
|-----|---------|
| `AuthRequests.RegisterRequest`   | `POST /auth/register` — `fname`/`lname`/`username`/`email`/`password` |
| `AuthRequests.LoginRequest`      | `POST /auth/login` — `username` accepts email OR username |
| `AuthRequests.RefreshTokenRequest` | `POST /auth/refresh` — optional, cookie fallback |
| `AuthRequests.LogoutRequest`     | `POST /auth/logout` — optional |
| `AuthRequests.ChangePasswordRequest` | `POST /auth/change-password` — re-verifies current password |
| `UpdateProfileRequest`           | `PATCH /users/me` — fname / lname / username (auth-layer only) |
| `UpdateUserProfileRequest`       | `PATCH /users/me/profile` — every field optional |
| `UpdateSpecializationsRequest`   | `PATCH /users/me/profile/specializations` — REPLACES the list |
| `AddLinkRequest` / `EditLinkRequest` | `/me/links` |
| `AddContactRequest` / `EditContactRequest` | `/me/contacts` |
| `AdminChangeAccountTypeRequest`  | `PATCH /admin/users/{id}/account-type` — admin-only |
| `VerificationReviewRequest`      | Admin verification approve/reject — `reviewerNote` ≤1000 |

### Response DTOs

| DTO | Returned by |
|-----|-------------|
| `UserResponse`            | All `/users/*` reads — embeds `profile`, `badges`, role, accountType, email-verified flag, createdAt |
| `ProfileResponse`         | Embedded inside `UserResponse.profile` — counters, links, contacts, attachments, specializations |
| `UserLinkResponse` / `UserContactResponse` / `UserAttachmentResponse` | Individual rows |
| `TopicDto`                | `{topicId, nameEn, nameAr, nameCkb}` — multi-language topic label |
| `BadgeDto`                | `{type, label, colorKey, icon, priority}` |
| `AuthResponse`            | `/auth/*` — `accessToken`, `refreshToken`, `tokenType`, `expiresIn` (seconds), optional `user` |
| `SocialActionResponse`    | Every social mutation — `{action, targetId, targetUsername, targetProfileImage, updatedStatus, performedAt}` |
| `SocialStatusResponse`    | `{isFollowing, isBlocking, isRestricting, isBlockedByThem, followerCount, followingCount}` |
| `ScholarVerificationResponse` | Admin verification queue rows |
| `NotificationResponse`    | Inbox listing + SSE `notification` event |

---

## 18. Realtime architecture

```
JPA write → @TransactionalEventListener(AFTER_COMMIT)
          → NotificationPushedEvent / NotificationUnreadCountEvent / NotificationReadEvent
          → NotificationRedisPublisher  ──► Redis pub/sub  irc:notifications:{userId}
                                                              │
                                                              ▼
                                NotificationRedisSubscriber (every instance)
                                              │
                                              ▼
                                NotificationSseService.push(userId, eventName, data)
                                              │
                                              ▼
                                          All open SSE emitters for the user
```

Key pieces:

- **`NotificationPushedEvent`** — fired inside the JPA transaction when a
  new (or coalesced) notification is saved. An `AFTER_COMMIT` listener
  forwards to Redis so SSE pushes never fire for rolled-back DB writes.
- **`NotificationUnreadCountEvent`** — fired after every state change so
  every open tab refreshes its badge count.
- **`NotificationReadEvent`** — fired on mark-read / delete so sibling
  tabs strip the row out of their local cache. Payload:
  `{ recipientId, ids, allRead, deleted }`.
- **`NotificationRedisPublisher`** — wraps each push in a
  `{event, data}` envelope so the subscriber can pick the correct SSE
  event name (`notification` / `unread-count` / `read` / `deleted`).
  Channel prefix: `irc:notifications:`.
- **`NotificationRedisSubscriber`** — listens on
  `irc:notifications:*`, parses the envelope, forwards to the local
  `NotificationSseService`. Unrecognised event names pass through as raw
  JSON for forward-compat.
- **`NotificationSseService`** — per-instance emitter manager. Multiple
  emitters per user (one per open tab/device). Heartbeat every 15s. Stale
  emitters are pruned silently on send failure.

---

## Cross-cutting User rules

- **Auth-layer vs profile split** — `User` carries identity / role /
  account classification; `UserProfile` carries public-facing fields.
- **System account is unloggable** — `User.isSystemAccount=true` blocks
  `UserDetails.isEnabled()`.
- **Account-type changes are admin-only** — no self-promotion.
- **No forgot-password flow** — only the authenticated `/change-password`
  endpoint rotates credentials.
- **Tokens dual-delivered** — HttpOnly cookies AND JSON body.
- **`SCHOLAR` implies `RESEARCHER`** in the authority list — the
  `UserDetails.getAuthorities()` override returns both `ROLE_SCHOLAR`
  and `ROLE_RESEARCHER`.
- **Soft delete** — `User.deletedAt` flips the account out of the
  `UserDetails.isEnabled()` check; rows stay in DB for audit.
- **Notifications coalesce** by `(userId, groupKey)` unread window, with
  the index `idx_notif_group` backing the hot-path dedupe lookup.
- **Realtime via Redis pub/sub** with `AFTER_COMMIT` listeners — no
  push fires for rolled-back DB transactions.
- **Daily inbox cleanup** at 03:15 — deletes read notifications older
  than 90 days; unread rows are kept indefinitely.
- **Per-category email opt-out** — four flags on `User`
  (`emailNotificationsEnabled`, `emailSocialEnabled`,
  `emailMentionsEnabled`, `emailSystemEnabled`).

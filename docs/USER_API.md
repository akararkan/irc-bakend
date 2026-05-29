# User Package — Complete API Documentation

The full reference for every endpoint under `ak.dev.irc.app.user` — the
PostgreSQL-backed identity layer that powers authentication, user profiles,
social graph (follow / block / restrict), notifications, close friends,
scholar verification, and email preferences.

Every endpoint below shows:

- **HTTP method + path**
- **Auth requirement** (anonymous-OK or JWT-required)
- **Path / query parameters** (with types and defaults)
- **JSON request body** (where applicable)
- **JSON response body** (full realistic sample)
- **Side effects** (DB tables touched, notifications fired, events broadcast)
- **Error responses** (specific HTTP statuses + `errorCode` strings)

---

## Table of contents

1. [Overview](#1-overview)
2. [Authentication & request headers](#2-authentication--request-headers)
3. [Token delivery (dual-channel)](#3-token-delivery-dual-channel)
4. [Unified error response](#4-unified-error-response)
5. [Enums (full catalog)](#5-enums)
6. [DTOs (response shapes)](#6-dtos)
7. [Database tables index](#7-database-tables-index)
8. [Auth endpoints](#8-auth-endpoints)
9. [User identity endpoints](#9-user-identity-endpoints)
10. [User profile endpoints](#10-user-profile-endpoints)
11. [Social graph endpoints](#11-social-graph-endpoints)
12. [Notification endpoints](#12-notification-endpoints)
13. [Close friends endpoints](#13-close-friends-endpoints)
14. [Admin user endpoints](#14-admin-user-endpoints)
15. [Email preferences endpoints](#15-email-preferences-endpoints)
16. [Cross-cutting rules](#16-cross-cutting-rules)
17. [Frontend integration guide](#17-frontend-integration-guide)

---

## 1. Overview

The user package is the **identity and social layer** of the IRC platform. It is
backed by **PostgreSQL** (JPA/Hibernate entities) + **Redis** (SSE fan-out,
email-context cache) + **R2/S3** (avatar / cover image / attachment binaries).

Key design decisions:

| Decision | Detail |
|---|---|
| Auth/profile split | `User` holds credentials + security flags; `UserProfile` holds all public display data |
| username ≠ email | `username` is the **user-chosen** public handle (e.g. `mala`). `email` is a private contact address. They are completely independent fields — the server never derives the username from the email, and changing one never affects the other. |
| Single role per user | `Role` column with exactly four values (USER / RESEARCHER / SCHOLAR / ADMIN); `SCHOLAR` implicitly grants `ROLE_RESEARCHER` |
| Badges follow the role | `VERIFIED_SCHOLAR` / `VERIFIED_RESEARCHER` are auto-derived from `role` — no separate verification workflow, no AccountType / VerificationTier dimensions |
| No forgot-password flow | Password can only be rotated by an authenticated session via `/change-password` |
| Soft-delete | `User.deletedAt` timestamp; `isEnabled()` returns `false` when set |
| Aggregated notifications | Same `(userId, groupKey)` rows coalesce instead of flooding the inbox |

---

## 2. Authentication & request headers

| Header | Value | When required |
|---|---|---|
| `Authorization` | `Bearer <accessToken>` | All `@PreAuthorize("isAuthenticated()")` endpoints |
| `Content-Type` | `application/json` | POST / PATCH / PUT with a JSON body |
| `Content-Type` | `multipart/form-data` | Avatar / cover upload |

The access token is a signed JWT issued at login / register / refresh. Its
default TTL is configurable; `AuthResponse.expiresIn` (seconds) tells the client
when to refresh.

---

## 3. Token delivery (dual-channel)

Every successful auth response delivers tokens via **two channels simultaneously**:

1. **HttpOnly cookies** — for browser clients (automatic CSRF protection via
   `SameSite`).
2. **JSON response body** — for mobile / API clients that read `accessToken` and
   `refreshToken` and send them as `Authorization: Bearer` headers.

The `/refresh` and `/logout` endpoints also accept the refresh token from
either the cookie or the request body — whichever is present.

---

## 4. Unified error response

All error responses share the same shape:

```json
{
  "status": 400,
  "errorCode": "VALIDATION_ERROR",
  "message": "Username must be between 3 and 50 characters",
  "timestamp": "2026-05-26T10:30:00"
}
```

Common `errorCode` values across the user package:

| errorCode | HTTP | Meaning |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Bean-validation constraint violated |
| `UNAUTHORIZED` | 401 | Missing or invalid JWT |
| `FORBIDDEN` | 403 | Authenticated but insufficient role |
| `USER_NOT_FOUND` | 404 | No user with the given id / username / email |
| `EMAIL_ALREADY_EXISTS` | 409 | Duplicate email on register |
| `USERNAME_ALREADY_EXISTS` | 409 | Duplicate username |
| `INVALID_CREDENTIALS` | 401 | Wrong password on login |
| `ACCOUNT_DISABLED` | 403 | Account not yet email-verified or soft-deleted |
| `TOKEN_EXPIRED` | 401 | Refresh token past `expires_at` |
| `TOKEN_REVOKED` | 401 | Refresh token already revoked |
| `CANNOT_FOLLOW_SELF` | 400 | Attempted self-follow |
| `CANNOT_BLOCK_SELF` | 400 | Attempted self-block |
| `ALREADY_FOLLOWING` | 409 | Duplicate follow attempt |
| `NOT_FOLLOWING` | 400 | Unfollow without a follow relationship |
| `NOTIFICATION_NOT_FOUND` | 404 | Notification id not found or not owned by caller |

---

## 5. Enums

### 5.1 Role

The only authorisation dimension. Each user has exactly one role; promotions /
demotions go through `PATCH /api/v1/admin/users/{id}/role` (admin-only).

| Value | Spring authorities granted | Notes |
|---|---|---|
| `USER` | `ROLE_USER` | Default for every registered account |
| `RESEARCHER` | `ROLE_RESEARCHER` | Can publish research papers; carries the **Researcher** badge automatically |
| `SCHOLAR` | `ROLE_SCHOLAR`, `ROLE_RESEARCHER` | Full scholar privileges (implies researcher); carries the **Scholar** badge automatically |
| `ADMIN` | `ROLE_ADMIN` | Platform operator. Gates `/api/v1/admin/*` |

> The older `MODERATOR` / `EDITOR` / `SUPER_ADMIN` tiers and the
> `AccountType` / `VerificationTier` dimensions were retired in the May 2026
> simplification. Every authorisation check now reads `role` alone.

### 5.2 BadgeType

Rendered in the UI next to the username. Auto-derived from `role` — no
separate verification workflow. A scholar shows only the Scholar badge (the
role implies researcher), not both.

| Value | When shown |
|---|---|
| `VERIFIED_SCHOLAR` | `role = SCHOLAR` |
| `VERIFIED_RESEARCHER` | `role = RESEARCHER` |

### 5.3 LinkPlatform

External link platforms a user can attach to their profile.

`FACEBOOK`, `TWITTER`, `INSTAGRAM`, `LINKEDIN`, `YOUTUBE`, `GITHUB`, `ORCID`,
`RESEARCHGATE`, `GOOGLE_SCHOLAR`, `TELEGRAM`, `PERSONAL_WEBSITE`, `OTHER`

### 5.4 ContactPlatform

Direct contact methods a user can share on their profile.

`TELEGRAM`, `WHATSAPP`, `EMAIL`, `PHONE`, `VIBER`, `SIGNAL`, `SKYPE`, `OTHER`

### 5.5 TokenType

Type of single-use verification token stored in `verification_tokens`.

| Value | TTL | Purpose |
|---|---|---|
| `EMAIL_VERIFY` | 24 h | Confirm email on registration |
| `PASSWORD_RESET` | 1 h | Future / legacy password reset (not exposed via API) |
| `TWO_FACTOR_OTP` | 10 min | 2FA one-time password |

### 5.6 NotificationType

Fine-grained event types delivered as notifications.

**Social**
`NEW_FOLLOWER`, `UNFOLLOWED`, `BLOCKED`, `UNBLOCKED`, `RESTRICTED`,
`CONNECTION_REQUEST`, `CONNECTION_ACCEPTED`

**Posts**
`POST_NEW`, `POST_REACTED`, `POST_COMMENTED`, `POST_COMMENT_REPLIED`,
`POST_COMMENT_REACTED`, `POST_SHARED`, `POST_MENTIONED`

**Q&A**
`QUESTION_NEW`, `QUESTION_ANSWERED`, `ANSWER_REPLIED`, `ANSWER_REACTED`,
`ANSWER_ACCEPTED`, `ANSWER_BEST_VOTED`

**Research / Publications**
`PUBLICATION_LIKED`, `PUBLICATION_COMMENTED`, `PUBLICATION_COMMENT_REACTED`,
`PUBLICATION_CITED`, `RESEARCH_CONTRIBUTOR_ADDED`

**Stories**
`STORY_PUBLISHED`, `STORY_REACTED`, `STORY_REPLIED`, `SOUND_APPROVED`

**Mentions**
`USER_MENTIONED` — cross-source; always lands in the MENTIONS inbox tab

**System**
`SYSTEM_MESSAGE`, `SYSTEM_ANNOUNCEMENT`, `ACCOUNT_WARNING`

### 5.7 NotificationCategory

Coarse inbox tab grouping. Derived from `NotificationType` at response time — never stored.

| Value | Types included |
|---|---|
| `POSTS` | `POST_*`, except `POST_MENTIONED` which goes to MENTIONS |
| `QNA` | `QUESTION_*`, `ANSWER_*` |
| `RESEARCH` | `PUBLICATION_*`, `RESEARCH_CONTRIBUTOR_ADDED` |
| `MENTIONS` | `USER_MENTIONED` |
| `SOCIAL` | `NEW_FOLLOWER`, `UNFOLLOWED`, `BLOCKED`, `UNBLOCKED`, `RESTRICTED`, `CONNECTION_*` |
| `SYSTEM` | `SYSTEM_*`, `ACCOUNT_WARNING` |

---

## 6. DTOs

### 6.1 AuthResponse

Returned on successful login, register, and token refresh.

```json
{
  "accessToken":  "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "550e8400-e29b-41d4-a716-...",
  "tokenType":    "Bearer",
  "expiresIn":    3600,
  "user": { /* UserResponse — null on /refresh */ }
}
```

`user` is `null` on `/refresh` — only the token pair is rotated.

### 6.2 UserResponse

The canonical user shape returned by most endpoints.

```json
{
  "id":              "550e8400-e29b-41d4-a716-446655440000",
  "fname":           "Ahmad",
  "lname":           "Al-Rashid",
  "username":        "ahmad.rashid",        // user-chosen handle — shown on posts and mentions; NEVER derived from email
  "email":           "ahmad@example.com",   // private contact address — never shown publicly
  "role":            "SCHOLAR",
  "badges": [
    {
      "type":      "VERIFIED_SCHOLAR",
      "label":     "Scholar",
      "colorKey":  "teal",
      "icon":      "ti-certificate",
      "priority":  1
    }
  ],
  "isEmailVerified": true,
  "profile":         { /* ProfileResponse */ },
  "createdAt":       "2025-01-15T08:30:00"
}
```

### 6.3 ProfileResponse

Nested inside `UserResponse`. Contains all public profile data.

```json
{
  "displayName":     "Sheikh Ahmad Al-Rashid",
  "avatarUrl":       "https://cdn.example.com/avatars/ahmad.jpg",
  "coverImageUrl":   "https://cdn.example.com/covers/ahmad-cover.jpg",
  "profileBio":      "Islamic jurisprudence scholar specialising in contemporary fiqh issues.",
  "selfDescriber":   "Scholar | Author | Researcher",
  "location":        "Amman, Jordan",
  "academicTitle":   "Professor of Islamic Jurisprudence",
  "institutionName": "University of Jordan",
  "madhhabId":       2,
  "madhhabName":     "Shafi'i",
  "websiteUrl":      "https://ahmad-rashid.com",
  "specializations": [
    { "topicId": 5, "nameEn": "Fiqh", "nameAr": "الفقه", "nameCkb": "فیقه" }
  ],
  "followerCount":   1240,
  "followingCount":  85,
  "researchCount":   12,
  "fatwaCount":      47,
  "isForHire":       false,
  "isProfileLocked": false,
  "contentLanguage": "AR",
  "profileViews":    8350,
  "links": [
    {
      "id":           "uuid",
      "platform":     "ORCID",
      "description":  "My research profile",
      "url":          "https://orcid.org/0000-0002-1825-0097",
      "isPublic":     true,
      "displayOrder": 1
    }
  ],
  "contacts": [
    {
      "id":       "uuid",
      "platform": "TELEGRAM",
      "value":    "@ahmad_rashid",
      "isPublic": true
    }
  ],
  "attachments": [
    {
      "id":          "uuid",
      "fileUrl":     "https://cdn.example.com/attachments/cv.pdf",
      "fileName":    "Ahmad-CV.pdf",
      "fileType":    "application/pdf",
      "fileSize":    204800,
      "description": "Curriculum vitae",
      "uploadedAt":  "2025-03-01T12:00:00"
    }
  ]
}
```

### 6.4 SocialActionResponse

Returned by every social action (follow / unfollow / block / unblock / restrict / unrestrict).

```json
{
  "action":             "FOLLOWED",
  "targetId":           "uuid",
  "targetUsername":     "ahmad.rashid",
  "targetProfileImage": "https://cdn.example.com/avatars/ahmad.jpg",
  "updatedStatus": {
    "isFollowing":   true,
    "isBlocking":    false,
    "isRestricting": false,
    "isBlockedByThem": false,
    "followerCount": 1241,
    "followingCount": 85
  },
  "performedAt": "2026-05-26T10:00:00"
}
```

Possible `action` strings: `FOLLOWED`, `UNFOLLOWED`, `BLOCKED`, `UNBLOCKED`,
`RESTRICTED`, `UNRESTRICTED`.

### 6.5 SocialStatusResponse

Describes the bidirectional relationship between the caller and a target user.

```json
{
  "isFollowing":     true,
  "isBlocking":      false,
  "isRestricting":   false,
  "isBlockedByThem": false,
  "followerCount":   1241,
  "followingCount":  85
}
```

### 6.6 NotificationResponse

```json
{
  "id":               "uuid",
  "type":             "POST_REACTED",
  "category":         "POSTS",
  "title":            "New reaction on your post",
  "body":             "Ahmad Al-Rashid and 3 others reacted to your post",
  "actorId":          "uuid",
  "actorUsername":    "ahmad.rashid",
  "actorFullName":    "Ahmad Al-Rashid",
  "actorProfileImage":"https://cdn.example.com/avatars/ahmad.jpg",
  "aggregateCount":   4,
  "lastActorId":      "uuid",
  "lastActorUsername":"someone.else",
  "resourceId":       "post-uuid",
  "resourceType":     "POST",
  "deepLink":         "/posts/post-uuid",
  "isRead":           false,
  "readAt":           null,
  "createdAt":        "2026-05-26T09:45:00"
}
```

### 6.7 FollowSuggestionResponse

Returned by the **friend suggestions** and **who-to-follow** endpoints (§11.12, §11.14). Carries enough data for the UI to render a suggestion card with no follow-up requests.

```json
{
  "id":            "uuid",
  "username":      "ahmad.rashid",
  "displayName":   "Ahmad Al-Rashid",
  "avatarUrl":     "https://cdn.example.com/avatars/ahmad.jpg",
  "followerCount": 1241,
  "role":          "SCHOLAR",
  "isFollowing":   false,
  "mutualCount":   3,
  "reason":        "3 mutual follows"
}
```

| Field | Type | Notes |
|---|---|---|
| `id` | uuid | Candidate user id |
| `username` | string | Public handle |
| `displayName` | string | Profile display name; falls back to `fname + lname` when unset |
| `avatarUrl` | string \| null | From `UserProfile`; `null` if no avatar |
| `followerCount` | long | Candidate's follower count (0 if no profile row) |
| `role` | enum | See [§5.1](#51-role) — drives the badge on the card |
| `isFollowing` | boolean | `true` if the caller already follows this candidate (always `false` for anonymous who-to-follow) |
| `mutualCount` | int | Friends-of-friends score (mutual-follow count). **Always `0`** for who-to-follow results — there is no mutual signal there |
| `reason` | string | Human-readable hint: `"3 mutual follows"` / `"Verified Scholar"` / `"Suggested for you"` |

### 6.8 UserStatsResponse

Profile stat-row counts, computed live from each source (see [§9.14](#914-profile-stat-counts)).

```json
{
  "postCount":      9,
  "reelCount":      3,
  "researchCount":  4,
  "questionCount":  7,
  "followerCount":  1241,
  "followingCount": 85
}
```

| Field | Source | Filter |
|---|---|---|
| `postCount` | Cassandra `posts_by_author` partition | live **non-reel** posts (`countByAuthor − reelCount`); a delete removes the row |
| `reelCount` | Cassandra `posts_by_author` partition | live posts with `post_type = REEL` |
| `researchCount` | Postgres `researches` | `status = PUBLISHED` and not soft-deleted |
| `questionCount` | Postgres `questions` | not soft-deleted |
| `followerCount` | Postgres `user_follows` | — |
| `followingCount` | Postgres `user_follows` | — |

> **Why a separate endpoint (not fields on `ProfileResponse`):** the counts span
> three stores (posts in Cassandra, research/questions in Postgres, follows on the
> profile). Computing them on demand keeps the hot `/users/me` and `/users/{id}`
> fetches cheap, and avoids cramming a Cassandra count into the profile DTO. The
> denormalized `user_profiles` counter columns (`research_count`, etc.) are **not**
> maintained and read 0 — ignore them; use this endpoint for the stat row.

---

## 7. Database tables index

| Table | Entity | Description |
|---|---|---|
| `users` | `User` | Core identity — credentials, flags, role |
| `user_profiles` | `UserProfile` | Public display data — bio, avatar, counters |
| `user_follows` | `UserFollow` | Follower ↔ following graph |
| `user_blocks` | `UserBlock` | Blocker ↔ blocked relationships |
| `user_restrictions` | `UserRestriction` | Restrictor ↔ restricted relationships |
| `close_friends` | `CloseFriendsList` | Owner ↔ friend close-friends list |
| `notifications` | `Notification` | Inbox rows with aggregation support |
| `refresh_tokens` | `RefreshToken` | Active refresh token sessions per device |
| `verification_tokens` | `VerificationToken` | Single-use tokens (email verify, 2FA OTP) |
| `user_links` | `UserLink` | External social/research links per profile |
| `user_contacts` | `UserContact` | Contact handles per profile (Telegram, etc.) |
| `user_attachments` | `UserAttachment` | CV / document attachments per profile |
| `user_topic_specializations` | `UserTopicSpecialization` | Topic expertise tags per profile |
| `user_authorities` | `UserAuthority` | Fine-grained permission grants (if any) |

All entities extend `BaseAuditEntity` which adds:

| Column | Type | Notes |
|---|---|---|
| `created_at` | `timestamp` | Set on `@PrePersist` |
| `updated_at` | `timestamp` | Set on `@PreUpdate` |
| `created_by` | `uuid` | Current authenticated user id |
| `updated_by` | `uuid` | Current authenticated user id |
| `created_by_ip` | `varchar(45)` | IP from `X-Forwarded-For` / `X-Real-IP` |
| `updated_by_ip` | `varchar(45)` | IP from `X-Forwarded-For` / `X-Real-IP` |
| `created_by_device` | `varchar(300)` | `User-Agent` header (max 300 chars) |
| `updated_by_device` | `varchar(300)` | `User-Agent` header (max 300 chars) |
| `last_action` | `varchar(30)` | `CREATE` or `UPDATE` |
| `action_note` | `varchar(500)` | Optional contextual note |

---

## 8. Auth endpoints

Base path: `/api/v1/auth`

---

### 8.1 Register

```
POST /api/v1/auth/register
```

**Auth:** none (public)

**Request body:**

```json
{
  "fname":    "Ahmad",
  "lname":    "Al-Rashid",
  "username": "ahmad.rashid",
  "email":    "ahmad@example.com",
  "password": "Str0ng!Pass"
}
```

| Field | Type | Constraints |
|---|---|---|
| `fname` | string | Required, max 80 chars |
| `lname` | string | Required, max 80 chars |
| `username` | string | Required, 3–50 chars, `[a-zA-Z0-9._-]` only |
| `email` | string | Required, valid email format, unique |
| `password` | string | Required, 8–128 chars |

**Response:** `201 Created`

```json
{
  "accessToken":  "eyJ...",
  "refreshToken": "uuid-...",
  "tokenType":    "Bearer",
  "expiresIn":    3600,
  "user": {
    "id":              "uuid",
    "fname":           "Ahmad",
    "lname":           "Al-Rashid",
    "username":        "ahmad.rashid",
    "email":           "ahmad@example.com",
    "role":            "USER",
    "badges":          [],
    "isEmailVerified": false,
    "profile":         { "followerCount": 0, "followingCount": 0, ... },
    "createdAt":       "2026-05-26T10:00:00"
  }
}
```

**Side effects:**
- Creates `users` row (`isEnabled = false` until email verified)
- Creates `user_profiles` row (1-to-1 cascade)
- Sends email verification email
- Issues `access_token` + `refresh_token` as HttpOnly cookies

**Errors:**

| Status | errorCode | Condition |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Any field constraint violated |
| 409 | `EMAIL_ALREADY_EXISTS` | Email already registered |
| 409 | `USERNAME_ALREADY_EXISTS` | Username already taken |

---

### 8.2 Login

```
POST /api/v1/auth/login
```

**Auth:** none (public)

**Request body:**

```json
{
  "username": "ahmad.rashid",
  "password": "Str0ng!Pass"
}
```

| Field | Type | Description |
|---|---|---|
| `username` | string | The account's **username** (nickname/handle, e.g. `ahmad.rashid`) **or** its **email address**. These are two distinct fields — either is accepted here as a login identifier. |
| `password` | string | The account password |

> `username` and `email` are separate fields on every account. `username` is the public-facing nickname (visible on posts, profiles, mentions). `email` is the private contact address (never shown publicly). At login you may supply either one — the server resolves it to the correct account.

**Response:** `200 OK` — same `AuthResponse` shape as register

**Side effects:**
- Updates `User.lastLoginAt`
- Creates a new `refresh_tokens` row (bound to device info + IP)
- Sets `access_token` + `refresh_token` HttpOnly cookies

**Errors:**

| Status | errorCode | Condition |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Missing fields |
| 401 | `INVALID_CREDENTIALS` | Wrong password |
| 401 | `ACCOUNT_DISABLED` | Account not enabled (email unverified or soft-deleted) |

---

### 8.3 Refresh tokens

```
POST /api/v1/auth/refresh
```

**Auth:** none (public) — authenticates via the refresh token itself

**Request body (optional — cookie used if body is absent):**

```json
{ "refreshToken": "uuid-..." }
```

**Response:** `200 OK`

```json
{
  "accessToken":  "eyJ...",
  "refreshToken": "uuid-...",
  "tokenType":    "Bearer",
  "expiresIn":    3600
}
```

`user` is **not** included in the refresh response.

**Side effects:**
- Revokes old refresh token
- Creates new refresh token
- Rotates HttpOnly cookies

**Errors:**

| Status | errorCode | Condition |
|---|---|---|
| 401 | `TOKEN_EXPIRED` | Refresh token past `expires_at` |
| 401 | `TOKEN_REVOKED` | Refresh token already revoked |
| 404 | `TOKEN_NOT_FOUND` | Unknown refresh token value |

---

### 8.4 Logout

```
POST /api/v1/auth/logout
```

**Auth:** JWT required

**Request body (optional — cookie used if body is absent):**

```json
{ "refreshToken": "uuid-..." }
```

**Response:** `200 OK` (empty body)

**Side effects:**
- Revokes the supplied refresh token
- Clears HttpOnly cookies

---

### 8.5 Logout all sessions

```
POST /api/v1/auth/logout-all
```

**Auth:** JWT required

**Request body:** none

**Response:** `200 OK` (empty body)

**Side effects:**
- Revokes **all** refresh tokens for the current user
- Clears HttpOnly cookies
- Every other active device is force-logged-out on next API call

---

### 8.6 Change password

```
POST /api/v1/auth/change-password
```

**Auth:** JWT required

**Design note:** This is the **only** way to change a password. There is no
`/forgot-password` or reset-token flow by design — only an authenticated session
can rotate the credential.

**Request body:**

```json
{
  "currentPassword": "Str0ng!Pass",
  "newPassword":     "Even$tr0nger!2026"
}
```

| Field | Constraints |
|---|---|
| `currentPassword` | Required |
| `newPassword` | Required, 8–128 chars |

**Response:** `200 OK` — `AuthResponse` with new token pair (no `user` field)

**Side effects:**
- Re-verifies `currentPassword` against stored hash — fails with `401` if wrong
- Revokes **all** existing refresh tokens (forces logout on other devices)
- Issues a fresh access + refresh pair for the caller's current device
- Rotates HttpOnly cookies

**Errors:**

| Status | errorCode | Condition |
|---|---|---|
| 400 | `VALIDATION_ERROR` | New password < 8 chars |
| 401 | `INVALID_CREDENTIALS` | `currentPassword` does not match |

---

## 9. User identity endpoints

Base path: `/api/v1/users`

---

### 9.1 Get my profile

```
GET /api/v1/users/me
```

**Auth:** JWT required

**Response:** `200 OK` — `UserResponse`

---

### 9.2 Get user by ID

```
GET /api/v1/users/{id}
```

**Auth:** none (public)

**Path params:** `id` — `UUID`

**Response:** `200 OK` — `UserResponse`

**Errors:** `404 USER_NOT_FOUND`

---

### 9.3 Get user by username

```
GET /api/v1/users/username/{username}
```

**Auth:** none (public)

**Response:** `200 OK` — `UserResponse`

**Errors:** `404 USER_NOT_FOUND`

---

### 9.4 Get user by email

```
GET /api/v1/users/email/{email}
```

**Auth:** none (public)

**Response:** `200 OK` — `UserResponse`

**Errors:** `404 USER_NOT_FOUND`

---

### 9.5 Update identity fields

Updates `fname`, `lname`, and/or `username` — the auth-layer fields.
Use `PATCH /me/profile` (§10.3) to update display/profile fields instead.

```
PATCH /api/v1/users/me
```

**Auth:** JWT required

**Request body (all fields optional):**

```json
{
  "fname":    "Ahmad",
  "lname":    "Al-Rashid",
  "username": "ahmad.rashid.new"
}
```

| Field | Constraints |
|---|---|
| `fname` | Max 80 chars |
| `lname` | Max 80 chars |
| `username` | 3–50 chars, `[a-zA-Z0-9._-]` only |

**Response:** `200 OK` — `UserResponse`

**Errors:** `409 USERNAME_ALREADY_EXISTS` if the new username is taken

---

### 9.6 Search users

```
GET /api/v1/users/search?q={query}&eligibleContributor={bool}&page={page}&size={size}
```

**Auth:** none (public)

**Query params:**

| Param | Type | Default | Description |
|---|---|---|---|
| `q` | string | `""` | Free-text search against name + username |
| `eligibleContributor` | bool | `false` | When `true`, restrict results to **contributor-eligible** users (`RESEARCHER` / `SCHOLAR`) — for the research co-author picker, so it only offers valid co-authors |
| `page` | int | 0 | Zero-based page index |
| `size` | int | 20 | Results per page |

**Response:** `200 OK` — `Page<UserResponse>`

---

### 9.7 Add link

```
POST /api/v1/users/me/links
```

**Auth:** JWT required

**Request body:**

```json
{
  "platform":     "ORCID",
  "description":  "My research profile",
  "url":          "https://orcid.org/0000-0002-1825-0097",
  "isPublic":     true,
  "displayOrder": 1
}
```

**Response:** `201 Created` — `UserLinkResponse`

```json
{
  "id":           "uuid",
  "platform":     "ORCID",
  "description":  "My research profile",
  "url":          "https://orcid.org/0000-0002-1825-0097",
  "isPublic":     true,
  "displayOrder": 1
}
```

---

### 9.8 Edit link

```
PATCH /api/v1/users/me/links/{linkId}
```

**Auth:** JWT required

**Request body (all fields optional):**

```json
{
  "platform":     "LINKEDIN",
  "description":  "Updated description",
  "url":          "https://linkedin.com/in/ahmad",
  "isPublic":     false,
  "displayOrder": 2
}
```

**Response:** `200 OK` — `UserLinkResponse`

---

### 9.9 Remove link

```
DELETE /api/v1/users/me/links/{linkId}
```

**Auth:** JWT required

**Response:** `204 No Content`

---

### 9.10 Add contact

```
POST /api/v1/users/me/contacts
```

**Auth:** JWT required

**Request body:**

```json
{
  "platform": "TELEGRAM",
  "value":    "@ahmad_rashid",
  "isPublic": true
}
```

**Response:** `201 Created` — `UserContactResponse`

```json
{
  "id":       "uuid",
  "platform": "TELEGRAM",
  "value":    "@ahmad_rashid",
  "isPublic": true
}
```

---

### 9.11 Edit contact

```
PATCH /api/v1/users/me/contacts/{contactId}
```

**Auth:** JWT required

**Request body (all fields optional):**

```json
{
  "platform": "WHATSAPP",
  "value":    "+962791234567",
  "isPublic": false
}
```

**Response:** `200 OK` — `UserContactResponse`

---

### 9.12 Remove contact

```
DELETE /api/v1/users/me/contacts/{contactId}
```

**Auth:** JWT required

**Response:** `204 No Content`

---

### 9.13 Delete account (soft)

```
DELETE /api/v1/users/me
```

**Auth:** JWT required

**Response:** `204 No Content`

**Side effects:**
- Sets `User.deletedAt = now()` — account is soft-deleted
- `User.isEnabled()` returns `false` immediately
- All subsequent login attempts will fail with `ACCOUNT_DISABLED`

---

### 9.14 Profile stat counts

Live counts for the profile stat row (POSTS / RESEARCH / QUESTIONS / FOLLOWERS /
FOLLOWING) — one request, works for your own profile and anyone else's.

```
GET /api/v1/users/{id}/stats
```

**Auth:** none (public, anonymous-safe).

**Response:** `200 OK` — [`UserStatsResponse`](#69-userstatsresponse)

```json
{ "postCount": 9, "reelCount": 3, "researchCount": 4, "questionCount": 7, "followerCount": 1241, "followingCount": 85 }
```

Each count is computed live from its source of truth and matches the filter of
the corresponding list endpoint, so the number always equals what the tab shows:

| Stat | Matches list endpoint | Counts |
|---|---|---|
| `postCount` | `GET /api/v1/posts/by-author/{id}` (non-reel rows) | the author's live posts, **excluding reels** |
| `reelCount` | `GET /api/v1/posts/reels/by-author/{id}` | the author's reels (`post_type = REEL`) |
| `researchCount` | `GET /api/v1/researches/researcher/{id}` | PUBLISHED, non-deleted research |
| `questionCount` | `GET /api/v1/questions/by-author/{id}`¹ | non-deleted questions |
| `followerCount` | `GET /api/v1/users/{id}/followers` | — |
| `followingCount` | `GET /api/v1/users/{id}/following` | — |

Each count is resilient: if one store is briefly unavailable that field degrades
to `0` rather than failing the whole response.

> ¹ A public `GET /questions/by-author/{id}` list isn't shipped yet — `questionCount`
> here is the authoritative number for any profile regardless. (`GET /questions/me`
> still returns the logged-in user's own paged list.)

---

## 10. User profile endpoints

Base path: `/api/v1/users`

---

### 10.1 Get public profile (by ID)

```
GET /api/v1/users/{id}/profile
```

**Auth:** none (public)

**Response:** `200 OK` — `UserResponse` (with full `ProfileResponse` nested)

---

### 10.2 Get my profile

```
GET /api/v1/users/me/profile
```

**Auth:** JWT required

**Response:** `200 OK` — `UserResponse`

---

### 10.3 Update my profile

Updates `UserProfile` display fields. Does not touch auth-layer fields
(`fname`, `lname`, `username`) — use `PATCH /me` (§9.5) for those.

```
PATCH /api/v1/users/me/profile
```

**Auth:** JWT required

**Request body (all fields optional — omitted fields retain current value):**

```json
{
  "displayName":     "Sheikh Ahmad Al-Rashid",
  "profileBio":      "Islamic jurisprudence scholar.",
  "selfDescriber":   "Scholar | Author | Researcher",
  "location":        "Amman, Jordan",
  "academicTitle":   "Professor of Islamic Jurisprudence",
  "institutionName": "University of Jordan",
  "madhhabId":       2,
  "websiteUrl":      "https://ahmad-rashid.com",
  "isForHire":       false,
  "isProfileLocked": false,
  "contentLanguage": "AR"
}
```

**Response:** `200 OK` — `UserResponse`

---

### 10.4 Upload avatar

```
POST /api/v1/users/me/profile/avatar
Content-Type: multipart/form-data
```

**Auth:** JWT required

**Form field:** `image` — binary image file

**Response:** `200 OK` — `UserResponse` (with updated `profile.avatarUrl`)

**Side effects:**
- Uploads to R2 / S3
- Deletes the previous avatar object if one existed
- Updates `user_profiles.avatar_url` and `user_profiles.avatar_s3_key`

---

### 10.5 Remove avatar

```
DELETE /api/v1/users/me/profile/avatar
```

**Auth:** JWT required

**Response:** `200 OK` — `UserResponse` (with `profile.avatarUrl = null`)

**Side effects:** Deletes the R2/S3 object; nulls both `avatar_url` and `avatar_s3_key`

---

### 10.6 Upload cover image

```
POST /api/v1/users/me/profile/cover
Content-Type: multipart/form-data
```

**Auth:** JWT required

**Form field:** `image` — binary image file

**Response:** `200 OK` — `UserResponse`

---

### 10.7 Remove cover image

```
DELETE /api/v1/users/me/profile/cover
```

**Auth:** JWT required

**Response:** `200 OK` — `UserResponse`

---

### 10.8 Update topic specializations

Replaces the full specialization list atomically.

```
PATCH /api/v1/users/me/profile/specializations
```

**Auth:** JWT required

**Request body:**

```json
{
  "specializations": [
    { "topicId": 5,  "displayOrder": 1 },
    { "topicId": 12, "displayOrder": 2 },
    { "topicId": 18, "displayOrder": 3 }
  ]
}
```

**Response:** `200 OK` — `UserResponse`

**Side effects:**
- Deletes all existing `user_topic_specializations` rows for the user
- Inserts the new list
- `TopicDto` in `ProfileResponse.specializations` is populated with `nameEn`,
  `nameAr`, `nameCkb` from the knowledge base

---

## 11. Social graph endpoints

Base path: `/api/v1/users`

---

### 11.1 Follow a user

```
POST /api/v1/users/{id}/follow
```

**Auth:** JWT required

**Response:** `200 OK` — `SocialActionResponse` with `action = "FOLLOWED"`

**Side effects:**
- Inserts into `user_follows`
- Increments `user_profiles.following_count` for the caller
- Increments `user_profiles.follower_count` for the target
- Fires `NEW_FOLLOWER` notification to the target (aggregated by `groupKey = "NEW_FOLLOWER:targetId"`)
- Publishes a RabbitMQ `UserEvent` for home-feed fanout

**Errors:**

| Status | errorCode | Condition |
|---|---|---|
| 400 | `CANNOT_FOLLOW_SELF` | Caller and target are the same user |
| 409 | `ALREADY_FOLLOWING` | Follow relationship already exists |

---

### 11.2 Unfollow a user

```
DELETE /api/v1/users/{id}/follow
```

**Auth:** JWT required

**Response:** `200 OK` — `SocialActionResponse` with `action = "UNFOLLOWED"`

**Side effects:**
- Deletes from `user_follows`
- Decrements `following_count` for the caller
- Decrements `follower_count` for the target

**Errors:** `400 NOT_FOLLOWING` if no follow relationship exists

---

### 11.3 Get a user's followers

```
GET /api/v1/users/{id}/followers?page={page}&size={size}
```

**Auth:** none (public)

**Response:** `200 OK` — `Page<UserResponse>`

---

### 11.4 Get a user's following

```
GET /api/v1/users/{id}/following?page={page}&size={size}
```

**Auth:** none (public)

**Response:** `200 OK` — `Page<UserResponse>`

---

### 11.5 Block a user

```
POST /api/v1/users/{id}/block
```

**Auth:** JWT required

**Response:** `200 OK` — `SocialActionResponse` with `action = "BLOCKED"`

**Side effects:**
- Inserts into `user_blocks`
- Automatically removes any follow relationship in both directions
- Fires `BLOCKED` notification to the target
- Blocked user can no longer see or interact with the blocker's content

**Errors:** `400 CANNOT_BLOCK_SELF`

---

### 11.6 Unblock a user

```
DELETE /api/v1/users/{id}/block
```

**Auth:** JWT required

**Response:** `200 OK` — `SocialActionResponse` with `action = "UNBLOCKED"`

**Side effects:**
- Deletes from `user_blocks`
- Fires `UNBLOCKED` notification to the previously blocked user

---

### 11.7 Get my blocked users

```
GET /api/v1/users/me/blocked?page={page}&size={size}
```

**Auth:** JWT required

**Response:** `200 OK` — `Page<UserResponse>`

---

### 11.8 Restrict a user

Restriction is a **soft privacy filter**: the restricted user can still see your
content but their comments appear only to themselves (not visible to others).

```
POST /api/v1/users/{id}/restrict
```

**Auth:** JWT required

**Response:** `200 OK` — `SocialActionResponse` with `action = "RESTRICTED"`

**Side effects:**
- Inserts into `user_restrictions`
- Fires `RESTRICTED` notification to the target

---

### 11.9 Unrestrict a user

```
DELETE /api/v1/users/{id}/restrict
```

**Auth:** JWT required

**Response:** `200 OK` — `SocialActionResponse` with `action = "UNRESTRICTED"`

---

### 11.10 Get my restricted users

```
GET /api/v1/users/me/restricted?page={page}&size={size}
```

**Auth:** JWT required

**Response:** `200 OK` — `Page<UserResponse>`

---

### 11.11 Get social status with a user

Returns the full bidirectional relationship between the caller and the target.

```
GET /api/v1/users/{id}/social-status
```

**Auth:** JWT required

**Response:** `200 OK` — `SocialStatusResponse`

```json
{
  "isFollowing":     true,
  "isBlocking":      false,
  "isRestricting":   false,
  "isBlockedByThem": false,
  "followerCount":   1241,
  "followingCount":  85
}
```

---

### 11.12 Get my friend suggestions

Friends-of-friends suggestions for the authenticated user, scored by mutual-follow
count and fully hydrated (name, avatar, follower count, `isFollowing`). Source is the
precomputed `friend_suggestions_by_user` Cassandra partition; user + profile data is
joined in from PostgreSQL in two bulk queries.

**Automatic fallback:** when the caller has no mutual connections yet (empty suggestion
partition — typical for new accounts), this **transparently returns the who-to-follow
list** (§11.14) instead, so the surface is never empty.

```
GET /api/v1/users/me/suggestions?limit={limit}
```

**Auth:** JWT required

**Query params:**

| Param | Type | Default | Notes |
|---|---|---|---|
| `limit` | int | `20` | Max suggestions to return |

**Response:** `200 OK` — `List<FollowSuggestionResponse>` (see [§6.8](#68-followsuggestionresponse))

```json
[
  {
    "id": "uuid", "username": "ahmad.rashid", "displayName": "Ahmad Al-Rashid",
    "avatarUrl": "https://cdn.example.com/avatars/ahmad.jpg", "followerCount": 1241,
    "role": "SCHOLAR",
    "isFollowing": false, "mutualCount": 3, "reason": "3 mutual follows"
  }
]
```

For friends-of-friends results `mutualCount` is the suggestion score and `reason`
comes from the stored reason (falls back to `"Suggested for you"`). When the fallback
to who-to-follow kicks in, items follow the who-to-follow shape (`mutualCount = 0`).

---

### 11.13 Dismiss a suggestion

Removes a candidate from the caller's suggestion partition so it stops appearing in
§11.12. **Idempotent** — a no-op if the suggestion no longer exists.

```
DELETE /api/v1/users/me/suggestions/{candidateId}
```

**Auth:** JWT required

**Path params:**

| Param | Type | Notes |
|---|---|---|
| `candidateId` | uuid | The suggested user to dismiss |

**Response:** `204 No Content`

**Side effects:**
- Deletes the `(viewerId, candidateId)` row from `friend_suggestions_by_user`

---

### 11.14 Who to follow

Popular accounts the caller does **not** yet follow. Ordering:
`SCHOLAR` → `RESEARCHER` → `USER`, then by `follower_count` DESC. Already-followed
and blocked users are excluded via SQL subqueries (so there is no empty-`IN`-list risk).

```
GET /api/v1/users/who-to-follow?limit={limit}
```

**Auth:** **optional.** Authenticated callers get a personalized list (self excluded,
already-followed excluded, blocked excluded). Anonymous callers get the global list with
no exclusions and every `isFollowing = false`.

**Query params:**

| Param | Type | Default | Notes |
|---|---|---|---|
| `limit` | int | `20` | Max accounts to return |

**Response:** `200 OK` — `List<FollowSuggestionResponse>` (see [§6.7](#67-followsuggestionresponse))

```json
[
  {
    "id": "uuid", "username": "yusuf", "displayName": "Yusuf al-Qaradawi",
    "avatarUrl": "https://cdn.example.com/avatars/yusuf.jpg", "followerCount": 98214,
    "role": "SCHOLAR",
    "isFollowing": false, "mutualCount": 0, "reason": "Verified Scholar"
  }
]
```

`mutualCount` is always `0` here (no mutual signal). `reason` is derived from
the candidate's `role` (`"Verified Scholar"`, `"Verified Researcher"`, or
`"Suggested for you"`).

---

## 12. Notification endpoints

Base path: `/api/v1/notifications`

All endpoints under this path require `isAuthenticated()`.

> 📒 **Full reference:** this section is the quick endpoint list. For the complete
> notification system — the kind catalog, what triggers each notification,
> aggregation rules, the SSE event shapes, email gating, Cassandra storage, and a
> frontend integration guide — see [`NOTIFICATIONS_API.md`](./NOTIFICATIONS_API.md).

---

### 12.1 SSE notification stream

Establishes a Server-Sent Events stream for real-time inbox delivery.

```
GET /api/v1/notifications/stream
```

**Auth:** JWT (Bearer header) **or** `?token=<accessToken>` query param

> Browser `EventSource` cannot send custom headers — use the `?token=` param
> from the browser.

**Response:** `text/event-stream`

Event types on this stream:

| Event name | Payload | When fired |
|---|---|---|
| `connected` | `{userId}` | On SSE subscription handshake |
| `notification` | `NotificationResponse` | New or coalesced notification arrives |
| `unread-count` | `{count: N}` | After any state change (read / delete / new) |
| `read` | `{ids:[...], allRead, deleted: false}` | After a mark-read action (for tab sync) |
| `deleted` | `{ids:[...], allRead, deleted: true}` | After a delete action |
| `heartbeat` | `{}` | Keepalive every 15 s |

**Auth failure:** If no valid token is found, the server writes `401` directly
and closes the SSE connection (no JSON error body).

---

### 12.2 List notifications

```
GET /api/v1/notifications
```

**Query params:**

| Param | Type | Description |
|---|---|---|
| `category` | `NotificationCategory` | Filter to one inbox tab |
| `type` | `NotificationType` (repeatable) | Filter to specific types |
| `unread` | boolean | `true` = only unread rows |
| `page` | int (default 0) | |
| `size` | int (default 20) | |

**Response:** `200 OK` — `Page<NotificationResponse>`

---

### 12.3 List unread notifications

```
GET /api/v1/notifications/unread?page={page}&size={size}
```

**Response:** `200 OK` — `Page<NotificationResponse>`

---

### 12.4 Unread count

```
GET /api/v1/notifications/unread/count?category={category}
```

`category` is optional — omit for the total across all categories.

**Response:**

```json
{ "count": 7 }
```

---

### 12.5 Mark all as read

```
PATCH /api/v1/notifications/read-all
```

**Response:** `200 OK` (empty body)

**Side effects:**
- Sets `is_read = true`, `read_at = now()` for all unread rows owned by caller
- Broadcasts `unread-count: 0` + `read` event on the SSE stream

---

### 12.6 Mark one as read

```
PATCH /api/v1/notifications/{id}/read
```

**Response:** `200 OK` (empty body)

**Errors:** `404 NOTIFICATION_NOT_FOUND`

---

### 12.7 Bulk mark as read

```
PATCH /api/v1/notifications/read
```

**Request body:**

```json
{ "ids": ["uuid-1", "uuid-2", "uuid-3"] }
```

**Response:**

```json
{ "updated": 3 }
```

Already-read rows are silently skipped (not counted in `updated`).

---

### 12.8 Mark category as read

```
PATCH /api/v1/notifications/category/{category}/read
```

**Path param:** `category` — one of the `NotificationCategory` values

**Response:**

```json
{ "updated": 5 }
```

---

### 12.9 Delete one notification

```
DELETE /api/v1/notifications/{id}
```

**Response:** `204 No Content`

**Errors:** `404 NOTIFICATION_NOT_FOUND`

---

### 12.10 Delete all read notifications

```
DELETE /api/v1/notifications/read
```

**Response:**

```json
{ "deleted": 12 }
```

**Side effects:** Hard-deletes all rows where `is_read = true` owned by the
caller. Broadcasts `deleted` SSE event.

---

## 13. Close friends endpoints

Base path: `/api/v1/users/me/close-friends`

All endpoints require `isAuthenticated()`.

---

### 13.1 List close friends

```
GET /api/v1/users/me/close-friends?page={page}&size={size}
```

**Response:** `200 OK` — `Page<UserResponse>`

---

### 13.2 Add close friend

```
POST /api/v1/users/me/close-friends/{userId}
```

**Response:** `201 Created` (empty body)

**Side effects:**
- Inserts into `close_friends` (composite PK: `owner_id` + `friend_id`)
- The friend must already be followed — the API does not auto-follow
- Story content marked `audience = CLOSE_FRIENDS` becomes visible to this user

**Errors:** `409` if already in the close friends list

---

### 13.3 Remove close friend

```
DELETE /api/v1/users/me/close-friends/{userId}
```

**Response:** `204 No Content`

---

## 14. Admin user endpoints

Base path: `/api/v1/admin/users`

> The previous **scholar-verification application workflow** (queue / approve /
> reject endpoints and the `ScholarVerification` entity) was retired with the
> AccountType / VerificationTier cleanup. Role changes are now a single admin
> PATCH call; there's no separate ticket to manage.

---

### 14.1 Change role (admin)

Promote / demote a user along the four-role ladder
(`USER` / `RESEARCHER` / `SCHOLAR` / `ADMIN`). The auto-derived badge on
`UserResponse` updates on the next read.

```
PATCH /api/v1/admin/users/{userId}/role
```

**Auth:** `ROLE_ADMIN`

**Request body:**

```json
{
  "role":   "SCHOLAR",
  "reason": "Verified credentials — ijaza from Al-Azhar confirmed."
}
```

| Field | Required | Notes |
|---|---|---|
| `role` | Yes | The new `Role` value (see [§5.1](#51-role)) |
| `reason` | No | Max 500 chars — recorded in audit log |

**Response:** `200 OK` — `UserResponse`

**Side effects:**
- Updates `User.role`
- The `reason` is recorded in `BaseAuditEntity.actionNote`
- The badge list in subsequent `UserResponse` calls reflects the new role immediately

**Errors:**

| Status | errorCode | Condition |
|---|---|---|
| 400 | `VALIDATION_ERROR` | `role` is null or `reason` > 500 chars |
| 403 | `FORBIDDEN` | Caller is not `ADMIN` |
| 404 | `USER_NOT_FOUND` | No user with the given `userId` |

---

## 15. Email preferences endpoints

Base path: `/api/v1/users/me/email-preferences`

All endpoints require `isAuthenticated()`.

---

### 15.1 Get email preferences

```
GET /api/v1/users/me/email-preferences
```

**Response:** `200 OK`

```json
{
  "master":   true,
  "social":   true,
  "mentions": true,
  "system":   true
}
```

| Flag | Controls |
|---|---|
| `master` | Global email kill switch — `false` suppresses all outbound emails |
| `social` | Follow / block / social interaction emails |
| `mentions` | `@mention` notification emails |
| `system` | System announcements and account warnings |

---

### 15.2 Update email preferences

Partial update — any field omitted retains its current value.

```
PATCH /api/v1/users/me/email-preferences
```

**Request body:**

```json
{
  "master":   true,
  "social":   false,
  "mentions": true,
  "system":   true
}
```

**Response:** `200 OK` — same `EmailPreferences` shape

**Side effects:**
- Persists to `users` table
- Evicts the 60-second email-context Redis cache entry so new preferences
  take effect immediately on the next outbound notification

---

### 15.3 Send test email

Sends a self-diagnostic email to the caller's own address. Bypasses the
notification pipeline — useful to verify SMTP is working end-to-end.

```
POST /api/v1/users/me/email-preferences/test
```

**Response:** `200 OK`

```json
{
  "queued": true,
  "to":     "ahmad@example.com"
}
```

If the user has no email address on file:

```json
{
  "queued": false,
  "reason": "no email on account"
}
```

---

### 15.4 Unsubscribe from all emails

Turns the master email toggle off. Designed to be linkable from email footers.

```
POST /api/v1/users/me/email-preferences/unsubscribe-all
```

**Response:** `200 OK`

```json
{ "emailNotificationsEnabled": false }
```

---

## 16. Cross-cutting rules

### Account lifecycle

1. Accounts start as `isEnabled = false` until the email verification link is
   clicked — login attempts before verification return `ACCOUNT_DISABLED`.
2. Soft-delete (`deletedAt != null`) immediately disables login without removing
   any data.

### Security flags (Spring Security `UserDetails`)

| Flag | Default | Effect when `false` |
|---|---|---|
| `isEnabled` | `false` until verified | Authentication blocked |
| `isAccountNonExpired` | `true` | Authentication blocked |
| `isAccountNonLocked` | `true` | Authentication blocked |
| `isCredentialsNonExpired` | `true` | Authentication blocked |

`isEnabled()` returns `true` **only when** both of:
- `isEnabled = true` (email verified)
- `deletedAt = null` (not soft-deleted)

### Password policy

- Minimum 8 characters, maximum 128 characters.
- No forgot-password flow — the only rotation path is `/change-password` with
  an active session.
- On password change: all other refresh tokens are revoked, the caller stays
  logged in on the current device.

### Token TTLs

| Token | Configurable property | Default |
|---|---|---|
| Access JWT | `app.security.jwt.expiration` | 1 hour (3 600 s) |
| Refresh token | `app.security.jwt.refresh-expiration` | 30 days |
| Email verify | hardcoded in `VerificationToken` | 24 hours |
| 2FA OTP | hardcoded in `VerificationToken` | 10 minutes |

### Notification aggregation

Notifications with the same `(userId, groupKey)` are coalesced into a single
inbox row instead of flooding:

- `groupKey` format: `TYPE:resourceId` (e.g. `POST_REACTED:abc-uuid`)
- `aggregateCount` increments on each coalesced event
- `actor` / `lastActor` are updated to reflect the most recent contributor
- `createdAt` is bumped so the coalesced row floats to the top of the inbox
- A new event on a previously-read coalesced row resets `isRead = false`

Null `groupKey` disables aggregation (used for one-off system / unblock
notifications).

### Profile convenience accessors

`User` delegates four read-only calls to `UserProfile` for use across the
post / qna / research modules without loading the profile separately:

- `getProfileImage()` → `profile.avatarUrl`
- `getLocation()` → `profile.location`
- `getProfileBio()` → `profile.profileBio`
- `getSelfDescriber()` → `profile.selfDescriber`
- `isProfileLocked()` → `profile.isProfileLocked`

### Follower / following counters

`user_profiles.follower_count` and `following_count` are denormalised counters
kept in sync by the service layer on every follow / unfollow action. They are
not recalculated from `user_follows` at read time.

> **`research_count` / `fatwa_count` are NOT maintained.** Despite the column
> names, no service increments them today, so they read `0` — do not trust them.
> Use the live [`GET /users/{id}/stats`](#914-profile-stat-counts) endpoint (§9.14)
> for the profile RESEARCH count (`researches WHERE status = PUBLISHED AND not
> deleted`, matching the RESEARCH tab list). The columns should be either
> maintained on publish/unpublish/delete or dropped.

---

## 17. Frontend integration guide

This section is written **for frontend engineers**. The sections above are the
contract (every field, every status code); this one is the *playbook* — what to
call, in what order, how to keep the UI in sync, and the traps to avoid. Each
feature has a **👉 What to do** block with the real call sequence.

### 17.1 Golden rules (read once, apply everywhere)

1. **Base URL:** all paths are under `https://<host>/api/v1`.
2. **Auth header:** send `Authorization: Bearer <accessToken>` on every
   authenticated call. (Browsers may instead rely on the HttpOnly cookie — see §18.2.)
3. **Optimistic updates for toggles.** Follow / block / dismiss return the *final*
   state. Flip the UI immediately on tap, fire the request, roll back only on error.
   Never block the UI waiting for the response.
4. **Counters are denormalised and authoritative in the response.** When a social
   action returns `updatedStatus`, bind your follower/following counts straight from
   it — don't recount.
5. **Every error has the same shape** (`{ status, errorCode, message, timestamp }`).
   Branch on `errorCode`, never on `message` (the message text may change).
6. **`username` is public, `email` is private.** Render `username` on cards, mentions,
   and profiles. **Never** display `email` anywhere public.

---

### 17.2 Auth & token lifecycle — do this first

Everything else depends on getting this right. The backend delivers tokens on
**two channels at once** (§3):

| Client type | What to use |
|---|---|
| **Browser web app** | The HttpOnly cookies set automatically on the response. Send requests with `credentials: 'include'`; you don't manually attach the access token. |
| **Mobile / native / API** | Read `accessToken` + `refreshToken` from the JSON body, store them securely (Keychain / Keystore), and send `Authorization: Bearer <accessToken>`. |

**`AuthResponse`** (from `/register`, `/login`, `/refresh`):

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "550e8400-e29b-41d4-a716-...",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "user": { "...": "UserResponse — null on /refresh" }
}
```

#### 👉 What to do — login / register

1. `POST /auth/login` (or `/auth/register`) with credentials.
2. Persist the token pair (mobile) or rely on cookies (web).
3. Store `user` from the response as your current-user object — don't make a second
   `GET /users/me` call, it's already there.
4. Schedule a refresh: refresh at roughly **`expiresIn − 60s`** (e.g. 3600 → refresh
   at ~3540s), so a token never expires mid-request.

#### 👉 What to do — the 401 auto-refresh interceptor

This is the single most important piece of plumbing. Wrap your HTTP client so a
`401 TOKEN_EXPIRED` triggers one refresh-and-retry:

```ts
async function request(path, opts = {}) {
  let res = await http(path, withAuth(opts));
  if (res.status === 401 && (await res.clone().json()).errorCode === 'TOKEN_EXPIRED') {
    const ok = await refreshTokens();              // POST /auth/refresh (cookie or body)
    if (!ok) { logoutLocally(); redirectToLogin(); throw new Error('session expired'); }
    res = await http(path, withAuth(opts));        // retry once with the new token
  }
  return res;
}
```

- `POST /auth/refresh` takes the refresh token from the cookie **or** body — send
  whichever channel you're on. It returns a fresh `AuthResponse` (with `user: null`).
- **`TOKEN_REVOKED`** (logged out elsewhere / refresh reused) is terminal — do **not**
  retry; clear local state and send the user to login.
- Refresh once per 401, never loop. If the retry also 401s, give up and log out.

#### 👉 What to do — logout

- `POST /auth/logout` ends the current session (revokes this refresh token).
- `POST /auth/logout-all` revokes **every** session/device — use it for a "log out
  everywhere" button and after a password change.
- Either way, clear local tokens, close the notification SSE stream (§18.7), and
  route to login.

---

### 17.3 Rendering a user (identity vs profile, and badges)

A `UserResponse` (§6.2) has two layers:

- **Identity** (top level): `id`, `fname`, `lname`, `username` (user-chosen
  handle), `email`, `role`, `isEmailVerified`, `badges`.
- **Profile** (`user.profile`, a `ProfileResponse` §6.3): `displayName`, `avatarUrl`,
  `coverImageUrl`, `profileBio`, counters (`followerCount`, `followingCount`,
  `researchCount`, `fatwaCount`), `specializations`, etc.

#### 👉 What to do — display name & avatar

- Show `profile.displayName` when present; fall back to `fname + " " + lname`.
- Show `profile.avatarUrl`; if `null`, render initials/placeholder.
- For the `@handle` on cards and mentions, use `username` (top-level identity).
  It's the value the user picked at registration — never derived from email.

#### 👉 What to do — badges

`badges` is a pre-computed, ready-to-render array auto-derived from `role`
(see [§5.2](#52-badgetype)). **Do not derive badges yourself** — the backend
already did, and there's no separate verification dimension to consult.

- Render in array order (a user has at most one badge — Scholar or Researcher,
  never both; ordinary users get an empty list).
- Use `colorKey` to pick your theme colour, `icon` for the glyph, `label` for the
  tooltip / accessible name.

---

### 17.4 Editing identity & profile

| Goal | Call | Notes |
|---|---|---|
| Change name / username | `PATCH /users/me` (§9.5) | Send only the fields you're changing |
| Edit bio, title, location… | `PATCH /users/me/profile` (§10.3) | Partial update |
| Avatar | `POST /users/me/profile/avatar` | `multipart/form-data`, field name **`image`** |
| Cover | `POST /users/me/profile/cover` | `multipart/form-data`, field name **`image`** |
| Remove avatar / cover | `DELETE …/avatar` / `…/cover` | |
| Links / contacts / specializations | §9.7–9.12, §10.8 | |

#### 👉 What to do — image upload

```ts
const fd = new FormData();
fd.append('image', file);                          // field MUST be "image"
const updated = await request('/users/me/profile/avatar', { method: 'POST', body: fd });
// response is the full UserResponse with the new profile.avatarUrl
setCurrentUser(updated);
```

- **Don't set `Content-Type` manually** for multipart — let the browser/runtime add
  the boundary.
- The response is the updated `UserResponse`. Re-render from it. If the new image URL
  is the same path as before (rare), append a cache-buster (`?v=Date.now()`) so the
  `<img>` refreshes.
- Uploading a new avatar/cover auto-deletes the previous object server-side — you
  don't need to call DELETE first.

---

### 17.5 Social graph — follow, block, restrict

All of these return a **`SocialActionResponse`** (§6.4) whose `updatedStatus` block
gives you the new relationship flags **and** counts in one shot.

#### 👉 What to do — the Follow button

```ts
async function toggleFollow(userId, currentlyFollowing) {
  setFollowing(!currentlyFollowing);                         // optimistic
  try {
    const verb = currentlyFollowing ? 'DELETE' : 'POST';
    const r = await request(`/users/${userId}/follow`, { method: verb });
    const body = await r.json();                             // SocialActionResponse
    setFollowing(body.updatedStatus.isFollowing);            // trust the server
    setFollowerCount(body.updatedStatus.followerCount);      // bind counts directly
  } catch (e) {
    setFollowing(currentlyFollowing);                        // roll back
  }
}
```

Error handling specific to follow:

| `errorCode` | Meaning | UI |
|---|---|---|
| `CANNOT_FOLLOW_SELF` | You tried to follow yourself | Hide/disable the button on your own profile |
| `ALREADY_FOLLOWING` | Race / stale state | Treat as success — set following = true |
| `NOT_FOLLOWING` | Unfollow with no relationship | Treat as success — set following = false |

#### 👉 What to do — opening someone's profile

Call `GET /users/{id}/social-status` once on profile open to hydrate the button
states (`isFollowing`, `isBlocking`, `isRestricting`, `isBlockedByThem`). If
`isBlockedByThem` is true, hide follow/message actions.

**Blocking removes follows in both directions** server-side — after a successful
block, locally set `isFollowing = false` *and* drop the user from any "followers"
list you're showing.

**Followers / following / blocked / restricted lists** are page-based:
`?page={n}&size={m}` → a Spring `Page<UserResponse>`. Use `content`, `number`,
`totalPages`, `last` for infinite scroll.

---

### 17.6 "Who to follow" & friend suggestions (the new surface)

This powers the suggestion sidebar / onboarding "find people" screen. **Pick the
endpoint by auth state:**

| State | Endpoint | Why |
|---|---|---|
| Logged in | `GET /users/me/suggestions?limit=20` | Friends-of-friends, personalised; **auto-falls back** to who-to-follow when the user has no mutuals yet — so it's never empty |
| Logged out | `GET /users/who-to-follow?limit=20` | Global verified/popular list (no exclusions) |

Both return `FollowSuggestionResponse[]` (§6.7) — **everything the card needs is
already in the row**, so rendering a suggestion costs **zero** extra requests.

#### 👉 What to do — render a suggestion card

- Avatar ← `avatarUrl`, name ← `displayName`, handle ← `@username`.
- Badge ← derive from `role` (`SCHOLAR` → Scholar badge, `RESEARCHER` →
  Researcher badge, `USER` → no badge). Reuse the same badge component as
  elsewhere.
- **Subtitle ← `reason`** (`"3 mutual follows"`, `"Verified Scholar"`, …). Just print
  it; the backend already localised the intent.
- Follow button initial state ← `isFollowing` (already computed for you).
- `mutualCount > 0` ⇒ a real friends-of-friends suggestion; `mutualCount === 0` ⇒ a
  who-to-follow item (don't show a "mutuals" pill for those).

#### 👉 What to do — follow from the card

Call `POST /users/{id}/follow` as in §18.5. On success, either remove the card from
the list or flip it to "Following" — your product choice. Don't refetch the whole list.

#### 👉 What to do — dismiss (the ✕ on a card)

```ts
async function dismiss(candidateId) {
  removeCardLocally(candidateId);                            // optimistic
  await request(`/users/me/suggestions/${candidateId}`, { method: 'DELETE' });
  // 204 No Content; idempotent — safe even if already gone. No rollback needed.
}
```

This permanently removes the candidate from the user's suggestion partition, so it
won't reappear on the next fetch.

---

### 17.7 Notifications — live SSE + REST

Notifications come through **two** channels: a live SSE stream for real-time updates
while the app is open, and REST endpoints for the initial inbox load and actions.

#### 👉 What to do — open the live stream on app start

```ts
// Browsers can't set headers on EventSource → pass the token as a query param.
const es = new EventSource(`/api/v1/notifications/stream?token=${accessToken}`);
```

**These are NAMED events** — you must use `addEventListener`, not `onmessage`:

| Event | Payload | What the frontend does |
|---|---|---|
| `connected` | `{ userId }` | Handshake — mark the stream live |
| `notification` | `NotificationResponse` | Prepend to the inbox; if the id already exists (aggregation), **replace** it (its `aggregateCount` / actors changed) and float it to top |
| `unread-count` | `{ count }` | **Set** the badge to `count` — don't compute it yourself |
| `read` | `{ ids, allRead }` | Another tab marked things read → mark the same ids read locally (multi-tab sync) |
| `deleted` | `{ ids, allRead }` | Another tab deleted → remove the same ids locally |
| `heartbeat` | `{}` | Keepalive (15 s) — ignore |

```ts
es.addEventListener('notification', e => upsertNotification(JSON.parse(e.data)));
es.addEventListener('unread-count', e => setBadge(JSON.parse(e.data).count));
es.addEventListener('read',    e => markReadLocally(JSON.parse(e.data).ids));
es.addEventListener('deleted', e => removeLocally(JSON.parse(e.data).ids));
```

#### 👉 What to do — reconnect & auth expiry

- `EventSource` auto-reconnects on a dropped connection — you don't manage retries.
- **But** if the access token expired, the server returns `401` and **closes** the
  stream (no JSON body). Detect via `es.onerror` with `es.readyState === CLOSED`:
  refresh the token (§18.2), then open a **new** `EventSource` with the fresh token.
- Always `es.close()` on logout and on app teardown.

#### 👉 What to do — inbox screen (REST)

- Initial load: `GET /notifications?page=0&size=20` (paginated history).
- Badge on cold start: `GET /notifications/unread/count`.
- Tabs/filters: `GET /notifications/unread`, `GET /notifications/category/{category}/read`.
- Mark read: `PATCH /notifications/{id}/read`, `PATCH /notifications/read` (bulk ids),
  `PATCH /notifications/read-all`, `PATCH /notifications/category/{category}/read`.
- Delete: `DELETE /notifications/{id}`, `DELETE /notifications/read` (clear all read).

After any of these, the server also pushes the matching `read`/`deleted`/`unread-count`
SSE event — so if your stream is open you'll get tab-sync for free; just apply the
optimistic local change immediately too.

#### 👉 What to do — render an aggregated notification

`NotificationResponse` (§6.6) is pre-aggregated:

- `aggregateCount > 1` ⇒ show "**{actorFullName}** and **{aggregateCount − 1} others**
  {action}". Use `title` / `body` directly — they're already composed.
- `isRead` drives the unread dot.
- **Navigate using `deepLink`** (e.g. `/posts/{id}`) on tap — don't build the URL from
  `resourceType` + `resourceId` yourself.

---

### 17.8 Close friends

`/users/me/close-friends` (§13): `GET` to list, `POST /{userId}` to add,
`DELETE /{userId}` to remove. Use it to populate the audience picker when a user posts
with `CLOSE_FRIENDS` visibility. Optimistic add/remove; both are idempotent-friendly.

---

### 17.9 Error-handling cheat-sheet

| `errorCode` | Where | Frontend reaction |
|---|---|---|
| `TOKEN_EXPIRED` | any auth call | Refresh + retry once (§18.2) |
| `TOKEN_REVOKED` | refresh | Terminal — clear state, go to login |
| `UNAUTHORIZED` | any | Not logged in / bad token → login |
| `FORBIDDEN` | admin / role-gated | Hide the action; show "no access" if reached directly |
| `VALIDATION_ERROR` | forms | Show `message` inline on the field |
| `EMAIL_ALREADY_EXISTS` / `USERNAME_ALREADY_EXISTS` | register | Mark that field taken |
| `INVALID_CREDENTIALS` | login | "Wrong email or password" (don't say which) |
| `ACCOUNT_DISABLED` | login | Prompt to verify email |
| `CANNOT_FOLLOW_SELF` / `CANNOT_BLOCK_SELF` | social | Hide the action on own profile |
| `ALREADY_FOLLOWING` / `NOT_FOLLOWING` | follow | Treat as success; reconcile button state |
| `USER_NOT_FOUND` / `NOTIFICATION_NOT_FOUND` | any | Remove the stale item from the list |

---

### 17.10 Frontend checklist

- [ ] HTTP client attaches `Authorization: Bearer` (mobile) or `credentials: 'include'` (web).
- [ ] 401 `TOKEN_EXPIRED` → refresh once → retry; `TOKEN_REVOKED` → hard logout.
- [ ] Proactive refresh scheduled at `expiresIn − 60s`.
- [ ] Store `user` from the login/register response; don't re-fetch `/users/me`.
- [ ] Badges rendered from `badges` (sorted by `priority`), not derived client-side.
- [ ] `email` never shown publicly; `username` used for handles/mentions.
- [ ] Follow/block/restrict are optimistic; counts bound from `updatedStatus`.
- [ ] Profile open hydrates buttons via `GET /{id}/social-status`.
- [ ] Image uploads use `multipart/form-data`, field `image`, no manual `Content-Type`.
- [ ] Suggestion sidebar: `/me/suggestions` when logged in, `/who-to-follow` when not.
- [ ] Suggestion cards render entirely from `FollowSuggestionResponse` (no extra calls); ✕ → `DELETE /me/suggestions/{id}`.
- [ ] Notification `EventSource` opened with `?token=`; uses `addEventListener` for named events; reopened after token refresh; closed on logout.
- [ ] Unread badge **set** from `unread-count` events, not recomputed.
- [ ] Notifications navigated via `deepLink`; aggregated rows use `title`/`body` as-is.
- [ ] All errors branch on `errorCode`, never on `message`.
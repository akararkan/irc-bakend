# User Model — API Reference (current state)

The authoritative reference for the **current** user data model and the
endpoints that read or mutate it. Concise on purpose — covers what
exists today, not what the older 2,300-line `USER_API.md` documented
historically. Refer to that file for notifications, social actions,
and admin-tier endpoints not repeated here.

---

## Table of contents

1. [Data model — the split](#1-data-model--the-split)
2. [Role enum (authorization surface)](#2-role-enum-authorization-surface)
3. [DTOs](#3-dtos)
4. [Authentication endpoints](#4-authentication-endpoints)
5. [Identity endpoints (`/api/v1/users`)](#5-identity-endpoints-apiv1users)
6. [Profile endpoints (`/api/v1/users/.../profile`)](#6-profile-endpoints-apiv1usersprofile)
7. [Links + contacts (sub-resources of profile)](#7-links--contacts-sub-resources-of-profile)
8. [Account lifecycle](#8-account-lifecycle)
9. [Error model](#9-error-model)
10. [Cheat sheet](#10-cheat-sheet)

---

## 1. Data model — the split

The user model is split across **two JPA entities** that share a 1:1
relationship, plus four supporting sub-entities hung off `UserProfile`.

| Entity | Table | Holds | Why split |
|---|---|---|---|
| `User` | `users` | Authentication (email, password, role, 2FA, email prefs, session metadata) | Loaded on every request via `@AuthenticationPrincipal`; kept narrow so the Spring Security path stays fast |
| `UserProfile` | `user_profiles` | Public-facing display (display name, bio, avatar, cover, academic title, institution, madhhab, counters) | Lazy-loaded only when the profile is actually rendered |
| `UserLink` | `user_links` | External link rows on the profile (Twitter, ORCID, website…) | 0..N per profile |
| `UserContact` | `user_contacts` | Direct-message handles on the profile (Telegram, email…) | 0..N per profile |
| `UserAttachment` | `user_attachments` | Files on the profile (CV, transcript…) | 0..N per profile |
| `UserTopicSpecialization` | `user_topic_specializations` | Join row between a profile and a topic | 0..N per profile, ordered |

`User.profile` is `@OneToOne(fetch = LAZY)`, cascade ALL, orphan-removal
true — the profile is created together with the user on registration
and dropped together on hard delete. Same cascade applies from
`UserProfile` to each of the four sub-entities.

### 1.1 `User` — every field

JPA table: `users`. Implements `UserDetails` so Spring Security can load
it directly. All fields below are exposed as public getters by Lombok.

#### Identity

| Field | Column | Type | Nullable | Default | Description |
|---|---|---|---|---|---|
| `id` | `id` | UUID | no | generated | Primary key, generated via `GenerationType.UUID` on first persist. Never updated. |
| `fname` | `fname` | varchar(80) | no | — | First name. Kept separate from `lname` so the platform can render display order per locale (Arabic ordering, etc.). |
| `lname` | `lname` | varchar(80) | no | — | Last name. |
| `username` | `username` | varchar(50) | no, **unique** | — | The user-chosen public handle (e.g. `mala`). Shown on posts, mentions, and the `@handle` rendering everywhere. **Never derived from email** — that was a historical bug, fixed. |
| `email` | `email` | varchar(255) | no, **unique** | — | Private contact address. Used to send verification, password-reset, and notification emails. Never shown in public profile responses. |
| `password` | `password` | varchar(255) | yes | — | BCrypt hash (cost 12). `null` only for federated-only accounts (OAuth-only; not currently exposed but allowed by the schema). |

#### Authorization

| Field | Column | Type | Nullable | Default | Description |
|---|---|---|---|---|---|
| `role` | `role` | enum | no | `SCHOLAR` | The complete authorization surface — one of `USER / RESEARCHER / SCHOLAR / ADMIN`. See [§2](#2-role-enum-authorization-surface). `getAuthorities()` grants `ROLE_SCHOLAR` + `ROLE_RESEARCHER` together when role is SCHOLAR. |
| `orcidId` | `orcid_id` | varchar(20) | yes | — | ORCID researcher identifier, e.g. `0000-0002-1825-0097`. Optional, used by the academic UI on researcher profiles. |
| `preferredLanguage` | `preferred_language` | enum | no | `EN` | UI + email locale. Drives `Accept-Language` resolution on transactional emails and the default `contentLanguage` filter for the feed. |

#### Spring Security flags

| Field | Column | Type | Nullable | Default | Description |
|---|---|---|---|---|---|
| `isEnabled` | `is_enabled` | boolean | no | `false` | Combined with `deletedAt == null` to satisfy `UserDetails.isEnabled()`. Flips `true` after email verification. A freshly-registered account is disabled until the user clicks the verification link. |
| `isAccountNonExpired` | `is_account_non_expired` | boolean | no | `true` | `UserDetails` flag; reserved for future scheduled-expiry features. |
| `isAccountNonLocked` | `is_account_non_locked` | boolean | no | `true` | `UserDetails` flag; flipped `false` by moderation when an account is locked. Locked users see `401 AUTH_ACCOUNT_LOCKED` on login. |
| `isCredentialsNonExpired` | `is_credentials_non_expired` | boolean | no | `true` | `UserDetails` flag; reserved for future password-rotation policies. |

#### Verification

| Field | Column | Type | Nullable | Default | Description |
|---|---|---|---|---|---|
| `emailVerifiedAt` | `email_verified_at` | timestamp | yes | `null` | Timestamp the user clicked the verification link. `isEmailVerified()` returns `true` when non-null. |
| `twoFactorEnabled` | `two_factor_enabled` | boolean | no | `false` | Whether TOTP is enabled on the account. |
| `twoFactorSecret` | `two_factor_secret` | varchar(255) | yes | — | Base-32 TOTP secret. Encrypted at rest (column-level). Returned only to the owner during enrollment, never on read paths. |

#### Email notification preferences

Each switch gates a category of outbound email. The in-app inbox is
unaffected — a muted category still produces inbox rows; only the email
side is suppressed. All default `true`.

| Field | Column | Description |
|---|---|---|
| `emailNotificationsEnabled` | `email_notifications_enabled` | **Master switch.** When `false`, ALL emails are suppressed regardless of the per-category flags below. |
| `emailSocialEnabled` | `email_social_enabled` | Social: posts, comments, reactions, shares, mentions in posts/comments. |
| `emailMentionsEnabled` | `email_mentions_enabled` | Mentions (`USER_MENTIONED`) across any source. |
| `emailSystemEnabled` | `email_system_enabled` | System messages, announcements, account warnings. |
| `emailTrendingEnabled` | `email_trending_enabled` | Opt-out for the daily `TRENDING_DIGEST` (X-style "trending in scholarship"). Independent of `emailSystemEnabled` so a user can keep account-warning emails while muting the digest. Carries `default true` in `columnDefinition` so `ALTER TABLE ADD COLUMN` on an existing table succeeds. |

#### Session metadata

| Field | Column | Type | Nullable | Default | Description |
|---|---|---|---|---|---|
| `lastLoginAt` | `last_login_at` | timestamp | yes | `null` | Updated on every successful login. Used for "Last active 3 minutes ago" surfaces. |
| `deletedAt` | `deleted_at` | timestamp | yes | `null` | Soft-delete marker. `isDeleted()` returns `true` when non-null. `isEnabled()` returns `false` for any soft-deleted user, blocking re-login without a separate restore flow. |

#### Relationships (lazy-loaded)

| Field | Mapping | Description |
|---|---|---|
| `profile` | `@OneToOne(mappedBy="user")` | The matching `UserProfile` row. Always present on an active account. |
| `authorities` | `@OneToMany(mappedBy="user")` | `UserAuthority` rows. Currently unused by the auth path (`getAuthorities()` derives from `role` directly); kept for future fine-grained ACLs. |
| `refreshTokens` | `@OneToMany(mappedBy="user")` | Open refresh-token rows. `/auth/logout` deletes one; `/auth/logout-all` deletes them all. |
| `notifications` | `@OneToMany(mappedBy="user")` | In-app notifications addressed to this user. Read by `NotificationController` endpoints. |

#### `UserDetails` overrides

| Method | Returns |
|---|---|
| `getUsername()` | The actual `username` field (provided by Lombok's getter — **not** overridden to return email; that was a removed bug). |
| `getPassword()` | The `password` field. |
| `getAuthorities()` | `ROLE_SCHOLAR` + `ROLE_RESEARCHER` if `role==SCHOLAR`; otherwise the single `ROLE_<name>`. |
| `isEnabled()` | `isEnabled && deletedAt == null` — soft-deleted users can never authenticate. |

#### Helpers

| Method | Description |
|---|---|
| `getFullName()` | `fname + " " + lname`. |
| `isDeleted()` | `deletedAt != null`. |
| `isEmailVerified()` | `emailVerifiedAt != null`. |
| `isVerifiedScholar()` | `role == SCHOLAR`. The scholar role is the platform's "verified" tier — no separate verification workflow. |
| `isResearcher()` | `role == SCHOLAR || role == RESEARCHER`. Returns true for anyone allowed to publish research. |

#### Convenience profile accessors (delegate to `UserProfile`)

These let post/qna/research/search modules read public profile data
without dereferencing `user.getProfile()` themselves:

| Method | Returns |
|---|---|
| `getProfileImage()` | `profile.avatarUrl` (null-safe) |
| `getLocation()` | `profile.location` (null-safe) |
| `getProfileBio()` | `profile.profileBio` (null-safe) |
| `getSelfDescriber()` | `profile.selfDescriber` (null-safe) |
| `isProfileLocked()` | `profile.isProfileLocked` (false when profile is null) |

---

### 1.2 `UserProfile` — every field

JPA table: `user_profiles`. One row per active `User`, created in the
same transaction as the user on registration.

#### Owner link

| Field | Column | Type | Nullable | Description |
|---|---|---|---|---|
| `id` | `id` | UUID | no | Primary key. |
| `user` | `user_id` | FK | no, **unique** | `@OneToOne` back to the owning `User`. Unique constraint enforces 1:1. |

#### Display identity

| Field | Column | Type | Nullable | Default | Description |
|---|---|---|---|---|---|
| `displayName` | `display_name` | varchar(120) | yes | — | Public name shown across the platform. For institutional/media accounts this is the organisation name; may differ from `User.fname + User.lname`. Falls back to `fname + lname` in the UI when null. |
| `profileBio` | `profile_bio` | TEXT | yes | — | Long-form bio shown on the profile page. Free text, Markdown allowed at the UI's discretion. |
| `selfDescriber` | `self_describer` | TEXT | yes | — | Short tagline shown directly under the display name (e.g. "Scholar · Author · Researcher"). Distinct from the longer `profileBio`. |
| `location` | `location` | varchar(200) | yes | — | Free-text location (e.g. "Erbil, Iraq"). Not geocoded — purely display. |
| `isProfileLocked` | `is_profile_locked` | boolean | no | `false` | When `true`, the profile is private: only followers can see posts/research; non-followers see a "locked profile" placeholder. New follow requests are still allowed (no separate request/accept flow). |

#### Media

| Field | Column | Type | Nullable | Description |
|---|---|---|---|---|
| `avatarUrl` | `avatar_url` | TEXT | yes | Public proxied URL of the avatar (e.g. `/api/v1/media/users/{id}/avatar/{uuid}.jpg` or absolute when `MEDIA_PUBLIC_URL` is set). Returned to the client; never the raw R2 URL. |
| `avatarS3Key` | `avatar_s3_key` | TEXT | yes | Internal R2 object key for the avatar. Used to delete the old file when the user replaces it. |
| `coverImageUrl` | `cover_image_url` | TEXT | yes | Public proxied URL of the cover image (banner). Same shape as `avatarUrl`. |
| `coverImageS3Key` | `cover_image_s3_key` | TEXT | yes | Internal R2 object key for the cover image. |

#### Academic / institutional

| Field | Column | Type | Nullable | Description |
|---|---|---|---|---|
| `academicTitle` | `academic_title` | varchar(150) | yes | E.g. "Professor of Islamic Jurisprudence", "MSc Computer Science". |
| `institutionName` | `institution_name` | varchar(200) | yes | E.g. "Salahaddin University". |
| `madhhab` | `madhhab_id` | FK | yes | `@ManyToOne` to `Madhhab` (knowledge module). Optional — not every user follows a single madhhab. |
| `websiteUrl` | `website_url` | TEXT | yes | Personal site. The `links` collection below is the preferred place for multi-platform links — `websiteUrl` is a first-class shortcut for the primary site. |

#### Denormalized counters (cached, eventually consistent)

These are kept in sync by `CounterCache` (Redis-backed) and reconciled
nightly. Treat them as approximate but fast — never use them for hard
business decisions (those re-count from the source table).

| Field | Column | Type | Default | Description |
|---|---|---|---|---|
| `followerCount` | `follower_count` | long | `0` | Number of users following this user. Bumped on `POST /users/{id}/follow`, decremented on unfollow / block. |
| `followingCount` | `following_count` | long | `0` | Number of users this user follows. |
| `researchCount` | `research_count` | int | `0` | Published research papers authored by this user. Drafts not counted. |
| `fatwaCount` | `fatwa_count` | int | `0` | Reserved for the fatwa subsystem (currently unused on this platform). |
| `profileViews` | `profile_views` | long | `0` | Visits to the public profile page. Bumped via Redis on each visit, batched-reconciled daily. |

#### Availability & content preferences

| Field | Column | Type | Default | Description |
|---|---|---|---|---|
| `isForHire` | `is_for_hire` | boolean | `false` | When `true`, the profile shows an "Available for hire" badge. Pure display flag — no marketplace logic attached. |
| `contentLanguage` | `content_language` | enum | `EN` | The default language filter for content this user publishes. Separate from `User.preferredLanguage` (which is the UI/email locale) so a user can read English UI but publish in Arabic. |

#### Sub-collections (eagerly ordered, lazily loaded)

All four `@OneToMany` collections cascade ALL + orphan-removal true,
so deleting the profile cascades to delete the rows below. Each is
sorted by `displayOrder ASC` in the serialised `ProfileResponse`.

| Field | Type | Description |
|---|---|---|
| `specializations` | `List<UserTopicSpecialization>` | Topics the user specialises in (e.g. "Fiqh", "Tafsir"). Renders as chips on the profile. |
| `links` | `List<UserLink>` | External links — Twitter, ORCID, personal site, etc. |
| `contacts` | `List<UserContact>` | Direct-message handles — Telegram, email, etc. |
| `attachments` | `List<UserAttachment>` | Files on the profile — CV, transcript, certificate scans. |

---

### 1.3 Sub-entities

#### `UserLink` — table `user_links`

External links displayed on the profile.

| Field | Column | Type | Nullable | Default | Description |
|---|---|---|---|---|---|
| `id` | `id` | UUID | no | generated | PK. |
| `profile` | `profile_id` | FK | no | — | Back-ref to the owning `UserProfile`. |
| `platform` | `platform` | enum | no | — | One of `LinkPlatform` values (TWITTER, ORCID, GITHUB, LINKEDIN, WEBSITE, …). |
| `description` | `description` | varchar(200) | no | — | Caption shown next to the link (e.g. "My research profile"). |
| `url` | `url` | TEXT | no | — | The actual URL. No server-side validation — store what the user typed. |
| `isPublic` | `is_public` | boolean | no | `true` | When `false`, the link is hidden from public profile views (visible only to the owner). |
| `displayOrder` | `display_order` | int | no | `0` | Ascending sort key. Smaller = shown first. |

#### `UserContact` — table `user_contacts`

Contact handles. Like `UserLink` but for direct-messaging surfaces
(Telegram, email, WhatsApp). Default visibility is **private** (opposite
of `UserLink`) since these are direct-contact channels.

| Field | Column | Type | Nullable | Default | Description |
|---|---|---|---|---|---|
| `id` | `id` | UUID | no | generated | PK. |
| `profile` | `profile_id` | FK | no | — | Back-ref to `UserProfile`. |
| `platform` | `platform` | enum | no | — | One of `ContactPlatform` values (EMAIL, TELEGRAM, WHATSAPP, PHONE, …). |
| `value` | `value` | varchar(200) | no | — | The handle / address as the user typed it. |
| `isPublic` | `is_public` | boolean | no | **`false`** | Default-private. Only the owner sees the value unless they explicitly opt-in to public visibility. |

#### `UserAttachment` — table `user_attachments`

Files uploaded to the profile (CV, transcript, certificate scans).

| Field | Column | Type | Nullable | Description |
|---|---|---|---|---|
| `id` | `id` | UUID | no | PK. |
| `profile` | `profile_id` | FK | no | Back-ref. |
| `fileUrl` | `file_url` | TEXT | no | Public proxied URL. |
| `s3Key` | `s3_key` | TEXT | yes | Internal R2 object key. Used to delete on remove. Nullable for back-compat with legacy rows that used direct URLs. |
| `fileName` | `file_name` | varchar(255) | no | Original filename (sanitised) — used as the suggested download name. |
| `fileType` | `file_type` | varchar(50) | no | MIME type, e.g. `application/pdf`. |
| `fileSize` | `file_size` | long | no | Bytes. |
| `description` | `description` | varchar(300) | yes | Optional caption shown in the attachments list. |

#### `UserTopicSpecialization` — table `user_topic_specializations`

Composite-key join table — no surrogate id. PK is `(profile_id, topic_id)`.

| Field | Column | Type | Nullable | Default | Description |
|---|---|---|---|---|---|
| `profile` | `user_id` *(historic column name)* | FK | no | — | Back-ref to `UserProfile` (despite the `user_id` column name). |
| `topic` | `topic_id` | FK | no | — | Reference to `Topic` (knowledge module). |
| `displayOrder` | `display_order` | int | no | `0` | Ascending sort key. |

> ⚠️ Column-name caveat: the join table uses `user_id` for the profile FK
> (the column was named when this was a direct `User`↔`Topic` join,
> before the `UserProfile` split). The Java field is `profile`. A future
> schema rename to `profile_id` is desirable but requires a coordinated
> migration.

---

### 1.4 Audit fields — inherited from `BaseAuditEntity`

Every entity above (except `UserTopicSpecialization`, which extends
nothing) inherits these audit columns through `@MappedSuperclass`.
They're populated automatically by `AuditingEntityListener` plus a
custom `@PrePersist` / `@PreUpdate` hook that captures the request
context.

| Field | Column | Type | Set by | Description |
|---|---|---|---|---|
| `createdAt` | `created_at` | timestamp | `@CreatedDate` | When the row was inserted. Never updated. |
| `updatedAt` | `updated_at` | timestamp | `@LastModifiedDate` | When the row was last mutated. |
| `createdBy` | `created_by` | UUID | `@CreatedBy` | UUID of the authenticated user at insert time. Null for system-created rows (registration, OAuth callback). |
| `updatedBy` | `updated_by` | UUID | `@LastModifiedBy` | UUID of the authenticated user at last update. |
| `createdByIp` | `created_by_ip` | varchar(45) | `@PrePersist` hook | Client IP at insert (X-Forwarded-For aware). IPv4 or IPv6. |
| `updatedByIp` | `updated_by_ip` | varchar(45) | `@PreUpdate` hook | Client IP at last update. |
| `createdByDevice` | `created_by_device` | varchar(300) | `@PrePersist` hook | User-Agent at insert (truncated to 300 chars). |
| `updatedByDevice` | `updated_by_device` | varchar(300) | `@PreUpdate` hook | User-Agent at last update. |
| `lastAction` | `last_action` | enum | `audit(...)` helper or default | High-level intent of the last change — one of `AuditAction` values (CREATE, UPDATE, FOLLOW, BLOCK, …). Defaults to `CREATE` on insert, `UPDATE` on update if not explicitly set. |
| `actionNote` | `action_note` | varchar(500) | `audit(...)` helper | Free-form note for the audit trail (e.g. "Blocked user: @spammer"). |

The audit fields are **not exposed** in `UserResponse` / `ProfileResponse` —
they're internal-only, surfaced through the audit-log subsystem.

---

## 2. Role enum (authorization surface)

The complete authorization model — **four values**, no other tiers:

| Role | Privileges | Auto badge |
|---|---|---|
| `USER` | Default for every signup | — |
| `RESEARCHER` | Can publish research papers | "Researcher" |
| `SCHOLAR` | Full scholar; **also implies RESEARCHER** authority (granted via `user.getAuthorities()` returning both `ROLE_SCHOLAR` and `ROLE_RESEARCHER`) | "Scholar" |
| `ADMIN` | Platform operator; gates `/api/v1/admin/*` | — |

The older `MODERATOR` / `EDITOR` / `SUPER_ADMIN` tiers are gone. The
`AccountType` and `VerificationTier` concepts were retired — **badges
are auto-derived from the role**, there is no separate verification
workflow.

---

## 3. DTOs

### `UserResponse` (auth identity payload)

Returned by `/users/me`, `/users/{id}`, `/users/username/{username}`,
`/users/email/{email}`, every `auth/*` endpoint that surfaces the user.

```jsonc
{
  "id":              "uuid",
  "fname":           "string",
  "lname":           "string",
  "username":        "string",     // the user-set handle, NEVER the email
  "email":           "string",
  "role":            "USER" | "RESEARCHER" | "SCHOLAR" | "ADMIN",
  "badges":          [ { "type": "SCHOLAR_VERIFIED", "label": "Scholar" } ],
  "isEmailVerified": true,
  "profile":         { ... ProfileResponse — null until the profile is created },
  "createdAt":       "2026-06-05T08:30:12Z"
}
```

### `ProfileResponse` (public surface)

Returned by `/users/me/profile`, `/users/{id}/profile`, and nested
inside `UserResponse`.

```jsonc
{
  "displayName":      "string",
  "avatarUrl":        "string (proxied through /api/v1/media/…)",
  "coverImageUrl":    "string",
  "profileBio":       "string",
  "selfDescriber":    "string (tagline shown under the name)",
  "location":         "string",
  "academicTitle":    "string",
  "institutionName":  "string",
  "madhhabId":        123,
  "madhhabName":      "Hanafi",
  "websiteUrl":       "string",
  "specializations":  [ { "topicId": 1, "topicName": "Tafsir", "displayOrder": 0 } ],
  "followerCount":    long,
  "followingCount":   long,
  "researchCount":    int,
  "fatwaCount":       int,
  "isForHire":        false,
  "isProfileLocked":  false,
  "contentLanguage":  "EN",
  "profileViews":     long,
  "links":            [ { id, platform, url, displayOrder } ],
  "contacts":         [ { id, platform, value, displayOrder } ],
  "attachments":      [ { id, fileUrl, fileName, mimeType, sizeBytes } ]
}
```

### Request DTOs (PATCH payloads — all fields optional, null = no change)

| DTO | Endpoint | Mutates |
|---|---|---|
| `UpdateUserProfileRequest` | `PATCH /users/me` | identity fields on `User` (fname, lname, username, orcidId, preferredLanguage) |
| `UpdateProfileRequest` | `PATCH /users/me/profile` | public profile fields on `UserProfile` (bio, displayName, selfDescriber, location, academicTitle, institutionName, madhhabId, websiteUrl, isForHire, contentLanguage, isProfileLocked) |
| `UpdateSpecializationsRequest` | `PATCH /users/me/profile/specializations` | replaces the `specializations` list wholesale |
| `AddLinkRequest` / `EditLinkRequest` | `POST` / `PATCH /users/me/links/...` | individual `UserLink` rows |
| `AddContactRequest` / `EditContactRequest` | `POST` / `PATCH /users/me/contacts/...` | individual `UserContact` rows |
| `AdminChangeRoleRequest` | `PATCH /admin/users/{id}/role` | the `role` field — admin-only |

---

## 4. Authentication endpoints

`@RequestMapping("/api/v1/auth")`

| Method | Path | Body | Returns | Notes |
|---|---|---|---|---|
| `POST` | `/register` | `{ fname, lname, username, email, password }` | `UserResponse` + auth tokens (Set-Cookie + body) | Creates the User + empty UserProfile in one transaction. Email verification link is sent. |
| `POST` | `/login` | `{ usernameOrEmail, password }` | `AuthResponse` ({ accessToken, refreshToken, user: UserResponse }) | Accepts both email AND username |
| `POST` | `/refresh` | `{ refreshToken }` OR cookie | `{ accessToken, refreshToken }` | **Frontend must persist + send the refresh token** — see `REALTIME_FRONTEND_GUIDE.md` §10 |
| `POST` | `/logout` | — | 204 | Invalidates the current refresh token |
| `POST` | `/logout-all` | — | 204 | Invalidates ALL refresh tokens for this user (every device signed out) |
| `POST` | `/change-password` | `{ currentPassword, newPassword }` | 204 | Auth required |

### Token semantics

- **Access JWT**: short-lived (≈ 1h). Carries `sub` = user UUID.
- **Refresh token**: long-lived, stored server-side in `refresh_tokens`. Rotates on every refresh.
- Either may be sent as `Authorization: Bearer …` header OR via cookie. **`EventSource` cannot send headers** — pass the access token via `?token=` query param on SSE streams.

---

## 5. Identity endpoints (`/api/v1/users`)

Owned by `UserController` — handles the `User` half of the model.

| Method | Path | Auth | Returns | Notes |
|---|---|---|---|---|
| `GET` | `/me` | required | `UserResponse` | Current user (profile inline) |
| `GET` | `/{id}` | optional | `UserResponse` | Public lookup by UUID |
| `GET` | `/username/{username}` | optional | `UserResponse` | Public lookup by handle |
| `GET` | `/email/{email}` | required (admin) | `UserResponse` | Email lookup — restricted |
| `PATCH` | `/me` | required | `UserResponse` | Body: `UpdateUserProfileRequest`. Updates User-side identity fields (NOT profile fields) |
| `GET` | `/{id}/stats` | optional | `UserStatsResponse` | Aggregate counters across user's content |
| `GET` | `/search?q=…&page=…&size=…` | optional | `Page<UserResponse>` | Full-text on username + display name |
| `DELETE` | `/me` | required | 204 | Soft delete — sets `deletedAt`; auth invalidated |

### `PATCH /users/me` — what's mutable

```jsonc
// UpdateUserProfileRequest — all fields optional
{
  "fname":             "Akar",
  "lname":             "Arkan",
  "username":          "akar",            // must be unique; rejected if taken
  "orcidId":           "0000-0002-1825-0097",
  "preferredLanguage": "EN"               // EN | AR | KU | ...
}
```

What's **not** in this endpoint:
- `email` — change-of-email is a separate flow (re-verification required).
- `password` — `POST /auth/change-password`.
- `role` — `PATCH /admin/users/{id}/role` (admin-only).
- Profile fields (bio, avatar, etc.) — see [§6](#6-profile-endpoints-apiv1usersprofile).

---

## 6. Profile endpoints (`/api/v1/users/.../profile`)

Owned by `UserProfileController` — handles the `UserProfile` half.

| Method | Path | Body | Returns | Notes |
|---|---|---|---|---|
| `GET` | `/{id}/profile` | — | `ProfileResponse` | Public profile of any user |
| `GET` | `/me/profile` | — | `ProfileResponse` | Current user's profile |
| `PATCH` | `/me/profile` | `UpdateProfileRequest` | `ProfileResponse` | All fields optional, null = unchanged |
| `POST` | `/me/profile/avatar` | multipart `image` | `ProfileResponse` | Replaces existing avatar; old R2 file is deleted |
| `DELETE` | `/me/profile/avatar` | — | `ProfileResponse` | Removes avatar |
| `POST` | `/me/profile/cover` | multipart `image` | `ProfileResponse` | Replaces existing cover |
| `DELETE` | `/me/profile/cover` | — | `ProfileResponse` | Removes cover |
| `PATCH` | `/me/profile/specializations` | `UpdateSpecializationsRequest` | `ProfileResponse` | Replaces the full specializations list |

### `PATCH /users/me/profile` — what's mutable

```jsonc
// UpdateProfileRequest — all fields optional
{
  "displayName":      "Akar Arkan",
  "profileBio":       "Building IRC.",
  "selfDescriber":    "Researcher · Engineer",
  "location":         "Erbil, Iraq",
  "academicTitle":    "MSc Computer Science",
  "institutionName":  "Salahaddin University",
  "madhhabId":        2,                  // FK to madhhabs (knowledge module)
  "websiteUrl":       "https://akar.dev",
  "isForHire":        true,
  "contentLanguage":  "EN",
  "isProfileLocked":  false               // when true, only followers see the profile
}
```

### Avatar / cover upload contract

```
POST /api/v1/users/me/profile/avatar
Content-Type: multipart/form-data
Part:  image  (binary, jpeg/png/webp, ≤ 5 MB recommended)
```

Returns a refreshed `ProfileResponse`. The `avatarUrl` value will be a
**proxied URL** like `/api/v1/media/users/{userId}/avatar/{uuid}.jpg`
or, if `MEDIA_PUBLIC_URL` env var is set on the deploy, an absolute
URL like `https://<host>/api/v1/media/...` — both work cross-origin.

---

## 7. Links + contacts (sub-resources of profile)

Both live under `/api/v1/users/me/...` for the current user.

### Links — `UserLink`

```jsonc
// AddLinkRequest
{ "platform": "TWITTER", "url": "https://twitter.com/akar", "displayOrder": 0 }
```

| Method | Path | Body | Returns |
|---|---|---|---|
| `POST` | `/me/links` | `AddLinkRequest` | `UserLinkResponse` |
| `PATCH` | `/me/links/{linkId}` | `EditLinkRequest` (partial) | `UserLinkResponse` |
| `DELETE` | `/me/links/{linkId}` | — | 204 |

### Contacts — `UserContact`

```jsonc
// AddContactRequest
{ "platform": "EMAIL", "value": "akar@example.com", "displayOrder": 0 }
```

| Method | Path | Body | Returns |
|---|---|---|---|
| `POST` | `/me/contacts` | `AddContactRequest` | `UserContactResponse` |
| `PATCH` | `/me/contacts/{contactId}` | `EditContactRequest` | `UserContactResponse` |
| `DELETE` | `/me/contacts/{contactId}` | — | 204 |

Both are sorted by `displayOrder` ASC when serialized into `ProfileResponse`.

---

## 8. Account lifecycle

| Event | What happens | Reversibility |
|---|---|---|
| Register | `User` + empty `UserProfile` row created in one transaction; email verification link sent | — |
| Email verify | `User.emailVerifiedAt` is set; `isEnabled` flips true | Re-verification possible on email change |
| Login | `User.lastLoginAt` updated; refresh-token row created in `refresh_tokens` | — |
| Logout | The specific refresh token is deleted | Re-login required |
| Logout-all | All refresh-token rows for the user are deleted | Every device signed out |
| Change password | New BCrypt hash written; existing refresh tokens are NOT invalidated automatically — call `/logout-all` to do that explicitly | — |
| Role change (admin) | `User.role` updated; granted authorities re-derived on next request | — |
| Soft delete (`DELETE /users/me`) | `User.deletedAt` set; `isEnabled` becomes false; auth context cleared | Soft — undelete is possible via admin endpoint but not exposed to user |

---

## 9. Error model

All errors return the standard envelope (see `GlobalExceptionHandler`):

```jsonc
{
  "status":    409,
  "error":     "Conflict",
  "errorCode": "USERNAME_TAKEN",
  "message":   "Username 'akar' is already in use.",
  "path":      "/api/v1/users/me",
  "traceId":   "a2c1...",
  "details":   { "field": "username" }      // optional
}
```

Common codes for the user model:

| HTTP | errorCode | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | `@Valid` body failed validation; `fieldErrors` array attached |
| 401 | `AUTH_BAD_CREDENTIALS` | Wrong email/username + password |
| 401 | `AUTH_REFRESH_TOKEN_MISSING` | `/auth/refresh` called without a refresh token (body or cookie) |
| 401 | `AUTH_ACCOUNT_DISABLED` | `User.isEnabled == false` (e.g. unverified email) |
| 403 | `FORBIDDEN` | Role insufficient for the endpoint (e.g. non-admin hitting `/admin/*`) |
| 404 | `USER_NOT_FOUND` | Lookup by id / username / email missed |
| 409 | `EMAIL_TAKEN`, `USERNAME_TAKEN` | Uniqueness violated on registration or PATCH |
| 422 | `INVALID_ROLE_TRANSITION` | Admin trying to demote themselves or assign an unsupported role |

---

## 10. Cheat sheet

The 10 endpoints the frontend uses 95% of the time:

```
POST   /api/v1/auth/register           — sign up
POST   /api/v1/auth/login              — sign in
POST   /api/v1/auth/refresh            — rotate tokens
POST   /api/v1/auth/logout             — sign out this session

GET    /api/v1/users/me                — current user + inline profile
GET    /api/v1/users/{id}              — public user
GET    /api/v1/users/{id}/profile      — public profile

PATCH  /api/v1/users/me                — change identity (fname/lname/username/orcidId/lang)
PATCH  /api/v1/users/me/profile        — change profile (bio/title/institution/...)
POST   /api/v1/users/me/profile/avatar — change avatar (multipart)
```

For everything else — notifications, follows, suggestions, close
friends, blocks, search-with-facets, admin role changes — see
`USER_API.md` (the long-form historical reference) and
`NOTIFICATIONS_API.md`.

---

## Implementation pointers (for backend engineers)

- Entity: `app/user/entity/User.java`, `app/user/entity/UserProfile.java`
- Role enum: `app/user/enums/Role.java`
- Controllers: `app/user/controller/AuthController.java`, `UserController.java`, `UserProfileController.java`, `AdminUserController.java`
- Mapper: `app/user/mapper/UserMapper.java` (badge derivation lives here in `resolveBadges`)
- Auth: `app/security/jwt/JwtTokenProvider.java`, `app/security/jwt/JwtAuthenticationFilter.java`
- The `User.getAuthorities()` override grants `ROLE_SCHOLAR` + `ROLE_RESEARCHER` together when role is SCHOLAR — this is why `@PreAuthorize("hasRole('RESEARCHER')")` admits scholars without needing a second annotation.

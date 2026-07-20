# Users API — Identity, Links, Contacts, Stats

Identity reads and auth-layer identity writes for the IRC platform, plus the
profile stat row and the links/contacts sub-resources. Display-layer profile
fields (bio, avatar, specializations, …) live in [profile.md](profile.md);
the social graph in [social.md](social.md); user search in
[search.md](search.md).

**Base path:** `/api/v1/users`

**Auth:** `Authorization: Bearer <accessToken>` (see [auth.md](auth.md)) on
every endpoint marked *JWT required*. Errors use the unified envelope in
[../errors/error-handling.md](../errors/error-handling.md).

> **username vs email:** `username` is the **user-set public handle** shown on
> posts and mentions. `email` is the private contact address. They are fully
> independent — the handle is never derived from the email.

---

## `UserResponse`

The canonical user shape returned by most endpoints in this module.

```json
{
  "id":              "550e8400-e29b-41d4-a716-446655440000",
  "fname":           "Ahmad",
  "lname":           "Al-Rashid",
  "username":        "ahmad.rashid",
  "email":           "ahmad@example.com",
  "role":            "SCHOLAR",
  "badges": [
    { "type": "VERIFIED_SCHOLAR", "label": "Scholar", "colorKey": "teal", "icon": "ti-certificate", "priority": 1 }
  ],
  "isEmailVerified": true,
  "profile":         { /* ProfileResponse — see profile.md */ },
  "createdAt":       "2025-01-15T08:30:00"
}
```

| Field | Type | Notes |
|---|---|---|
| `id` | uuid | — |
| `fname` / `lname` | string | Auth-layer name fields |
| `username` | string | User-set handle |
| `email` | string | Private contact address |
| `role` | enum | `USER` / `RESEARCHER` / `SCHOLAR` / `ADMIN` — the only four roles |
| `badges` | array | **Auto-derived from `role`**: `VERIFIED_SCHOLAR` for scholars, `VERIFIED_RESEARCHER` for researchers, empty otherwise. No verification workflow |
| `isEmailVerified` | boolean | — |
| `profile` | object \| null | Nested [`ProfileResponse`](profile.md#profileresponse) |
| `createdAt` | datetime | — |

---

## `GET /me`

```
GET /api/v1/users/me
```

**Auth:** Bearer JWT required

Returns the caller's own account, including owner-only profile fields
(`profile.profileViews`).

**Response:** `200 OK` — [`UserResponse`](#userresponse)

**Errors:**

| Status | errorCode | When |
|---|---|---|
| 401 | `AUTH_TOKEN_INVALID` | Missing/invalid token |
| 404 | `USER_NOT_FOUND` | Account soft-deleted since the token was issued |

---

## `GET /{id}`

```
GET /api/v1/users/{id}
```

**Auth:** none (public)

**Path params:**

| Param | Type | Description |
|---|---|---|
| `id` | UUID | Target user id |

**Response:** `200 OK` — [`UserResponse`](#userresponse) (public view —
`profile.profileViews` reads `0`, only public links/contacts included)

**Errors:**

| Status | errorCode | When |
|---|---|---|
| 404 | `USER_NOT_FOUND` | No active (non-deleted) user with that id |

---

## `GET /username/{username}`

```
GET /api/v1/users/username/{username}
```

**Auth:** none (public)

Looks up a user by handle. Forgiving: if the path value contains `@` it is
first tried as an email, and either form falls back to the other lookup before
returning 404.

**Response:** `200 OK` — [`UserResponse`](#userresponse)

**Errors:**

| Status | errorCode | When |
|---|---|---|
| 404 | `USER_NOT_FOUND` | No active user with that username (or email fallback) |

---

## `GET /email/{email}`

```
GET /api/v1/users/email/{email}
```

**Auth:** none (public)

Exact-match email lookup. This is the **only** way to find a user by email —
the [search endpoint](search.md) deliberately does not index emails.

**Response:** `200 OK` — [`UserResponse`](#userresponse)

**Errors:**

| Status | errorCode | When |
|---|---|---|
| 404 | `USER_NOT_FOUND` | No active user with that email |

---

## `GET /{id}/stats`

```
GET /api/v1/users/{id}/stats
```

**Auth:** none (public, anonymous-safe)

Live profile stat counts, computed on demand from each source of truth
(posts/reels from Cassandra, research/questions/follows from Postgres). Works
for your own profile or anyone else's.

> **Caching (current behavior):** the response is **cached server-side for
> ~30 seconds per user** (Redis, `sync=true`). A profile-view burst collapses
> to one count sweep per user per 30 s; sub-30-second staleness is expected.

**Response:** `200 OK` — `UserStatsResponse` with **six live counts**:

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
| `postCount` | Cassandra `posts_by_author` | Live **non-reel** posts (total − reels) |
| `reelCount` | Cassandra `posts_by_author` | Live posts with `post_type = REEL` |
| `researchCount` | Postgres `researches` | `PUBLISHED`, not soft-deleted |
| `questionCount` | Postgres `questions` | Not soft-deleted |
| `followerCount` | Postgres `user_follows` | — |
| `followingCount` | Postgres `user_follows` | — |

Each count is isolated — if one store is briefly unavailable that field
degrades to `0` instead of failing the whole row. The denormalized counter
columns on `user_profiles` are **not** maintained; use this endpoint for the
stat row.

---

## `PATCH /me`

```
PATCH /api/v1/users/me
```

**Auth:** Bearer JWT required

Updates the **auth-layer identity fields** only: `fname`, `lname`,
`username`. Use [`PATCH /me/profile`](profile.md#patch-meprofile) for display
fields. All fields are optional; omitted fields keep their current value.

**Request body:**

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
| `username` | 3–50 chars, pattern `[a-zA-Z0-9._-]+`. Uniqueness checked against active users (case-insensitive same-value change is a no-op) |

**Response:** `200 OK` — [`UserResponse`](#userresponse)

**Errors:**

| Status | errorCode | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Size/pattern constraint violated |
| **409** | `USER_DUPLICATE` | **New username already taken** |
| 401 | `AUTH_TOKEN_INVALID` | Not authenticated |

---

## Links CRUD

External links (ORCID, GitHub, personal site, …) attached to the caller's
profile. Platforms: `FACEBOOK`, `TWITTER`, `INSTAGRAM`, `LINKEDIN`, `YOUTUBE`,
`GITHUB`, `ORCID`, `RESEARCHGATE`, `GOOGLE_SCHOLAR`, `TELEGRAM`,
`PERSONAL_WEBSITE`, `OTHER`. Only links with `isPublic = true` appear in
profile responses.

### `POST /me/links`

```
POST /api/v1/users/me/links
```

**Auth:** Bearer JWT required

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

| Field | Type | Constraints |
|---|---|---|
| `platform` | enum | Required |
| `description` | string | Required, max 200 chars |
| `url` | string | Required; must be unique among the caller's links (case-insensitive) |
| `isPublic` | boolean | Default `false` if omitted |
| `displayOrder` | int | Sort order in the profile |

**Response:** `201 Created` — `UserLinkResponse`

```json
{
  "id":           "9b2e6a2e-...",
  "platform":     "ORCID",
  "description":  "My research profile",
  "url":          "https://orcid.org/0000-0002-1825-0097",
  "isPublic":     true,
  "displayOrder": 1
}
```

**Errors:**

| Status | errorCode | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Missing/oversized fields |
| 409 | `LINK_DUPLICATE` | Same URL already on the profile |
| 404 | `USERPROFILE_NOT_FOUND` | Caller has no profile row |

### `PATCH /me/links/{linkId}`

```
PATCH /api/v1/users/me/links/{linkId}
```

**Auth:** Bearer JWT required. All body fields optional — `null`/omitted means
"no change" (same shape as the add request).

**Response:** `200 OK` — `UserLinkResponse`

**Errors:**

| Status | errorCode | When |
|---|---|---|
| 404 | `LINK_NOT_FOUND` | `linkId` not found **on the caller's own profile** (you can only edit your own links) |

### `DELETE /me/links/{linkId}`

```
DELETE /api/v1/users/me/links/{linkId}
```

**Auth:** Bearer JWT required

**Response:** `204 No Content`

**Errors:**

| Status | errorCode | When |
|---|---|---|
| 404 | `LINK_NOT_FOUND` | `linkId` not on the caller's profile |

---

## Contacts CRUD

Direct contact handles (Telegram, WhatsApp, phone, …). Platforms: `TELEGRAM`,
`WHATSAPP`, `EMAIL`, `PHONE`, `VIBER`, `SIGNAL`, `SKYPE`, `OTHER`. Only
contacts with `isPublic = true` appear in profile responses.

### `POST /me/contacts`

```
POST /api/v1/users/me/contacts
```

**Auth:** Bearer JWT required

**Request body:**

```json
{
  "platform": "TELEGRAM",
  "value":    "@ahmad_rashid",
  "isPublic": true
}
```

| Field | Type | Constraints |
|---|---|---|
| `platform` | enum | Required |
| `value` | string | Required, max 200 chars. `(platform, value)` must be unique per profile (case-insensitive) |
| `isPublic` | boolean | Default `false` if omitted |

**Response:** `201 Created` — `UserContactResponse`

```json
{
  "id":       "3f7c1d80-...",
  "platform": "TELEGRAM",
  "value":    "@ahmad_rashid",
  "isPublic": true
}
```

**Errors:**

| Status | errorCode | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Missing/oversized fields |
| 409 | `CONTACT_DUPLICATE` | Same platform+value already on the profile |
| 404 | `USERPROFILE_NOT_FOUND` | Caller has no profile row |

### `PATCH /me/contacts/{contactId}`

```
PATCH /api/v1/users/me/contacts/{contactId}
```

**Auth:** Bearer JWT required. All body fields optional (same shape as add).

**Response:** `200 OK` — `UserContactResponse`

**Errors:**

| Status | errorCode | When |
|---|---|---|
| 404 | `CONTACT_NOT_FOUND` | `contactId` not on the caller's profile |

### `DELETE /me/contacts/{contactId}`

```
DELETE /api/v1/users/me/contacts/{contactId}
```

**Auth:** Bearer JWT required

**Response:** `204 No Content`

**Errors:**

| Status | errorCode | When |
|---|---|---|
| 404 | `CONTACT_NOT_FOUND` | `contactId` not on the caller's profile |

---

## `DELETE /me`

```
DELETE /api/v1/users/me
```

**Auth:** Bearer JWT required

**Soft-deletes** the caller's account and kills every session.

**Response:** `204 No Content`

**Side effects:**

- Revokes **all** of the user's refresh tokens (every device logged out; the
  current access token dies at its short TTL)
- Sets `users.deleted_at = now()` and `is_enabled = false`
- The account disappears from all public lookups (`GET /{id}`, search,
  followers lists, …) which filter on `deleted_at IS NULL`
- Subsequent logins fail with the generic `401 AUTH_BAD_CREDENTIALS` /
  `AUTH_ACCOUNT_DISABLED`

**Errors:**

| Status | errorCode | When |
|---|---|---|
| 401 | `AUTH_TOKEN_INVALID` | Not authenticated |
| 404 | `USER_NOT_FOUND` | Account already deleted |

# User Profile API — Display Layer

Public display data for a user: display name, bio, avatar, cover image,
academic details, madhhab, specializations. Identity fields (`fname`, `lname`,
`username`, `email`) live on the auth layer — see [users.md](users.md).

**Base path:** `/api/v1/users`

**Auth:** `Authorization: Bearer <accessToken>` on all `/me/...` endpoints.
Errors use the unified envelope in
[../errors/error-handling.md](../errors/error-handling.md).

Related: [auth.md](auth.md) · [users.md](users.md) · [social.md](social.md) ·
[search.md](search.md) · [security-model.md](security-model.md) ·
[knowledge taxonomy](../knowledge/taxonomy.md) (topic/madhhab vocabularies
behind `specializations` and `madhhabId`)

---

## Model notes — the User / UserProfile split

| Layer | Entity | Holds |
|---|---|---|
| Auth | `User` | Credentials, `username`, `email`, role, security flags |
| Display | `UserProfile` | Everything shown on a profile page — bio, avatar, cover, academic info, links, contacts, specializations |

Every account has exactly one profile row, created automatically at
[registration](auth.md#post-register).

- **Roles:** exactly four — `USER`, `RESEARCHER`, `SCHOLAR`, `ADMIN`.
  `SCHOLAR` implies researcher privileges. There are no other tiers or
  account-type dimensions.
- **Badges are auto-derived from the role** — there is no verification
  workflow. `role = SCHOLAR` → `VERIFIED_SCHOLAR` badge; `role = RESEARCHER`
  → `VERIFIED_RESEARCHER`. A scholar shows only the Scholar badge.
- All profile endpoints return the full [`UserResponse`](users.md#userresponse)
  (with the profile nested) so the client can refresh its user object in one go.

## `ProfileResponse`

Nested inside `UserResponse` as `profile`:

```json
{
  "displayName":     "Sheikh Ahmad Al-Rashid",
  "avatarUrl":       "https://cdn.example.com/users/avatars/....jpg",
  "coverImageUrl":   "https://cdn.example.com/users/covers/....jpg",
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
  "links":           [ { "id": "…", "platform": "ORCID", "description": "…", "url": "…", "isPublic": true, "displayOrder": 1 } ],
  "contacts":        [ { "id": "…", "platform": "TELEGRAM", "value": "@ahmad_rashid", "isPublic": true } ],
  "attachments":     [ { "id": "…", "fileUrl": "…", "fileName": "CV.pdf", "fileType": "application/pdf", "fileSize": 204800, "description": "CV", "uploadedAt": "…" } ]
}
```

| Field | Type | Notes |
|---|---|---|
| `displayName` | string | Defaults to `fname + lname` at registration |
| `avatarUrl` / `coverImageUrl` | string \| null | Public CDN URLs (R2/S3-backed) |
| `madhhabId` / `madhhabName` | int / string \| null | From the knowledge base |
| `specializations` | `TopicDto[]` | Ordered topic expertise tags |
| `followerCount` … `fatwaCount` | numbers | **Denormalized counters — not maintained, may read 0.** Use [`GET /{id}/stats`](users.md#get-idstats) for live counts |
| `isProfileLocked` | boolean | When `true`, new follows are rejected — see [social.md](social.md#post-idfollow) |
| `contentLanguage` | string \| null | Language enum name (e.g. `AR`, `EN`, `CKB`) |
| `profileViews` | long | **Owner-only** — reads `0` on public views |
| `links` / `contacts` | arrays | Only entries with `isPublic = true` are serialized |

---

## `GET /{id}/profile`

```
GET /api/v1/users/{id}/profile
```

**Auth:** none (public)

Public profile read.

> **Caching:** cached server-side (Redis, `user-profile` cache, ~5 min TTL) and
> evicted on any profile mutation, so reads are hot-path cheap and updates are
> visible immediately after a write.

**Path params:**

| Param | Type | Description |
|---|---|---|
| `id` | UUID | Target user id |

**Response:** `200 OK` — [`UserResponse`](users.md#userresponse) with the full
profile nested (public view: `profileViews = 0`, only public links/contacts).

**Errors:**

| Status | errorCode | When |
|---|---|---|
| 404 | `USER_NOT_FOUND` | No active user with that id |

---

## `GET /me/profile`

```
GET /api/v1/users/me/profile
```

**Auth:** Bearer JWT required

Owner view — includes `profileViews`. Not cached.

**Response:** `200 OK` — [`UserResponse`](users.md#userresponse)

**Errors:**

| Status | errorCode | When |
|---|---|---|
| 401 | `AUTH_TOKEN_INVALID` | Not authenticated |
| 404 | `USER_NOT_FOUND` | Account deleted |

---

## `PATCH /me/profile`

```
PATCH /api/v1/users/me/profile
```

**Auth:** Bearer JWT required

Partial update of display fields. **All fields optional — omitted/`null`
fields keep their current value.** Does not touch `fname`/`lname`/`username`
(use [`PATCH /me`](users.md#patch-me)).

**Request body:**

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

| Field | Type | Constraints |
|---|---|---|
| `displayName` | string | Max 120 chars |
| `profileBio` | string | Free text |
| `selfDescriber` | string | Max 200 chars |
| `location` | string | Max 200 chars |
| `academicTitle` | string | Max 150 chars |
| `institutionName` | string | Max 200 chars |
| `madhhabId` | int | Must exist in the knowledge base |
| `websiteUrl` | string | — |
| `isForHire` | boolean | — |
| `isProfileLocked` | boolean | `true` blocks new followers |
| `contentLanguage` | string | Must match a `Language` enum value (case-insensitive) |

**Response:** `200 OK` — [`UserResponse`](users.md#userresponse)

**Side effects:** evicts the `user-profile` cache.

**Errors:**

| Status | errorCode | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Size constraint violated |
| 400 | `INVALID_LANGUAGE` | Unknown `contentLanguage` code |
| 404 | `MADHHAB_NOT_FOUND` | Unknown `madhhabId` |
| 401 | `AUTH_TOKEN_INVALID` | Not authenticated |

---

## Avatar

### `POST /me/profile/avatar`

```
POST /api/v1/users/me/profile/avatar
Content-Type: multipart/form-data
```

**Auth:** Bearer JWT required

**Form fields:**

| Field | Type | Constraints |
|---|---|---|
| `image` | file | Required. `image/jpeg`, `image/png`, `image/webp`, or `image/gif`; filename must not contain path separators |

**Response:** `200 OK` — [`UserResponse`](users.md#userresponse) with the new
`profile.avatarUrl`.

**Side effects:**

- Uploads to R2/S3 under `users/avatars/{userId}`; deletes the previous avatar
  object if one existed
- Updates `avatar_url` + `avatar_s3_key`; evicts the `user-profile` cache

**Errors:**

| Status | errorCode | When |
|---|---|---|
| 400 | `EMPTY_FILE` | No file / empty file |
| 400 | `INVALID_FILE_TYPE` | Content type not in the allowed image set |
| 400 | `MISSING_FILENAME` / `INVALID_FILENAME` | Blank filename, or contains `..`, `/`, `\` |
| 413 | `FILE_TOO_LARGE` | Exceeds the multipart upload limit |
| 401 | `AUTH_TOKEN_INVALID` | Not authenticated |

### `DELETE /me/profile/avatar`

```
DELETE /api/v1/users/me/profile/avatar
```

**Auth:** Bearer JWT required

**Response:** `200 OK` — [`UserResponse`](users.md#userresponse) with
`profile.avatarUrl = null`.

**Side effects:** deletes the R2/S3 object (best-effort), nulls both DB
columns, evicts the `user-profile` cache. Idempotent — succeeds even when no
avatar is set.

---

## Cover image

### `POST /me/profile/cover`

```
POST /api/v1/users/me/profile/cover
Content-Type: multipart/form-data
```

**Auth:** Bearer JWT required

Same contract as the avatar upload — form field `image`, same type/filename
validations and errors. Stored under `users/covers/{userId}`; replaces any
previous cover object.

**Response:** `200 OK` — [`UserResponse`](users.md#userresponse) with the new
`profile.coverImageUrl`.

### `DELETE /me/profile/cover`

```
DELETE /api/v1/users/me/profile/cover
```

**Auth:** Bearer JWT required

**Response:** `200 OK` — [`UserResponse`](users.md#userresponse) with
`profile.coverImageUrl = null`. Same side effects as avatar removal.

---

## `PATCH /me/profile/specializations`

```
PATCH /api/v1/users/me/profile/specializations
```

**Auth:** Bearer JWT required

**Replaces the full specialization list atomically** — the existing list is
cleared and the submitted list becomes the new one. Send the complete desired
set; an empty array clears all specializations.

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

| Field | Type | Constraints |
|---|---|---|
| `specializations` | array | Required (may be empty) |
| `specializations[].topicId` | int | Required; must exist in the knowledge base |
| `specializations[].displayOrder` | int | Sort order on the profile |

**Response:** `200 OK` — [`UserResponse`](users.md#userresponse) —
`profile.specializations` is returned hydrated with `nameEn` / `nameAr` /
`nameCkb` from the knowledge base.

**Side effects:** deletes + reinserts `user_topic_specializations` rows;
evicts the `user-profile` cache.

**Errors:**

| Status | errorCode | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | `specializations` missing or `topicId` null |
| 404 | `TOPIC_NOT_FOUND` | Any `topicId` unknown (whole request rolls back) |
| 401 | `AUTH_TOKEN_INVALID` | Not authenticated |

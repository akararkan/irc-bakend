# User Social Graph API — Follow, Block, Restrict, Suggestions

The social graph between users: follow/unfollow, block/unblock, silent
restrict/unrestrict, relationship snapshots, friend suggestions, and the
who-to-follow ranking.

**Base path:** `/api/v1/users`

**Auth:** `Authorization: Bearer <accessToken>` (see [auth.md](auth.md)) on
every mutating endpoint; follower/following lists are public and
`/who-to-follow` works anonymously. Errors use the unified envelope in
[../errors/error-handling.md](../errors/error-handling.md).

Related: [users.md](users.md) · [profile.md](profile.md) ·
[search.md](search.md) · [security-model.md](security-model.md)

---

## Shared response shapes

### `SocialActionResponse`

Returned by every social mutation so the UI can refresh without a follow-up
call:

```json
{
  "action":             "FOLLOWED",
  "targetId":           "550e8400-e29b-41d4-a716-446655440000",
  "targetUsername":     "ahmad.rashid",
  "targetProfileImage": "https://cdn.example.com/users/avatars/ahmad.jpg",
  "updatedStatus": {
    "isFollowing":     true,
    "isBlocking":      false,
    "isRestricting":   false,
    "isBlockedByThem": false,
    "followerCount":   1241,
    "followingCount":  85
  },
  "performedAt": "2026-07-20T10:00:00"
}
```

| Field | Type | Notes |
|---|---|---|
| `action` | string | `FOLLOWED`, `UNFOLLOWED`, `BLOCKED`, `UNBLOCKED`, `RESTRICTED`, `UNRESTRICTED` |
| `targetId` / `targetUsername` / `targetProfileImage` | — | The other user |
| `updatedStatus` | object | The relationship state **after** the action (see below) |
| `performedAt` | datetime | — |

### `SocialStatusResponse`

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

| Field | Type | Meaning |
|---|---|---|
| `isFollowing` | boolean | Caller → target follow edge exists |
| `isBlocking` | boolean | Caller blocks the target |
| `isRestricting` | boolean | Caller restricts the target |
| `isBlockedByThem` | boolean | Target blocks the caller |
| `followerCount` / `followingCount` | long | **Target's** live counts from `user_follows` |

---

## `POST /{id}/follow`

```
POST /api/v1/users/{id}/follow
```

**Auth:** Bearer JWT required

Follows the target user. **Idempotent:** following someone you already follow
returns `200` with `action = "FOLLOWED"` and the current state — no error.

**Path params:** `id` — UUID of the user to follow.

**Response:** `200 OK` —
[`SocialActionResponse`](#socialactionresponse) with `action = "FOLLOWED"`.

**Side effects:**

- Inserts the `user_follows` edge
- **Feed backfill (async):** the follower gets the followed user's **~50 most
  recent posts** copied into their home-feed partition in the background, so
  the feed shows content immediately instead of waiting for the next post
  (best-effort; `ONLY_ME` posts are skipped)
- Publishes a `UserFollowedEvent` (RabbitMQ) → the consumer creates an
  aggregated **`NEW_FOLLOWER` notification** for the target
- Records the follow on the actor's activity history
- Evicts the `user-following-ids` cache

**Errors:**

| Status | errorCode | When |
|---|---|---|
| 400 | `SELF_ACTION_NOT_ALLOWED` | Attempted self-follow |
| **403** | **`FOLLOW_BLOCKED_RELATIONSHIP`** | A block edge exists between the two users **in either direction** |
| **403** | **`FOLLOW_PROFILE_LOCKED`** | Target's `isProfileLocked = true` |
| 404 | `USER_NOT_FOUND` | Target does not exist or is deleted |
| 401 | `AUTH_TOKEN_INVALID` | Not authenticated |

---

## `DELETE /{id}/follow`

```
DELETE /api/v1/users/{id}/follow
```

**Auth:** Bearer JWT required

**Response:** `200 OK` — `SocialActionResponse` with `action = "UNFOLLOWED"`.

**Side effects:** deletes the follow edge, publishes `UserUnfollowedEvent`
(cleans up the corresponding `NEW_FOLLOWER` notification), evicts the
`user-following-ids` cache.

**Errors:**

| Status | errorCode | When |
|---|---|---|
| 400 | `SELF_ACTION_NOT_ALLOWED` | Self-unfollow |
| 404 | `RESOURCE_NOT_FOUND` | You are not following this user |
| 401 | `AUTH_TOKEN_INVALID` | Not authenticated |

---

## `GET /{id}/followers` · `GET /{id}/following`

```
GET /api/v1/users/{id}/followers?page=0&size=20
GET /api/v1/users/{id}/following?page=0&size=20
```

**Auth:** none (public)

Offset-paginated lists of who follows the user / whom the user follows.

**Query params:**

| Param | Type | Default | Description |
|---|---|---|---|
| `page` | int | `0` | Zero-based page index |
| `size` | int | `20` | Page size |
| `sort` | string | — | Standard Spring sort expression (optional) |

**Response:** `200 OK` — `Page<UserResponse>`:

```json
{
  "content": [ { /* UserResponse — see users.md */ } ],
  "pageable":         { "pageNumber": 0, "pageSize": 20 },
  "totalElements":    1241,
  "totalPages":       63,
  "number":           0,
  "size":             20,
  "numberOfElements": 20,
  "first":            true,
  "last":             false,
  "empty":            false
}
```

**Errors:**

| Status | errorCode | When |
|---|---|---|
| 404 | `USER_NOT_FOUND` | `{id}` does not exist or is deleted |

---

## `POST /{id}/block`

```
POST /api/v1/users/{id}/block
```

**Auth:** Bearer JWT required

Blocks the target. Blocking is the heaviest edge in the graph:

- **Tears down follow edges in both directions** (they unfollow you, you
  unfollow them)
- **Supersedes restrict** — an existing restriction on the target is removed
- While the block exists, neither side can follow the other
  (`FOLLOW_BLOCKED_RELATIONSHIP`)

**Response:** `200 OK` — `SocialActionResponse` with `action = "BLOCKED"`.

**Side effects:** inserts `user_blocks`, deletes all follow edges between the
pair, removes any caller→target restriction, publishes `UserBlockedEvent`,
evicts the `user-blocked-ids` and `user-following-ids` caches.

**Errors:**

| Status | errorCode | When |
|---|---|---|
| 400 | `SELF_ACTION_NOT_ALLOWED` | Self-block |
| 409 | `RESOURCE_DUPLICATE` | Already blocking this user |
| 404 | `USER_NOT_FOUND` | Target does not exist |
| 401 | `AUTH_TOKEN_INVALID` | Not authenticated |

---

## `DELETE /{id}/block`

```
DELETE /api/v1/users/{id}/block
```

**Auth:** Bearer JWT required

Removes the block. Follow edges are **not** restored — both sides start from
zero.

**Response:** `200 OK` — `SocialActionResponse` with `action = "UNBLOCKED"`.

**Errors:**

| Status | errorCode | When |
|---|---|---|
| 404 | `RESOURCE_NOT_FOUND` | You have not blocked this user |
| 401 | `AUTH_TOKEN_INVALID` | Not authenticated |

---

## `GET /me/blocked`

```
GET /api/v1/users/me/blocked?page=0&size=20
```

**Auth:** Bearer JWT required

**Response:** `200 OK` — `Page<UserResponse>` of everyone the caller blocks
(same page envelope as the followers list).

---

## `POST /{id}/restrict` · `DELETE /{id}/restrict`

```
POST   /api/v1/users/{id}/restrict
DELETE /api/v1/users/{id}/restrict
```

**Auth:** Bearer JWT required

Restriction is the **silent** moderation tool: unlike block it does not touch
follow edges, and **no event or notification is ever sent to the target** —
they cannot tell they are restricted.

**Response:** `200 OK` — `SocialActionResponse` with `action = "RESTRICTED"` /
`"UNRESTRICTED"`.

**Errors (restrict):**

| Status | errorCode | When |
|---|---|---|
| 400 | `SELF_ACTION_NOT_ALLOWED` | Self-restrict |
| 400 | `RESTRICT_ALREADY_BLOCKED` | Target is already blocked — a restriction is unnecessary |
| 409 | `RESOURCE_DUPLICATE` | Already restricting this user |
| 404 | `USER_NOT_FOUND` | Target does not exist |

**Errors (unrestrict):**

| Status | errorCode | When |
|---|---|---|
| 404 | `RESOURCE_NOT_FOUND` | You are not restricting this user |

---

## `GET /me/restricted`

```
GET /api/v1/users/me/restricted?page=0&size=20
```

**Auth:** Bearer JWT required

**Response:** `200 OK` — `Page<UserResponse>` of everyone the caller
restricts.

---

## `GET /{id}/social-status`

```
GET /api/v1/users/{id}/social-status
```

**Auth:** Bearer JWT required

One-shot snapshot of the caller ↔ target relationship — follow, block and
restrict flags in both relevant directions, plus the target's live
follower/following counts. Use it to render the action buttons on a profile.

**Response:** `200 OK` — [`SocialStatusResponse`](#socialstatusresponse)

**Errors:**

| Status | errorCode | When |
|---|---|---|
| 401 | `AUTH_TOKEN_INVALID` | Not authenticated |

---

## `GET /me/suggestions`

```
GET /api/v1/users/me/suggestions?limit=20
```

**Auth:** Bearer JWT required

Friends-of-friends suggestions for the caller, precomputed and hydrated with
everything the suggestion card needs. **Falls back to the
[who-to-follow](#get-who-to-follow) ranking automatically** when the caller
has no mutual connections yet (new account).

**Query params:**

| Param | Type | Default | Description |
|---|---|---|---|
| `limit` | int | `20` | Max suggestions returned |

**Response:** `200 OK` — `FollowSuggestionResponse[]`

```json
[
  {
    "id":            "550e8400-e29b-41d4-a716-446655440000",
    "username":      "ahmad.rashid",
    "displayName":   "Ahmad Al-Rashid",
    "avatarUrl":     "https://cdn.example.com/users/avatars/ahmad.jpg",
    "followerCount": 1241,
    "role":          "SCHOLAR",
    "isFollowing":   false,
    "mutualCount":   3,
    "reason":        "3 mutual follows"
  }
]
```

| Field | Type | Notes |
|---|---|---|
| `id` | uuid | Candidate user id |
| `username` | string | Handle |
| `displayName` | string | Profile display name; falls back to `fname + lname` |
| `avatarUrl` | string \| null | — |
| `followerCount` | long | `0` if the candidate has no profile row |
| `role` | enum | Drives the badge on the card |
| `isFollowing` | boolean | Caller already follows this candidate |
| `mutualCount` | int | Mutual-follow score; **always `0` for who-to-follow results** |
| `reason` | string | `"3 mutual follows"` / `"Verified Scholar"` / `"Suggested for you"` |

---

## `DELETE /me/suggestions/{candidateId}`

```
DELETE /api/v1/users/me/suggestions/{candidateId}
```

**Auth:** Bearer JWT required

Dismisses a suggestion — removes the candidate from the caller's suggestion
partition so it will not be offered again. **Idempotent:** a no-op if the
suggestion no longer exists.

**Response:** `204 No Content`

---

## `GET /who-to-follow`

```
GET /api/v1/users/who-to-follow?limit=20
```

**Auth:** optional — anonymous callers get the global list

Popular/verified accounts the caller does not yet follow. **Ranking: scholars
and researchers first, then by follower count** — precisely `SCHOLAR` →
`RESEARCHER` → everyone else, then `followerCount DESC`, then account age.
For authenticated callers the list excludes themselves, everyone they already
follow, and any account with a block edge in either direction; anonymous
callers receive the unfiltered global ranking (with `isFollowing` always
`false`).

**Query params:**

| Param | Type | Default | Description |
|---|---|---|---|
| `limit` | int | `20` | Max results |

**Response:** `200 OK` — `FollowSuggestionResponse[]` (same shape as
suggestions; `mutualCount` is always `0`, `reason` is role-based:
`"Verified Scholar"` / `"Verified Researcher"` / `"Suggested for you"`).

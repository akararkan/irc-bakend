# Close Friends API

The "inner circle" list that gates `CLOSE_FRIENDS`-visibility
[stories](stories.md).

There are **two independent endpoint families** and it matters which one you
call:

| Family | Base path | Module / store | Purpose |
|---|---|---|---|
| **A. Story close-friends** | `/api/v1/close-friends` | Story module (`CassandraStoryController`), Cassandra table `close_friends_by_owner` | **Authoritative for story visibility.** The `CLOSE_FRIENDS` check on every story read/view resolves against this table. Returns raw id rows. |
| **B. User-module close-friends** | `/api/v1/users/me/close-friends` | User module (`CloseFriendsController`), Postgres `CloseFriendsList` | Profile-oriented list management with full `UserResponse` payloads, pagination, duplicate/self/user-existence validation. |

> **The two stores are not synchronized.** Adding a friend via family B does
> **not** make them a close friend for story visibility (family A), and vice
> versa. If your goal is "let this person see my `CLOSE_FRIENDS` stories",
> use family A.

**Auth:** `Authorization: Bearer <JWT>` — required on every endpoint in both
families (with one quirk on `is-member`, noted below). Only the **owner** can
read their own list; other users can at most trigger the single-boolean
`is-member` predicate — never enumerate a list.

**Errors:** shared envelope — see [Error handling](../errors/error-handling.md).

Sibling docs: [Stories](stories.md) · [Polls](polls.md) ·
[Highlights](highlights.md) · [Realtime (SSE)](realtime.md)

---

# Family A — Story close-friends (`/api/v1/close-friends`, Cassandra)

The list is always **"mine"** — the owner is the JWT principal; there is no
way to read or edit another user's list.

## List my close friends

```
GET /api/v1/close-friends
```

**Auth:** required.

### Response — `200 OK`

```json
[
  {
    "ownerId": "6f1a2b3c-4d5e-4f60-8a71-92b3c4d5e6f7",
    "friendId": "3e2d1c0b-9a87-4654-b321-0fedcba98765",
    "addedAt": "2026-07-11T18:22:40Z"
  },
  {
    "ownerId": "6f1a2b3c-4d5e-4f60-8a71-92b3c4d5e6f7",
    "friendId": "7a6b5c4d-3e2f-4a1b-8c9d-0e1f2a3b4c5d",
    "addedAt": "2026-06-28T07:03:12Z"
  }
]
```

| Field | Type | Description |
|---|---|---|
| `ownerId` | UUID | Always the caller |
| `friendId` | UUID | The trusted friend |
| `addedAt` | ISO-8601 instant | When they were added |

Raw id rows only — resolve display names/avatars via the user API if needed
(or use family B, which returns full profiles).

### Errors

| Status | errorCode | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid bearer token |

---

## Add a close friend

```
POST /api/v1/close-friends
```

**Auth:** required.

### Query parameters

| Param | Type | Required | Description |
|---|---|---|---|
| `friendId` | UUID | yes | User to add to my list |

### Example

```bash
curl -X POST "https://api.irc.example/api/v1/close-friends?friendId=3e2d1c0b-9a87-4654-b321-0fedcba98765" \
  -H "Authorization: Bearer $TOKEN"
```

### Response — `204 No Content`

Behavioral notes (all return `204`, none error):

- **Idempotent upsert** — re-adding an existing friend just refreshes
  `addedAt`.
- **Self-add is silently ignored** (you cannot be your own close friend).
- No check that `friendId` refers to an existing user — this family trades
  validation for write speed (family B validates).

### Errors

| Status | errorCode | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid bearer token |
| 400 | `MISSING_PARAMETER` | `friendId` omitted |
| 400 | `TYPE_MISMATCH` | `friendId` is not a valid UUID |

### Side effects

- The friend can immediately see the caller's `CLOSE_FRIENDS` stories (the
  visibility check reads this table live — no fanout, no cache to wait for).
- No notification is sent to the added user.

---

## Remove a close friend

```
DELETE /api/v1/close-friends
```

**Auth:** required.

### Query parameters

| Param | Type | Required | Description |
|---|---|---|---|
| `friendId` | UUID | yes | User to remove from my list |

### Response — `204 No Content`

Idempotent — removing someone who was never on the list is still `204`.

### Errors

| Status | errorCode | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid bearer token |
| 400 | `MISSING_PARAMETER` | `friendId` omitted |

### Side effects

- The user immediately loses access to the caller's `CLOSE_FRIENDS` stories.
- No notification and no tray event fires on removal.

---

## Am I / are they a close friend?

```
GET /api/v1/close-friends/is-member
```

**Auth:** optional — an **anonymous caller gets `200` with `false`** rather
than a 401. Lightweight predicate for colour-coding UI (e.g. the green
story ring).

Checks whether `candidateId` is on **the caller's** list.

### Query parameters

| Param | Type | Required | Description |
|---|---|---|---|
| `candidateId` | UUID | yes | User to test for membership in my list |

### Response — `200 OK`

```json
true
```

A bare JSON boolean.

### Errors

| Status | errorCode | When |
|---|---|---|
| 400 | `MISSING_PARAMETER` | `candidateId` omitted |
| 400 | `TYPE_MISMATCH` | `candidateId` is not a valid UUID |

---

# Family B — User-module close-friends (`/api/v1/users/me/close-friends`, Postgres)

Class-level `@PreAuthorize("isAuthenticated()")` — every endpoint requires a
valid JWT. Backed by a Postgres relation with full validation (self-add,
duplicates, user existence) and profile-rich responses.

## List my close friends (paginated profiles)

```
GET /api/v1/users/me/close-friends
```

**Auth:** required.

### Query parameters

| Param | Type | Default | Description |
|---|---|---|---|
| `page` | int | `0` | Zero-based page index |
| `size` | int | `20` | Page size |
| `sort` | string | — | Standard Spring sort expression (optional) |

### Response — `200 OK`

A Spring `Page` of `UserResponse` objects:

```json
{
  "content": [
    {
      "id": "3e2d1c0b-9a87-4654-b321-0fedcba98765",
      "fname": "Amina",
      "lname": "Karim",
      "username": "amina.k",
      "email": "amina@example.com",
      "role": "RESEARCHER",
      "badges": [ { "type": "RESEARCHER" } ],
      "isEmailVerified": true,
      "profile": { "avatarUrl": "https://cdn.irc.example/avatars/amina.jpg" },
      "createdAt": "2025-11-02T14:20:00"
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "number": 0,
  "size": 20
}
```

| Field | Type | Description |
|---|---|---|
| `content[]` | UserResponse | Full user summaries (id, names, `username` handle, role, badges, profile) |
| `totalElements` / `totalPages` / `number` / `size` | int | Standard Spring pagination metadata |

### Errors

| Status | errorCode | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` / `AUTH_INSUFFICIENT` | No / invalid bearer token |

---

## Add a close friend (validated)

```
POST /api/v1/users/me/close-friends/{userId}
```

**Auth:** required.

### Path parameters

| Param | Type | Description |
|---|---|---|
| `userId` | UUID | User to add to my (user-module) close-friends list |

### Response — `201 Created` (empty body)

### Errors

| Status | errorCode | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid bearer token |
| 400 | `SELF_ACTION` | `userId` is the caller (`"Cannot add yourself to close friends."`) |
| 409 | `RESOURCE_DUPLICATE` | Already on the list (`"User is already a close friend."`) |
| 404 | `USER_NOT_FOUND` | Caller or target is not an active user |
| 400 | `TYPE_MISMATCH` | `userId` is not a valid UUID |

### Side effects

- Postgres row only. **Does not** affect story visibility (family A) and
  sends no notification.

---

## Remove a close friend (validated)

```
DELETE /api/v1/users/me/close-friends/{userId}
```

**Auth:** required.

### Path parameters

| Param | Type | Description |
|---|---|---|
| `userId` | UUID | User to remove |

### Response — `204 No Content`

### Errors

| Status | errorCode | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid bearer token |
| 404 | `RESOURCE_NOT_FOUND` | Not on the list (`"Close friend not found."`) — unlike family A, this delete is **not** idempotent |
| 400 | `TYPE_MISMATCH` | `userId` is not a valid UUID |

---

## Choosing a family — cheat sheet

| You want to… | Call |
|---|---|
| Gate who sees my `CLOSE_FRIENDS` stories | Family A (`/api/v1/close-friends`) |
| Render a settings screen with avatars + names | Family B (`/api/v1/users/me/close-friends`) |
| Colour a story ring green ("am I in their circle?" — no, "is X in mine") | Family A `is-member` |
| Validated add with duplicate/404 feedback | Family B |

## Related

- Story visibility rules: [stories.md](stories.md#visibility-model)
- `story_removed` fan-out to close friends on delete: [realtime.md](realtime.md)

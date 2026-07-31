# Friend Suggestions API Reference

All endpoints require `Authorization: Bearer <JWT>` and act on the caller
(the JWT principal) — one user can never read or mutate another user's
suggestion state. Errors use the unified envelope
([../errors/error-handling.md](../errors/error-handling.md)); page sizes
clamp to 1..100.

---

## 1. Reading suggestions

### 1.1 `GET /api/v1/posts/suggestions` — raw rows (legacy shape)

| Param | Default |
|---|---|
| `limit` | 20 |

Returns raw `friend_suggestions_by_user` rows, score DESC:

```json
[ { "userId": "…", "score": 287, "candidateId": "…",
    "reason": "4 mutual follows · in your contacts · same institution",
    "computedAt": "2026-08-01T10:11:12Z" } ]
```

⚠ `score` here is the stored ×10 fixed-point int (287 ⇒ 28.7). Shape kept
verbatim for existing clients; prefer 1.2.

### 1.2 `GET /api/v1/posts/suggestions/detailed` — hydrated (canonical)

Same `limit` param. Candidate identity is join-fetched (avatars populate)
and deleted candidates are dropped:

```json
[ {
  "candidateId": "…",
  "candidate": { "id": "…", "username": "amina", "fullName": "Amina K", "profileImage": "https://…" },
  "score": 28.7,
  "reason": "4 mutual follows · in your contacts · same institution",
  "computedAt": "2026-08-01T10:11:12Z"
} ]
```

### 1.3 `GET /api/v1/users/me/suggestions` and `GET /api/v1/users/who-to-follow`

The who-to-follow surface (popular/verified fallback when the caller's
graph is too cold to produce mutual-based rows). Unchanged contract — reads
the same store, falls back to `WhoToFollowService` ranking.

---

## 2. Negative feedback (dismissal)

### `POST /api/v1/posts/suggestions/{candidateId}/dismiss` → 204
### `DELETE /api/v1/users/me/suggestions/{candidateId}` → 204 (who-to-follow surface)

Both persist a `suggestion_dismissals` row **and** delete the stored
suggestion — the candidate disappears now and stays excluded from every
future recompute. (Previously the who-to-follow dismiss was delete-only, so
dismissed people reappeared; and the underlying Cassandra `DELETE` was
missing its `score` clustering column and failed silently. Both fixed.)

---

## 3. Contact synchronization

### `POST /api/v1/users/contacts/sync`

```json
{ "hashes": ["9f86d081884c7d65…", "…"] }
```

Client-side hashing contract (server never sees raw contacts):
- email → `sha256(lowercase(trim(email)))`, hex-encoded
- phone → `sha256(E.164 digits, no '+')`, hex-encoded

Rules: max **5000** hashes per sync; each sync **replaces** the previous
upload; invalid entries are skipped. A sync also writes the caller's own
server-side IDENTITY hash and triggers an async suggestion recompute.

**Response:** `{ "stored": 812, "matched": 17 }` — `matched` = registered
users found in the uploaded contacts.

### `DELETE /api/v1/users/contacts` → 204

Privacy op: wipes the caller's uploaded contact hashes (IDENTITY row stays
— it contains no contact data, only the caller's own hashed email) and
recomputes suggestions without the contact signal.

Note: matching is email-based today (users register with email). Phone
hashes are accepted and stored; they start matching automatically if
phone-verified signup ever ships.

---

## 4. Recompute

### `POST /api/v1/posts/suggestions/recompute` → 202

Manual trigger (onboarding, pull-to-refresh). Also fired automatically on
**follow**, **unfollow**, and **contact sync/clear** — all async.

---

## 5. Interactions with other subsystems

- **Interest graph**: the `INTERACTIONS` candidate source reads the same
  `user_author_affinity` counters the home-feed ranker writes — engaging
  with someone's content nudges them into your suggestions
  (see [../feed/algorithm.md](../feed/algorithm.md)).
- **Follow**: `POST /api/v1/users/{id}/follow` — following a suggestion
  removes them naturally on the next recompute (already-followed filter).
- **Blocks/restricts**: managed via `/api/v1/users/{id}/block|restrict`;
  enforced as hard filters here.

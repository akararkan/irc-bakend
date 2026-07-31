# User Search API

Relevance-ranked user search over Postgres full-text indexes, with fuzzy and
prefix fallbacks. Powers the dedicated people picker and the research
co-author picker.

> **Also:** users are now searchable through the unified search bar —
> `GET /api/v1/search?types=USER` (Elasticsearch, ranked against content,
> follower-count boost). When to use which surface:
> [../search/users.md](../search/users.md).

**Base path:** `/api/v1/users`

Errors use the unified envelope in
[../errors/error-handling.md](../errors/error-handling.md).

Related: [auth.md](auth.md) · [users.md](users.md) · [profile.md](profile.md) ·
[social.md](social.md) · [security-model.md](security-model.md)

---

## `GET /search`

```
GET /api/v1/users/search?q={query}&page={page}&size={size}&eligibleContributor={bool}
```

**Auth:** none (public)

> **Rewritten (current behavior):** search no longer runs a sequential-scan
> `LIKE '%q%'`. It now uses **Postgres full-text search** — a GIN function
> index over `username + fname + lname + profile_bio`
> (`to_tsvector('simple', …)` matched with `websearch_to_tsquery`) — and
> results are **ranked by relevance** (`ts_rank_cd`, ties broken by newest
> account). Two fallbacks cover the edges:
>
> 1. **Trigram fuzzy fallback** — if the full-text match returns nothing (a
>    typo like `ahmda`), a `pg_trgm` similarity search over
>    username/fname/lname (GIN `gin_trgm_ops` indexes) catches misspellings,
>    ranked by best similarity.
> 2. **Prefix matcher for short queries** — queries **under 3 characters**
>    (too short to form trigrams) use the mention-style prefix ranking:
>    username-prefix matches first, then first/last-name prefix, then trigram
>    similarity; shorter usernames win ties.

### Privacy: emails are not searchable

**Emails are deliberately NOT indexed by this endpoint.** Searching for
`ahmad@example.com` will not find the account that owns that address. To
resolve an exact email, use
[`GET /api/v1/users/email/{email}`](users.md#get-emailemail) instead.
(`username` is the user-set handle and never derived from the email, so
handle searches leak nothing about addresses.)

### Result cap

Ranked text searches fetch at most the **top 200 ranked matches**
(`min(offset + size, 200)` ids are pulled, then batch-loaded and re-ordered by
rank). Page metadata (`totalElements`, `totalPages`) therefore **reflects that
cap**, not the total number of users that could match — search UIs never page
deeper, and the cap bounds the worst-case response time. Pages past the cap
come back empty.

### Query parameters

| Param | Type | Default | Description |
|---|---|---|---|
| `q` | string | `""` | Search text. **Blank returns all active users** with normal offset paging (no cap, no ranking) |
| `page` | int | `0` | Zero-based page index |
| `size` | int | `20` | Page size |
| `eligibleContributor` | boolean | `false` | `true` restricts results to **`RESEARCHER` + `SCHOLAR`** roles — used by the research co-author picker so it only offers valid contributors. Applies to both the blank-query listing and ranked search |

Soft-deleted accounts are always excluded.

### Response

`200 OK` — `Page<UserResponse>` ([`UserResponse`](users.md#userresponse)),
ordered by relevance (or `createdAt DESC` for blank `q`):

```json
{
  "content": [
    {
      "id":              "550e8400-e29b-41d4-a716-446655440000",
      "fname":           "Ahmad",
      "lname":           "Al-Rashid",
      "username":        "ahmad.rashid",
      "email":           "ahmad@example.com",
      "role":            "SCHOLAR",
      "badges":          [ { "type": "VERIFIED_SCHOLAR", "label": "Scholar", "colorKey": "teal", "icon": "ti-certificate", "priority": 1 } ],
      "isEmailVerified": true,
      "profile":         { "displayName": "Sheikh Ahmad Al-Rashid", "avatarUrl": "…" },
      "createdAt":       "2025-01-15T08:30:00"
    }
  ],
  "pageable":         { "pageNumber": 0, "pageSize": 20 },
  "totalElements":    37,
  "totalPages":       2,
  "number":           0,
  "size":             20,
  "numberOfElements": 20,
  "first":            true,
  "last":             false,
  "empty":            false
}
```

| Field | Notes |
|---|---|
| `content` | Ranked `UserResponse[]` for this page |
| `totalElements` | Matches found, **capped at 200** for ranked searches |
| `totalPages` | Derived from the capped total |
| `number` / `size` | Echo of the requested page/size |

### Examples

```
GET /api/v1/users/search?q=ahmad rashid          → FTS, relevance-ranked
GET /api/v1/users/search?q=ahmda                 → no FTS hit → trigram fuzzy match
GET /api/v1/users/search?q=ah                    → <3 chars → prefix matcher
GET /api/v1/users/search?q=&page=3&size=50       → all active users, plain paging
GET /api/v1/users/search?q=fiqh&eligibleContributor=true
                                                 → RESEARCHER/SCHOLAR only
```

### Errors

| Status | errorCode | When |
|---|---|---|
| 400 | `TYPE_MISMATCH` | Non-numeric `page`/`size`, non-boolean `eligibleContributor` |

### Side effects

None — read-only. (Bio content is indexed from `user_profiles.profile_bio`;
profile updates are reflected on the next search, subject to the short
search-result cache.)

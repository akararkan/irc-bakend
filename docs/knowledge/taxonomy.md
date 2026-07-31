# Knowledge Taxonomy API — Topics & Madhhabs

The two fixed reference vocabularies of the platform, both **trilingual**
(English / Arabic / Central Kurdish):

| Vocabulary | Table | Used for |
|---|---|---|
| **Topics** (Fiqh, Tafsir, Hadith, …) | `topics` | Profile **specializations** — the subject areas a researcher/scholar displays on their profile |
| **Madhhabs** (schools of jurisprudence) | `madhhabs` | The single **madhhab** a user selects on their profile |

- **Base path:** `/api/v1`
- **Auth:** the two lookup endpoints are **public** (no token required —
  same policy as `/tags/*` reads, and usable during onboarding). The
  consumer endpoints (§3) require authentication.
- **Errors:** unified envelope — see
  [../errors/error-handling.md](../errors/error-handling.md).

**Content management:** there is deliberately **no write API**. Both
vocabularies are operator-managed reference data (rows are maintained
directly in Postgres); the application only reads them. IDs are stable
`Integer`s — safe to cache client-side for a session.

Related: [profile.md](../user/profile.md) ·
[search — knowledge lookup](../search/knowledge.md)

---

## 1. `GET /topics`

List or search the topic vocabulary.

```
GET /api/v1/topics
GET /api/v1/topics?q=fiqh
GET /api/v1/topics?q=الفقه
```

**Auth:** none (public).

| Param | Type | Default | Notes |
|---|---|---|---|
| `q` | string | — | Optional. Blank/omitted → the **full vocabulary** (the picker case). Non-blank → case-insensitive *contains* match across **all three** name columns |

A query matches a row if it appears in `nameEn`, `nameAr` **or** `nameCkb`
— `fiqh`, `الفقه` and the Kurdish spelling all resolve the same row.
Unicode is matched as-is; the platform never transliterates (same rule as
[tags](../platform/tags.md)).

### Response `200` — `Topic[]`

```json
[
  { "id": 1, "nameEn": "Fiqh",   "nameAr": "الفقه",   "nameCkb": "فیقھ" },
  { "id": 2, "nameEn": "Tafsir", "nameAr": "التفسير", "nameCkb": "تەفسیر" }
]
```

| Field | Type | Notes |
|---|---|---|
| `id` | int | Stable topic id — what you send to the specializations endpoint |
| `nameEn` / `nameAr` / `nameCkb` | string | Display names; render the one matching the UI language |

Rows come back in table order (no relevance ranking — the vocabulary is
dozens of rows; filter/sort client-side as needed).

**Side effects:** none. **Errors:** none beyond the standard envelope.

---

## 2. `GET /madhhabs`

Identical contract to `/topics`, over the madhhab vocabulary.

```
GET /api/v1/madhhabs?q=han
```

**Auth:** none (public).

### Response `200` — `Madhhab[]`

```json
[
  { "id": 1, "nameEn": "Hanafi", "nameAr": "الحنفية", "nameCkb": "حەنەفی" },
  { "id": 2, "nameEn": "Shafii", "nameAr": "الشافعية", "nameCkb": "شافعی" }
]
```

Same field semantics as topics; `id` is what you send as `madhhabId` when
updating a profile.

---

## 3. Where the taxonomy is consumed

### 3.1 Profile specializations — `PATCH /api/v1/users/me/profile/specializations`

**Auth:** required (`isAuthenticated()`).

Sets the caller's displayed specialization topics. **Replace-all
semantics** — the request is the complete new list; anything not listed is
removed. Send `{"specializations": []}` to clear.

```json
{
  "specializations": [
    { "topicId": 1, "displayOrder": 0 },
    { "topicId": 7, "displayOrder": 1 }
  ]
}
```

| Field | Type | Notes |
|---|---|---|
| `specializations` | array (required) | The full new list |
| `topicId` | int (required) | Must exist in `/topics` — unknown id fails the whole request (nothing is applied) |
| `displayOrder` | int | Client-controlled ordering on the profile |

**Response `200`:** the full `UserResponse` (profile included) — see
[../user/profile.md](../user/profile.md). Each specialization renders as a
`TopicDto`:

```json
"specializations": [
  { "topicId": 1, "nameEn": "Fiqh", "nameAr": "الفقه", "nameCkb": "فیقھ" }
]
```

**Side effects:** evicts the profile cache; audit-logged (`UPDATE`).

**Errors:**

| Status | When |
|---|---|
| 401 | No/invalid token |
| 400 | Missing `specializations` / `topicId` (validation) |
| 404 | Any `topicId` not found — transactional, no partial apply |

### 3.2 Madhhab selection — `PATCH /api/v1/users/me/profile`

The general profile-update endpoint ([../user/profile.md](../user/profile.md))
accepts a `madhhabId`:

```json
{ "madhhabId": 1 }
```

Validated against the vocabulary (`404` if unknown). The profile response
then carries both `madhhabId` and `madhhabName` (the **English** name —
clients wanting `nameAr`/`nameCkb` resolve it against `GET /madhhabs`,
which is cheap and cacheable).

---

## 4. Design notes

- **Why no Elasticsearch:** tiny fixed vocabularies — one bounded table
  read + an in-memory trilingual filter is effectively **O(1)**, always
  consistent, and needs no index maintenance. Full reasoning:
  [../search/knowledge.md](../search/knowledge.md).
- **Why replace-all for specializations:** the profile shows a short
  ordered list; diff semantics (add/remove endpoints) would need ordering
  moves anyway. One idempotent PATCH is simpler for every client.
- **`fatwaCount` / `researchCount`** on the profile are unrelated counters
  ([../user/profile.md](../user/profile.md)) — the taxonomy carries no
  per-topic content counts today. If topics ever need "N research items
  each", that belongs in the tag subsystem, not here.

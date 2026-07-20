# Story Highlights API

Permanent, curated archives of [stories](stories.md) — the row of cover
"pills" on a profile. Highlights never expire. Adding a story to a highlight
**snapshots (copies)** the story's content into the highlight, because the
source story row will be TTL'd away within ≤ 24 h; the snapshot keeps the
original story's `createdAt` so the highlight reads in true chronological
order.

**Base path:** `/api/v1/highlights`

**Auth:** `Authorization: Bearer <JWT>` on all mutations. Reads
(`by-author`, `stories`) are public.

> **Security model (current behavior).** All mutating endpoints derive the
> actor from the **JWT principal** — never from the body or query string:
>
> - `POST /` — an `authorId` in the body is accepted for backward
>   compatibility but **ignored**; the owner is always the caller.
> - `POST /{highlightId}/stories/{storyId}` — the old `?requesterId=` query
>   parameter has been **removed**; if a legacy client still sends it, it is
>   ignored. The caller must be **both** the story's author **and** the
>   highlight's owner.
> - `DELETE /{highlightId}/stories/{storyId}` — ownership enforced; `403`
>   otherwise.
> - `PATCH /order` — reorders only the caller's own highlights; foreign ids
>   are silently skipped.

**Errors:** shared envelope — see [Error handling](../errors/error-handling.md).

Sibling docs: [Stories](stories.md) · [Polls](polls.md) ·
[Close friends](close-friends.md) · [Realtime (SSE)](realtime.md)

---

## Highlight object

| Field | Type | Description |
|---|---|---|
| `authorId` | UUID | Owner (always the JWT principal at create time) |
| `displayOrder` | int | Position on the profile rail, ascending (part of the storage clustering key) |
| `highlightId` | UUID | Server-generated id |
| `title` | string | Pill label |
| `coverUrl` | string \| null | Cover image |
| `createdAt` | ISO-8601 instant | Creation time |

---

## Create a highlight

```
POST /api/v1/highlights
```

**Auth:** required. The owner is **always** the authenticated caller.

### Request body

```json
{
  "title": "Ramadan 1447",
  "coverUrl": "https://cdn.irc.example/highlights/ramadan-cover.jpg",
  "displayOrder": 0
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `authorId` | UUID | no | **Deprecated & ignored** — accepted only so old clients don't break; the owner is the JWT principal regardless of what is sent here |
| `title` | string | yes | Pill label |
| `coverUrl` | string | no | Cover image URL |
| `displayOrder` | int | yes | Initial rail position (reorder later via `PATCH /order`) |

### Response — `200 OK`

```json
{
  "authorId": "6f1a2b3c-4d5e-4f60-8a71-92b3c4d5e6f7",
  "displayOrder": 0,
  "highlightId": "b1c2d3e4-f5a6-4b7c-8d9e-0f1a2b3c4d5e",
  "title": "Ramadan 1447",
  "coverUrl": "https://cdn.irc.example/highlights/ramadan-cover.jpg",
  "createdAt": "2026-07-20T11:00:00Z"
}
```

### Errors

| Status | errorCode | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid bearer token |
| 400 | `MALFORMED_JSON` | Body is not valid JSON |

---

## List an author's highlights

```
GET /api/v1/highlights/by-author/{authorId}
```

**Auth:** none.

Returns the author's highlights ordered by `displayOrder` ascending (free —
it is the clustering key).

### Path parameters

| Param | Type | Description |
|---|---|---|
| `authorId` | UUID | Whose highlight rail to list |

### Response — `200 OK`

```json
[
  {
    "authorId": "6f1a2b3c-4d5e-4f60-8a71-92b3c4d5e6f7",
    "displayOrder": 0,
    "highlightId": "b1c2d3e4-f5a6-4b7c-8d9e-0f1a2b3c4d5e",
    "title": "Ramadan 1447",
    "coverUrl": "https://cdn.irc.example/highlights/ramadan-cover.jpg",
    "createdAt": "2026-07-20T11:00:00Z"
  },
  {
    "authorId": "6f1a2b3c-4d5e-4f60-8a71-92b3c4d5e6f7",
    "displayOrder": 1,
    "highlightId": "c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f",
    "title": "Hadith notes",
    "coverUrl": null,
    "createdAt": "2026-05-02T08:30:00Z"
  }
]
```

### Errors

| Status | errorCode | When |
|---|---|---|
| 400 | `TYPE_MISMATCH` | `authorId` is not a valid UUID |

---

## Add a story to a highlight

```
POST /api/v1/highlights/{highlightId}/stories/{storyId}
```

**Auth:** required. The caller must be **both** the story's author **and**
the highlight's owner. The actor comes from the JWT — the legacy
`?requesterId=` query parameter was removed and is ignored if sent.

Snapshots a still-live story into the highlight. Do this **before** the
story's TTL fires — once the source rows are gone there is nothing left to
copy.

### Path parameters

| Param | Type | Description |
|---|---|---|
| `highlightId` | UUID | Target highlight (must belong to the caller) |
| `storyId` | UUID | Source story (must be authored by the caller and still live) |

### Response — `200 OK`

```json
{
  "highlightId": "b1c2d3e4-f5a6-4b7c-8d9e-0f1a2b3c4d5e",
  "createdAt": "2026-07-20T09:15:00Z",
  "storyId": "0c9d8e7f-6a5b-4c3d-9e2f-1a0b9c8d7e6f",
  "authorId": "6f1a2b3c-4d5e-4f60-8a71-92b3c4d5e6f7",
  "storyType": "IMAGE",
  "mediaUrl": "https://cdn.irc.example/stories/media/9b2f1c.jpg",
  "thumbnailUrl": "https://cdn.irc.example/stories/thumb/9b2f1c.jpg",
  "textContent": "Notes from today's tafsir circle"
}
```

| Field | Type | Description |
|---|---|---|
| `highlightId` | UUID | The archive the story went into |
| `createdAt` | ISO-8601 instant | **The ORIGINAL story's timestamp** (not the archive time) — also the clustering key you need for delete |
| `storyId` | UUID | The archived story |
| `authorId` / `storyType` / `mediaUrl` / `thumbnailUrl` / `textContent` | — | Denormalized copies of the source story |

### Errors

| Status | errorCode | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid bearer token |
| 403 | `FORBIDDEN` | Caller is not the story's author (`"Not the story author"`) |
| 403 | `FORBIDDEN` | Caller does not own the target highlight (`"Not the highlight owner"`) |
| 404 | — (empty body) | Story unknown, or its content already expired before the snapshot could be taken |

### Side effects

- Writes a permanent (no-TTL) row into `stories_in_highlight`. The snapshot
  survives the source story's expiry and manual deletion.
- No SSE event or notification fires.

---

## List stories inside a highlight

```
GET /api/v1/highlights/{highlightId}/stories
```

**Auth:** none.

Returns the archived snapshots in chronological (`createdAt` ascending)
order.

### Path parameters

| Param | Type | Description |
|---|---|---|
| `highlightId` | UUID | The highlight to open |

### Response — `200 OK`

Array of the snapshot objects shown above. Empty array for an unknown or
empty highlight.

### Errors

| Status | errorCode | When |
|---|---|---|
| 400 | `TYPE_MISMATCH` | `highlightId` is not a valid UUID |

---

## Remove a story from a highlight

```
DELETE /api/v1/highlights/{highlightId}/stories/{storyId}
```

**Auth:** required. **Highlight owner only** — ownership is verified against
the stored snapshot's `authorId`; anyone else gets `403`.

### Path parameters

| Param | Type | Description |
|---|---|---|
| `highlightId` | UUID | The highlight |
| `storyId` | UUID | The archived story to remove |

### Query parameters

| Param | Type | Required | Description |
|---|---|---|---|
| `createdAt` | ISO-8601 instant | yes | The snapshot's `createdAt` (returned on add / list) — it is part of the storage clustering key, so the delete needs it to address the exact row |

### Example

```bash
curl -X DELETE "https://api.irc.example/api/v1/highlights/b1c2d3e4-f5a6-4b7c-8d9e-0f1a2b3c4d5e/stories/0c9d8e7f-6a5b-4c3d-9e2f-1a0b9c8d7e6f?createdAt=2026-07-20T09:15:00Z" \
  -H "Authorization: Bearer $TOKEN"
```

### Response — `204 No Content`

### Errors

| Status | errorCode | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid bearer token |
| 403 | `FORBIDDEN` | The snapshot does not belong to the caller — or does not exist at all (`"Not the highlight owner"`) |
| 400 | `MISSING_PARAMETER` | `createdAt` omitted |
| 400 | `TYPE_MISMATCH` | `createdAt` is not a parseable instant |

---

## Reorder my highlights

```
PATCH /api/v1/highlights/order
```

**Auth:** required. Operates **only on the caller's own highlights**.

Rewrites the caller's rail to match the supplied id sequence. Pass the full
list of highlight ids in the desired left-to-right order.

Semantics:

- **Foreign or unknown ids are silently skipped** — you cannot move (or even
  reference) another user's highlights; they are simply dropped from the
  result.
- Ids you own but omit from the list keep their old rows (they are not
  renumbered) — send the complete list to avoid `displayOrder` collisions.
- Idempotent: re-sending the same order is a net no-op.
- Rows genuinely moving are re-written (delete + insert) because
  `displayOrder` is part of the clustering key; `title` / `coverUrl` /
  `createdAt` are preserved.

### Request body

```json
{
  "order": [
    "c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f",
    "b1c2d3e4-f5a6-4b7c-8d9e-0f1a2b3c4d5e"
  ]
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `order` | UUID[] | yes | Desired left-to-right rail order; empty array is a no-op returning `[]` |

### Response — `200 OK`

The rewritten highlights in their new order (skipped foreign ids absent):

```json
[
  {
    "authorId": "6f1a2b3c-4d5e-4f60-8a71-92b3c4d5e6f7",
    "displayOrder": 0,
    "highlightId": "c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f",
    "title": "Hadith notes",
    "coverUrl": null,
    "createdAt": "2026-05-02T08:30:00Z"
  },
  {
    "authorId": "6f1a2b3c-4d5e-4f60-8a71-92b3c4d5e6f7",
    "displayOrder": 1,
    "highlightId": "b1c2d3e4-f5a6-4b7c-8d9e-0f1a2b3c4d5e",
    "title": "Ramadan 1447",
    "coverUrl": "https://cdn.irc.example/highlights/ramadan-cover.jpg",
    "createdAt": "2026-07-20T11:00:00Z"
  }
]
```

### Errors

| Status | errorCode | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid bearer token |
| 403 | `ACCESS_FORBIDDEN` | Body missing or `order` is `null` (`"order list is required"`) — note the current build maps this to 403, not 400 |
| 400 | `MALFORMED_JSON` | Body is not valid JSON |

---

## Related

- Source stories and their TTLs: [stories.md](stories.md)
- Live story lifecycle events: [realtime.md](realtime.md)

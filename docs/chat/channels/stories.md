# Channels — stories & highlights

Channels post **stories** like users do — the whole
[story stack](../../story/stories.md) is reused with the **channel's
conversation id as the story author** and `visibility: "CHANNEL"`, so TTL
lifetimes, view logs, story polls and the story-tray SSE work unchanged.
**Highlights** pin chosen stories permanently on the channel profile.

Base path `/api/v1`. `{id}` = channel UUID; `{storyId}`/`{highlightId}` = UUIDs.
Posting/deleting stories & highlights requires the `canManageStories`
[right](admins.md#adminrights) (`403 ADMINS_ONLY` otherwise).

---

## Posting stories

### `POST /channels/{id}/stories` *(JSON)*
**Request** — `CreateChannelStoryRequest`:

| field | type | notes |
|---|---|---|
| `storyType` | string | ≤ 20; `TEXT`/`IMAGE`/`VIDEO`/… Defaults to `IMAGE` if media given, else `TEXT`. |
| `textContent` | string | ≤ 2000. |
| `mediaUrl` | string | ≤ 500; pre-uploaded media URL. |
| `thumbnailUrl` | string | ≤ 500. |
| `lifetimeHours` | int | `8` / `16` / `24` (default 24). |

A story needs `mediaUrl` and/or `textContent` (else `400`).

```json
{ "storyType": "TEXT", "textContent": "Launch week!", "lifetimeHours": 8 }
```

### `POST /channels/{id}/stories` *(multipart)*
Uploads media through R2 first. Parts/params: `media` (file), `thumbnail`
(file), `storyType`, `textContent`, `lifetimeHours`. Type auto-detects from the
media MIME (`video/* → VIDEO`, else `IMAGE`) when `storyType` is omitted.

**Response** *(both)* — `201`, [`StoryByAuthorEntity`](#storybyauthorentity).
Publishing fans out `new_story` on the [story tray](#story-tray).

---

## Reading & deleting

### `GET /channels/{id}/stories` — active stories
Public channel → anyone; private → active subscribers (`403` otherwise).
**Response** — `200`, [`StoryByAuthorEntity`](#storybyauthorentity)`[]` (newest
first).

### `DELETE /channels/{id}/stories/{storyId}`
**Response** — `204`. Fires `story_removed` on the tray; cleans up any attached
poll. **Errors** — `404` (unknown / not this channel's story), `403`.

### StoryByAuthorEntity
| field | type | notes |
|---|---|---|
| `authorId` | UUID | **the channel id**. |
| `createdAt` | timestamp | |
| `storyId` | UUID | |
| `storyType` | string | |
| `visibility` | string | always `"CHANNEL"` here. |
| `mediaUrl`, `thumbnailUrl` | string | |
| `textContent` | string | |
| `expiresAt` | timestamp | when the TTL fires. |

---

## Views & viewer log

Reuse the shared story endpoints with the story id:

- `POST /stories/{storyId}/views` — any allowed viewer records a view.
  **Response** — **`202`** (no body).
- `GET /stories/{storyId}/views` — the viewer log, readable by the channel's
  **owner/admins** (a plain subscriber is refused). **Response** — `200`,
  `StoryViewEntity[]` = `{ storyId, viewedAt, viewerId }`, newest first.

---

## Story polls

A 2-option (A/B) poll on a channel story — separate from
[post polls](posts.md#polls--quizzes).

### `POST /channels/{id}/stories/{storyId}/poll` — attach
**Request** — `{ "question": "…", "optionA": "…", "optionB": "…" }`.
**Response** — `201`, `StoryPollEntity`:
`{ storyId, pollId, question, optionA, optionB, authorId, createdAt }`.

### Voting (shared story-poll endpoints)
- `POST /polls/{pollId}/vote?choice=A|B` — cast/change. **Response** — `200`,
  `{ "choice": "A", "voteA": 12, "voteB": 3 }`.
- `GET /polls/{pollId}/results` → same `{ choice, voteA, voteB }` shape
  (`choice` null).
- `GET /polls/{pollId}/vote/me` → the caller's current choice.

---

## Story tray

### `GET /channels/stories/tray`
The caller's subscribed channels that currently have live stories.
**Response** — `200`, `ChannelStoryTrayItem[]`:

| field | type | notes |
|---|---|---|
| `channelId` | UUID | |
| `handle` | string | |
| `title` | string | |
| `avatarUrl` | string | |
| `verified` | boolean | |
| `stories` | [StoryByAuthorEntity](#storybyauthorentity)[] | that channel's live stories. |

```json
[ { "channelId": "7b1e…", "handle": "ai_research", "title": "AI Digest",
    "avatarUrl": "https://cdn…", "verified": true,
    "stories": [ { "authorId": "7b1e…", "storyId": "…", "visibility": "CHANNEL",
                   "storyType": "TEXT", "textContent": "Launch!",
                   "expiresAt": "2026-07-24T18:00:00Z" } ] } ]
```

**Realtime.** Publishing/deleting a channel story fans out `new_story` /
`story_removed` on the existing **story-tray SSE**
(`GET /api/v1/stories/tray/stream`) to every active subscriber, with
`authorId` = the channel id and `visibility: "CHANNEL"` so clients render a
channel ring. Fan-out is capped at 50k recipients.

---

## Highlights

Permanent curated archives on the channel profile. Adding a story **snapshots**
it, so the highlight survives after the story's TTL expires. Reading is
audience-gated exactly like stories; curating needs `canManageStories`.

### `POST /channels/{id}/highlights` — create
**Request** — `{ "title": "Launch", "coverUrl": "…"|null }` (blank title →
`400`). New highlights append to the end of the rail.
**Response** — `201`, [`HighlightByAuthorEntity`](#highlightbyauthorentity).

### `GET /channels/{id}/highlights` — the rail
**Response** — `200`, `HighlightByAuthorEntity[]` (by `displayOrder`).

### `POST /channels/{id}/highlights/{highlightId}/stories/{storyId}` — snapshot
Snapshots a **live** story into the highlight — archive **before** it expires or
you get `404`. **Response** — `201`,
[`StoryInHighlightEntity`](#storyinhighlightentity).

### `GET /channels/{id}/highlights/{highlightId}/stories` — snapshots
**Response** — `200`, `StoryInHighlightEntity[]`.

### `DELETE /channels/{id}/highlights/{highlightId}/stories/{storyId}`
Remove one snapshot. **Response** — `204`.

### `DELETE /channels/{id}/highlights/{highlightId}`
Delete the whole highlight (and its snapshots). **Response** — `204`.

### `PATCH /channels/{id}/highlights/order` — reorder
**Request** — `{ "order": ["<highlightId>", …] }` (full left-to-right list;
foreign ids skipped). **Response** — `200`, the rewritten
`HighlightByAuthorEntity[]`.

### HighlightByAuthorEntity
`{ authorId (=channel), displayOrder, highlightId, title, coverUrl, createdAt }`.

### StoryInHighlightEntity
Denormalized snapshot (self-contained, survives the source story):
`{ highlightId, createdAt, storyId, authorId (=channel), storyType, mediaUrl,
thumbnailUrl, textContent }`.

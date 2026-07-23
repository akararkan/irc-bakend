# Channels — posts

Posting to a channel is `POST /conversations/{channelId}/messages` — the normal
[send](../messages.md) call with the channel id (requires `canPostMessages`).
This page gives the **full request/response** for a channel post, the extra
message types, view/forward counters, and the in-message poll.

Base path `/api/v1`. Message/post ids are **64-bit Snowflakes** (JSON numbers).

---

## `POST /conversations/{channelId}/messages` — post

**Request** — `SendMessageRequest`:

| field | type | req | notes |
|---|---|---|---|
| `clientNonce` | string | ✔ | idempotency key (≤ 64); reuse on retry → same message, no dup row. |
| `type` | enum | ✔ | `TEXT` `IMAGE` `VIDEO` `VOICE` `AUDIO` `GIF` `STICKER` `FILE` `POLL` `LOCATION` `CONTACT` `VIDEO_NOTE`. |
| `body` | string | – | ≤ 8000 chars; `null` for pure-media. |
| `replyToId` | long | – | Snowflake of the replied-to message. |
| `media` | [MediaRefDto](#mediarefdto)[] | – | ≤ 10 attachments (album). |
| `silent` | boolean | – | delivers normally but sends **no push**. |
| `poll` | [PollCreateDto](#pollcreatedto) | POLL✔ | required iff `type=POLL`, else must be absent. |
| `location` | [LocationDto](#locationdto) | LOCATION✔ | required iff `type=LOCATION`. |
| `contact` | [ContactDto](#contactdto) | CONTACT✔ | required iff `type=CONTACT`. |

The typed payload **must match** `type` (e.g. `location` on a `TEXT`, or a
`LOCATION` with no `location`, → `400`).

```json
{ "clientNonce": "0f9c…", "type": "TEXT",
  "body": "New paper on diffusion models #ml #papers", "silent": false }
```

**Response** — `201`, [`MessageResponse`](#messageresponse).
**Errors** — `403 ADMINS_ONLY` (lacks `canPostMessages`), `400 BAD_REQUEST`
(payload/type mismatch), `429 RATE_LIMIT_EXCEEDED`.

> Multipart variant `POST /conversations/{id}/messages/upload` uploads files
> inline (parts `files`, plus `clientNonce`/`type`/`body`/`silent`); it
> classifies `image/gif → GIF`, other `image/* → IMAGE`, `video/* → VIDEO`,
> `audio/* → AUDIO`, else `FILE`. See [messages.md](../messages.md).

---

## MessageResponse

The rendered post — returned by the send call and by every read/search/pin path
in [messages.md](../messages.md). Channel-specific fields are flagged.

| field | type | notes |
|---|---|---|
| `messageId` | long | Snowflake. |
| `conversationId` | UUID | the channel. |
| `senderId` | UUID | posting admin. |
| `senderUsername`, `senderFullName` | string | |
| `type` | string | message type. |
| `body` | string | text/caption; `null` for pure-media. |
| `media` | [MediaRefResponse](#mediarefresponse)[] | attachments. |
| `replyToId` | long | `null` if not a reply. |
| `replyTo` | ReplyPreview | small preview of the replied-to message. |
| `forwardedFrom` | UUID | original conversation when forwarded. |
| `mentions` | UUID[] | @-mentioned user ids. |
| `reactions` | ReactionSummary[] | per-emoji counts + `mine`. |
| `editedAt` | timestamp | `null` if never edited. |
| `deleted` | boolean | tombstone flag. |
| `systemEvent` | string | set for system rows (joins, etc.). |
| `createdAt` | timestamp | |
| `starred` | boolean | viewer-relative bookmark. |
| **`tags`** | string[] | lowercased `#hashtags` from body/caption (re-extracted on edit). |
| **`authorSignature`** | string | posting admin's `@username` when `signMessages` is on; else `null`. |
| **`views`** | long | **channel posts only** — unique-viewer count; `null` elsewhere. |
| **`forwards`** | long | **channel posts only** — times forwarded; `null` elsewhere. |
| **`comments`** | long | **channel posts only** — [discussion](discussion.md) comment count; `null` when no group is linked. |
| `poll` | [PollResponse](#pollresponse) | for `POLL` messages. |
| `location` | [LocationDto](#locationdto) | for `LOCATION` messages. |
| `contact` | [ContactDto](#contactdto) | for `CONTACT` messages. |

```json
{ "messageId": 7395019283746, "conversationId": "7b1e…", "senderId": "9c2a…",
  "senderUsername": "aram", "senderFullName": "Aram K", "type": "TEXT",
  "body": "New paper #ml", "media": [], "replyToId": null, "replyTo": null,
  "forwardedFrom": null, "mentions": [], "reactions": [],
  "editedAt": null, "deleted": false, "systemEvent": null,
  "createdAt": "2026-07-24T10:00:00Z", "starred": false,
  "tags": ["ml"], "authorSignature": "@aram",
  "views": 512, "forwards": 7, "comments": 3,
  "poll": null, "location": null, "contact": null }
```

---

## Message-type payloads

### LocationDto
`type: "LOCATION"`. Round-tripped verbatim on the response.

| field | type | req | notes |
|---|---|---|---|
| `latitude` | double | ✔ | −90…90. |
| `longitude` | double | ✔ | −180…180. |
| `name` | string | – | venue name, ≤ 120. |
| `address` | string | – | ≤ 200. |
| `live` | boolean | – | live location (client keeps updating). |
| `livePeriodSeconds` | int | – | live validity window. |

```json
{ "clientNonce": "…", "type": "LOCATION",
  "location": { "latitude": 36.19, "longitude": 44.01,
                "name": "Erbil Citadel", "live": false } }
```

### ContactDto
`type: "CONTACT"`.

| field | type | req | notes |
|---|---|---|---|
| `phone` | string | ✔ | ≤ 32. |
| `firstName` | string | ✔ | ≤ 80. |
| `lastName` | string | – | ≤ 80. |
| `userId` | UUID | – | set when the contact is a platform user. |

### VIDEO_NOTE
`type: "VIDEO_NOTE"` with a `media` entry of `kind: "VIDEO_NOTE"` (circular
video). Appears in the gallery under `kind=VIDEO_NOTE`.

### MediaRefDto
Inbound attachment (bytes uploaded via the media API first; chat carries the
`storageKey`).

| field | type | req | notes |
|---|---|---|---|
| `kind` | string | ✔ | `IMAGE`/`VIDEO`/`VOICE`/`AUDIO`/`GIF`/`STICKER`/`VIDEO_NOTE`/`FILE` (≤ 16). |
| `storageKey` | string | ✔ | ≤ 512. |
| `url`, `thumbnailKey`, `thumbnailUrl` | string | – | proxy references. |
| `mime` | string | – | ≤ 128. |
| `bytes` | long | – | |
| `width`, `height`, `durationMs` | int | – | |
| `waveform` | string | – | voice waveform (≤ 20000). |
| `fileName` | string | – | ≤ 300. |
| `altText` | string | – | ≤ 500. |

### MediaRefResponse
Outbound mirror of the above (`url`/`thumbnailUrl` resolve through the proxy):
`kind, storageKey, url, thumbnailKey, thumbnailUrl, mime, bytes, width, height,
durationMs, waveform, fileName, altText`.

---

## Reading the feed

Reading a channel uses the standard [conversation](../conversations.md) message
endpoints with the channel id (membership rules apply — subscribe to a private
channel first). All return [`MessageResponse`](#messageresponse) with the channel
fields (`views`, `forwards`, `comments`, `tags`, `authorSignature`) hydrated.

| Method & path | Does |
|---|---|
| `GET /conversations/{id}/messages?cursor=&limit=` | Page newest→older. `limit` clamped 1–100 (default 50). |
| `GET /conversations/{id}/messages/sync?after=&limit=` | Gap sync — everything strictly newer than `after` (a Snowflake), ascending. |
| `GET /messages/{messageId}` | A single post. |
| `GET /conversations/{id}/pinned` | Pinned posts, newest pin first. |
| `GET /conversations/{id}/messages/search?q=&limit=` | In-channel full-text search (ES). |

`GET /conversations/{id}/messages` returns a **`MessagePage`**:

```json
{ "items": [ …MessageResponse… ], "nextCursor": 7395019283000, "hasMore": true }
```
`nextCursor` is the Snowflake of the **oldest** row in the page — pass it back as
`cursor` for the next-older page; `null` means the start of history is reached.

---

## Managing posts (admin)

### `PATCH /messages/{messageId}` — edit
Requires `canEditMessages` (channel posts belong to the channel, so an editor
can edit **any** post). Re-extracts `#tags`. **Request** —
`{ "body": "corrected text" }` (non-blank, ≤ 8000). **Response** — `200`,
[`MessageResponse`](#messageresponse) with `editedAt` set.

### `DELETE /messages/{messageId}?scope=everyone|me` — delete
`scope=everyone` (default) tombstones the post for everyone — allowed for the
poster or an admin with `canDeleteMessages`; also drops any
[discussion comments](discussion.md). `scope=me` hides it for the caller only.
**Response** — `204`.

### Pin / unpin
| Method & path | Does |
|---|---|
| `POST /conversations/{id}/messages/{messageId}/pin` | Pin (requires `canPinMessages`). → `200` |
| `DELETE /conversations/{id}/messages/{messageId}/pin` | Unpin. → `204` |

### Scheduled posts

Queue a post for a future time (Telegram "Schedule message").

- `POST /conversations/{id}/messages/schedule` — **request**
  `ScheduleMessageRequest`:

  | field | type | req | notes |
  |---|---|---|---|
  | `scheduledAt` | timestamp | ✔ | must be in the **future**. |
  | `clientNonce` | string | ✔ | idempotency key (≤ 64). |
  | `type` | enum | ✔ | same message types as a live post. |
  | `body` | string | – | ≤ 8000. |
  | `replyToId` | long | – | |
  | `media` | [MediaRefDto](#mediarefdto)[] | – | ≤ 10. |
  | `silent` | boolean | – | no push when it fires. |

  **Response** — `201`, `ScheduledMessageResponse`:
  `{ id, conversationId, type, body, media, replyToId, scheduledAt, status,
  sentMessageId, createdAt }` (`sentMessageId` is filled once it fires).
- `GET /conversations/{id}/scheduled` → pending `ScheduledMessageResponse[]`.
- `DELETE /messaging/scheduled/{scheduledId}` → `204` (cancel before it fires).

---

## Reactions

Subscribers react to posts (gated by the channel's
[`reactionsEnabled` / `allowedReactions`](overview.md#channelsettings)).

| Method & path | Body | Response |
|---|---|---|
| `POST /messages/{messageId}/react` | `{ "emoji": "👍" }` (single grapheme, ≤ 16) | `200`, `ReactionSummary[]` |
| `DELETE /messages/{messageId}/react` | — | `200`, `ReactionSummary[]` |
| `GET /messages/{messageId}/reactions` | — | `200`, `ReactionSummary[]` |

`ReactionSummary` = `{ emoji, count, reactedByMe }`. Reacting when disabled → 
`403 REACTIONS_DISABLED`; an emoji outside a non-empty `allowedReactions` → same.

---

## Views & forwards

### `POST /channels/{id}/posts/views` — batch view marker

Clients report posts that entered the viewport; each (post, viewer) counts
**once** (Redis HyperLogLog dedupe → Cassandra `message_counters`).

**Request** — `MarkViewsRequest`: `{ "messageIds": [<snowflake>, …] }` —
non-empty, ≤ 100 ids.

**Response** — `200`, `{ "counted": [<ids newly counted>] }`. For a private
channel the caller can't read, `counted` is empty.

### `POST /messages/{id}/forward`
Forwarding a channel post bumps its `forwards` counter. Refused with
`403 PROTECTED_CONTENT` when the channel has `protectedContent`. Full body in
[messages.md](../messages.md).

---

## Polls & quizzes

Send a `POLL` message with a `poll` payload.

### PollCreateDto

| field | type | req | notes |
|---|---|---|---|
| `question` | string | ✔ | ≤ 300. |
| `options` | string[] | ✔ | 2–10 entries, each non-blank ≤ 100, in display order. |
| `allowsMultipleAnswers` | boolean | – | not allowed for quizzes. |
| `anonymous` | boolean | – | default `true`; hides who voted. |
| `quiz` | boolean | – | quiz mode (exactly one correct option). |
| `correctOptionIndex` | int | quiz | index into `options`. |
| `explanation` | string | – | ≤ 200; shown after answering (quiz). |

```json
{ "clientNonce": "…", "type": "POLL", "poll": {
    "question": "Best element?", "options": ["C", "Si", "Fe"],
    "allowsMultipleAnswers": false, "anonymous": true,
    "quiz": false, "correctOptionIndex": null, "explanation": null } }
```

### Voting

- `POST /messages/{id}/poll/votes` — body `{ "optionIndexes": [2] }` (≥ 1;
  multiple only if `allowsMultipleAnswers`). Casts or **changes** the vote.
- `DELETE /messages/{id}/poll/votes` — retract. **Quiz answers are final** — no
  change/retract.
- `POST /messages/{id}/poll/close` — author or admin with `canEditMessages`.

Each vote/retract/close broadcasts **`poll.updated`** on `/messaging/stream`
with the viewer-neutral aggregate.

### PollResponse

Rides on the message wherever it's hydrated. Quiz `correctOptionIndex` +
`explanation` are **hidden until** the viewer votes or the poll closes.

| field | type | notes |
|---|---|---|
| `question` | string | |
| `options` | PollOption[] | `{ index, text, voterCount }`. |
| `allowsMultipleAnswers`, `anonymous`, `quiz` | boolean | |
| `correctOptionIndex` | int | quiz; hidden pre-vote. |
| `explanation` | string | quiz; hidden pre-vote. |
| `closed` | boolean | |
| `totalVoters` | long | |
| `myVotes` | int[] | option indexes the viewer picked (empty if none). |

> This is the in-**post** poll. Story polls are the separate A/B mechanism in
> [stories.md](stories.md#story-polls).

---

## Shared-media gallery & hashtag search

### `GET /conversations/{id}/media?kind=&before=&limit=`
`kind` = `IMAGE|VIDEO|VOICE|AUDIO|GIF|STICKER|VIDEO_NOTE|FILE|LINK`; newest
first; `before={messageId}` cursor; `limit` clamped 1–100. Backed by the
`media_by_conversation` index (one row per attachment; `LINK` rows for bodies
containing a URL) — no timeline scan. Albums appear once per message.
**Response** — `200`, `MessageResponse[]`.

### `GET /conversations/{id}/messages/by-tag?tag=physics`
Exact-tag match via the ES `tags` keyword field, with a bounded Cassandra scan
fallback while the index is cold. **Response** — `200`, `MessageResponse[]`.

# Channels — discussion group, comments, drafts & slow mode

Telegram-style **discussion groups**: link a group to a channel and replies to
channel posts become threaded comments. Also covers per-user drafts and slow
mode.

Base path `/api/v1`. `{id}` = channel UUID; `{postId}` = a post's Snowflake.

---

## Linking a discussion group

### `PUT /channels/{id}/discussion-group/{groupId}` — link
Links a `GROUP` as this channel's discussion group. The caller must hold
`canChangeInfo` on the channel **and** be an admin of the group; a group can
serve **one** channel only. **Response** — `204`. Afterward
`ChannelResponse.linkedGroupId` = `{groupId}`. **Errors** — `403` (not admin of
both), `400` (group already linked elsewhere / not a GROUP).

### `DELETE /channels/{id}/discussion-group` — unlink
Clears the link; commenting is disabled again (`POST …/comments` → `400`).
**Response** — `204`.

---

## Comments

Comments are ordinary messages in the linked group that reply to the channel
post — so edits, reactions, deletes and search all just work on them.

### `POST /channels/{id}/posts/{postId}/comments` — add a comment
The body is a normal [`SendMessageRequest`](posts.md#post-conversationschannelidmessages--post)
(text/media). The caller is **auto-joined** into the discussion group on first
comment (join-source `COMMENT`); the `replyToId` is set to `{postId}`
server-side.

```json
{ "clientNonce": "…", "type": "TEXT", "body": "Great write-up!" }
```

**Response** — `201`, [`MessageResponse`](posts.md#messageresponse) — its
`conversationId` is the **group** id and `replyToId` is `{postId}`. Increments
the post's `comments` count and fires **`message.comment`**
(`messageId` = `{postId}`, `added: true`). **Errors** — `400` (no discussion
group linked), `403` (channel not readable).

### `GET /channels/{id}/posts/{postId}/comments?before=&limit=` — read thread
Newest first, from the single-partition `chat_comments_by_post` Cassandra index.
`before={commentMessageId}` cursor; `limit` default 30 (clamped 1–100).
**Response** — `200`, [`MessageResponse`](posts.md#messageresponse)`[]`.

Deleting a comment (via the normal `DELETE /messages/{id}`) decrements the post's
`comments` count and fires `message.comment` with `added: false`.

---

## Drafts

Per-conversation, per-user server-side drafts (a half-written message follows the
user across devices). Applies to **any** conversation, not just channels.

### `PUT /conversations/{id}/draft` — save / overwrite
**Request** — `SaveDraftRequest` (one draft per conversation per user; this
overwrites):

| field | type | notes |
|---|---|---|
| `body` | string | ≤ 8000. |
| `media` | [MediaRefDto](posts.md#mediarefdto)[] | ≤ 10. |
| `replyToId` | long | the message being replied to. |

**Response** — `200`, `DraftResponse`:
`{ conversationId, body, media, replyToId, updatedAt }`.

### `GET /conversations/{id}/draft` — read
**Response** — `200`, `DraftResponse`. **Errors** — `404` when no draft exists.

### `DELETE /conversations/{id}/draft` — discard
**Response** — `204` (idempotent). **Sending** a message into the conversation
also clears the draft automatically.

---

## Slow mode

`GroupSettings.slowModeSeconds` (set via
`PATCH /conversations/{id} { "settings": { "slowModeSeconds": 60 } }`) throttles
**non-admin** members to one message per window; the owner and admins are exempt.
Over-quota sends return **`429 RATE_LIMIT_EXCEEDED`** (Retry-After semantics).
Primarily for busy discussion groups. `slowModeSeconds: 0` disables it.

# Channels (Telegram-style broadcast)

A **channel** is a broadcast conversation: admins post, everyone else
**subscribes** and reads. It is a `CHANNEL`-typed conversation with an
admins-only send mode, so it reuses the entire message log, read path, search and
realtime — **posting to and reading a channel use the normal
[conversation](conversations.md) / [message](messages.md) endpoints** with the
channel's id. This page adds only channel creation, discovery, and subscription.

Base path: `/api/v1`.

## Endpoints

| Method & path | Body | Does |
|---|---|---|
| `POST /channels` | `CreateChannelRequest` | Create a channel (you become `OWNER`). → `201 ChannelResponse` |
| `GET /channels/discover?q=` | — | Public channels matching `q` (or all), most-subscribed first. → `[ChannelResponse]` |
| `GET /channels/by-handle/{handle}` | — | Look up a public channel by @handle. → `ChannelResponse` |
| `POST /channels/{id}/subscribe` | — | Subscribe to a public channel (idempotent). → `ChannelResponse` |
| `DELETE /channels/{id}/subscribe` | — | Unsubscribe. → `204` |

### CreateChannelRequest
```json
{ "title": "AI Research Digest", "description": "…", "handle": "ai_research", "publicChannel": true }
```
`handle` — 3–32 chars of `a–z`, `0–9`, `_` (a leading `@` is stripped); required
and unique for public channels.

### ChannelResponse
```json
{
  "id": "<uuid>", "handle": "ai_research", "title": "…", "description": "…",
  "publicChannel": true, "subscriberCount": 128, "ownerId": "<uuid>",
  "subscribed": false, "createdAt": "…"
}
```

## Posting & reading
- **Post:** `POST /conversations/{channelId}/messages` — only `OWNER`/`ADMIN`
  succeed (a subscriber gets `403 ADMINS_ONLY`). Every message feature applies
  (media, edit, delete, reactions, pins, scheduled, disappearing).
- **Read:** `GET /conversations/{channelId}/messages` (cursor page), `…/sync`,
  `…/search`, `…/pinned`. New subscribers see the channel's full history.
- **Realtime:** subscribers receive `message.new` / `message.edited` /
  `message.deleted` / `message.reaction` on their `/messaging/stream`, exactly
  like a group.

## Notes
- Private channels (`publicChannel: false`) are not discoverable and reject
  self-subscribe; add members via the group [invite link](groups.md) flow.
- The owner cannot unsubscribe from their own channel.
- Subscribers are `conversation_members`; promote one to `ADMIN` via the
  [group roles](groups.md) endpoint to let them post.

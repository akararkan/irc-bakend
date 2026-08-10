# Channels — overview & lifecycle

A **channel** is a Telegram-style broadcast conversation: admins post, everyone
else **subscribes** and reads. Under the hood it is a `CHANNEL`-typed
[conversation](../conversations.md) with an admins-only send mode, so it reuses
the entire message log, read path, search and realtime — **posting to and
reading a channel use the normal [conversation](../conversations.md) /
[message](../messages.md) endpoints** with the channel's id.

| File | Covers |
|------|--------|
| [overview.md](overview.md) | **Start here** — lifecycle (create/discover/subscribe), profile (photo/cover/verified), settings, `ChannelResponse` |
| [admins.md](admins.md) | Granular admin rights & enforcement, invite links, join requests & approval, transfer/kick/restrict |
| [posts.md](posts.md) | Channel posts — full lifecycle: create, read feed, edit/delete/pin/schedule, reactions, types, polls, views/forwards, gallery, hashtags |
| [discussion.md](discussion.md) | Linked discussion group & comments, per-user drafts, slow mode |
| ~~[stories.md](stories.md)~~ | **⛔ Removed** — channel stories & highlights were deleted; the page is a tombstone. |
| [inbox.md](inbox.md) | Subscriber/member list, the channel in your inbox (read/mute/pin/archive), notifications, deleting the channel |
| [stats.md](stats.md) | Statistics endpoint + connecting to the realtime SSE stream & event catalog |

**Conventions.** Base path `/api/v1`; every endpoint needs
`Authorization: Bearer <accessToken>`. Bodies are JSON unless marked
*multipart*. `{id}` is the channel's conversation **UUID**; message/post ids are
**64-bit Snowflakes** (JSON numbers). Errors use the shared envelope — switch on
`errorCode` ([catalog](../api-reference.md#error-codes)).

---

## `POST /channels` — create

Creates a channel with the caller as `OWNER` (membership stamped join-source
`OWNER`). A public channel **requires** a unique `handle`.

**Request** — `CreateChannelRequest`:

| field | type | req | notes |
|---|---|---|---|
| `title` | string | ✔ | 1–120 chars. |
| `description` | string | – | ≤ 500 chars. |
| `handle` | string | public✔ | 3–32 of `a–z 0–9 _`; a leading `@` is stripped; unique. Ignored for private. |
| `publicChannel` | boolean | – | default `true`. Public = discoverable + self-subscribable. |
| `category` | string | – | ≤ 48-char directory slug (e.g. `"science"`), used by discovery's `category=`. |
| `settings` | ChannelSettings | – | initial settings; [defaults](#channelsettings) when omitted. |

```json
{
  "title": "AI Research Digest", "description": "Weekly papers, distilled.",
  "handle": "ai_research", "publicChannel": true, "category": "science",
  "settings": { "language": "en", "signMessages": true }
}
```

**Response** — `201`, [`ChannelResponse`](#channelresponse).
**Errors** — `400 BAD_REQUEST` (missing title, or public with no/dup handle).

---

## `GET /channels/{id}` — detail

**Response** — `200`, [`ChannelResponse`](#channelresponse) with viewer-relative
flags (`subscribed`, `pendingJoinRequest`, `myRole`).
**Errors** — `404 CONVERSATION_NOT_FOUND`; `403 NOT_A_MEMBER` for a private
channel the caller can't see.

## `GET /channels/by-handle/{handle}` — look up by @handle

`{handle}` without the `@`. **Response** — `200`,
[`ChannelResponse`](#channelresponse). **Errors** — `404` if no public channel
owns that handle.

## `GET /channels/discover?q=&category=` — directory

Public channels, **most-subscribed first**. Both query params optional: `q`
matches title/handle/description; `category` filters by the slug. The result is
**capped at 50** and **not paged** — it's a directory/typeahead, not a full
scan; narrow with `q`/`category` rather than expecting more rows.

**Response** — `200`, `ChannelResponse[]` (a plain JSON array).

---

## `PATCH /channels/{id}` — update info/settings

Requires `canChangeInfo`. All fields optional; **null leaves a field
unchanged**. `settings` is a **whole-object replacement** (send the full
`ChannelSettings` blob).

**Request** — `UpdateChannelRequest`:

| field | type | notes |
|---|---|---|
| `title` | string | ≤ 120. |
| `description` | string | ≤ 500. |
| `handle` | string | ≤ 32; ignored unless the channel is/*becomes* public. |
| `publicChannel` | boolean | toggle; **turning private clears the @handle** (Telegram behavior — going public again needs a handle). |
| `category` | string | ≤ 48; empty string clears it. |
| `settings` | ChannelSettings | full replacement. |

**Response** — `200`, [`ChannelResponse`](#channelresponse). Broadcasts
`conversation.updated` (`CHANNEL_INFO_CHANGED`). **Errors** — `403 ADMINS_ONLY`,
`400 BAD_REQUEST` (e.g. handle taken).

## `DELETE /channels/{id}` — delete the channel

**Owner only.** Soft-deletes the channel for **everyone** — it drops out of all
subscriber inboxes, discovery and by-handle lookups, and is de-indexed from the
public-channel search. Broadcasts `conversation.updated`
(`memberChange: "DELETED"`). **Response** — `204`. **Errors** — `403 NOT_OWNER`,
`403 NOT_A_MEMBER`, `404`. Full lifecycle semantics (including
`DELETE /conversations/{id}` on a channel id):
[inbox.md](inbox.md#leaving--deleting-the-channel).

---

## Photo & cover *(multipart)*

| Endpoint | Body | Response |
|---|---|---|
| `POST /channels/{id}/photo` | multipart `file` (image) | `200 ChannelResponse` (with `avatarUrl`) |
| `DELETE /channels/{id}/photo` | — | `204` |
| `POST /channels/{id}/cover` | multipart `file` (image) | `200 ChannelResponse` (with `coverUrl`) |
| `DELETE /channels/{id}/cover` | — | `204` |

All four require `canChangeInfo`. The image is uploaded through the R2/S3 proxy;
the returned `avatarUrl`/`coverUrl` resolve through it.

## `PUT /channels/{id}/verified?verified=true|false` — verified badge

**Platform `ROLE_ADMIN` only** (not the channel owner). Sets/clears the blue
check. **Response** — `200`, [`ChannelResponse`](#channelresponse). **Errors** —
`403` for non-platform-admins.

---

## Subscribe / unsubscribe

### `POST /channels/{id}/subscribe`
Public channel → subscribes immediately (join-source `DISCOVERY`). If the
channel has `joinByRequest`, this **files a join request** instead (see
[admins.md](admins.md#join-requests)) and returns with `pendingJoinRequest:
true`. **Response** — `200`, [`ChannelResponse`](#channelresponse). **Errors** —
`403` on a private channel (join via invite instead).

### `DELETE /channels/{id}/subscribe`
Unsubscribe — equivalent to `POST /conversations/{id}/leave` on the channel id
(see [inbox.md](inbox.md#leaving--deleting-the-channel)). **Response** — `204`.
The **owner cannot unsubscribe** —
[delete the channel](#delete-channelsid--delete-the-channel) or
[transfer ownership](admins.md#ownership-kick-restrict) instead (`403`).

---

## ChannelResponse

Returned by every create/read/update/subscribe/photo/cover/verified call.

| field | type | notes |
|---|---|---|
| `id` | UUID | the channel = its conversation id; use it on `/conversations/{id}/…`. |
| `handle` | string | public @handle; `null` for private. |
| `title`, `description`, `category` | string | info. |
| `publicChannel` | boolean | |
| `verified` | boolean | platform-granted blue check. |
| `subscriberCount` | long | exact. |
| `postCount` | long | |
| `ownerId` | UUID | |
| `subscribed` | boolean | viewer-relative. |
| `pendingJoinRequest` | boolean | viewer filed a request still awaiting approval. |
| `myRole` | string | `OWNER` / `ADMIN` / `MEMBER`; `null` when not a member. |
| `avatarUrl`, `coverUrl` | string | proxy URLs; `null` when unset. |
| `settings` | [ChannelSettings](#channelsettings) | |
| `linkedGroupId` | UUID | the [discussion group](discussion.md); `null` when unlinked. |
| `createdAt` | timestamp | UTC ISO-8601. |
| `shareUrl` | string | `{base}/c/{handle}`; `null` for private channels. |

```json
{
  "id": "7b1e…", "handle": "ai_research", "title": "AI Research Digest",
  "description": "Weekly papers, distilled.", "category": "science",
  "publicChannel": true, "verified": false,
  "subscriberCount": 128, "postCount": 42, "ownerId": "9c2a…",
  "subscribed": true, "pendingJoinRequest": false, "myRole": "OWNER",
  "avatarUrl": "https://cdn…/avatar.jpg", "coverUrl": null,
  "settings": { "reactionsEnabled": true, "signMessages": true,
                "protectedContent": false, "hiddenSubscribers": false,
                "joinByRequest": false },
  "linkedGroupId": null,
  "createdAt": "2026-07-24T09:12:00.000Z",
  "shareUrl": "https://irc.example.com/c/ai_research"
}
```

## ChannelSettings

A single JSONB blob; `PATCH … { "settings": … }` replaces it whole.
`@JsonInclude(NON_NULL)` — unset string knobs are omitted from responses.

| knob | type | default | meaning |
|---|---|---|---|
| `language`, `country`, `region` | string | – | info labels (ISO code / free-form region). |
| `accentColor`, `emojiStatus`, `wallpaper` | string | – | appearance hints (client-rendered). |
| `reactionsEnabled` | boolean | `true` | `false` → reacting returns `403 REACTIONS_DISABLED`. |
| `allowedReactions` | string[] | null | non-empty list whitelists emoji; others → `403 REACTIONS_DISABLED`. |
| `protectedContent` | boolean | `false` | posts can't be forwarded out (`403 PROTECTED_CONTENT`); clients disable copy/save. |
| `hiddenSubscribers` | boolean | `false` | member list is admins-only (`403 SUBSCRIBERS_HIDDEN`); the count stays public. |
| `signMessages` | boolean | `false` | posts carry the posting admin's `authorSignature`. |
| `joinByRequest` | boolean | `false` | public subscribe files a join request instead of joining. |

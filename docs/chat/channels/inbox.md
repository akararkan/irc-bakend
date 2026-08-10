# Channels — subscribers, inbox & lifecycle

The subscriber-facing side: seeing the member list, and managing a channel as an
entry in **your** inbox (read state, mute, pin-to-top, archive), plus leaving
and deleting the channel. These are the shared
[conversation](../conversations.md) endpoints applied to a channel's id.

Base path `/api/v1`. `{id}` = channel UUID.

---

## Subscribers (member list)

### `GET /conversations/{id}/members?page=&size=`
The channel's members. `size` default 30 (clamped). **Response** — `200`,
a Spring `Page<MemberResponse>`:

| field | type | notes |
|---|---|---|
| `userId` | UUID | |
| `username`, `fullName` | string | |
| `role` | string | `OWNER` / `ADMIN` / `MEMBER`. |
| `status` | string | `ACTIVE` / `RESTRICTED` / `LEFT` / … |
| `joinedAt` | timestamp | |

When the channel has [`hiddenSubscribers`](overview.md#channelsettings), this is
**admins-only** — a non-admin gets `403 SUBSCRIBERS_HIDDEN` (the
`subscriberCount` on `ChannelResponse` stays public).

> Adding members directly is `POST /conversations/{id}/members
> { "userIds": [...] }` (requires `canInviteUsers`; join-source `ADDED_BY_ADMIN`).
> Kick / role / restrict live in [admins.md](admins.md#ownership-kick-restrict).

---

## The channel in your inbox

A channel appears in your inbox like any conversation
(`GET /conversations` / `GET /conversations/archived`), rendered as a
[`ConversationResponse`](../conversations.md) with `type: "CHANNEL"` — carrying
`unreadCount`, `hasUnread`, `mutedUntil`, `pinned`, `archived`, `lastMessageId`,
`lastMessagePreview`, etc. Manage that per-user state with:

| Method & path | Body | Effect |
|---|---|---|
| `POST /conversations/{id}/read` | `{ "lastReadMessageId": <snowflake> }` | Advance the read marker (clears unread). → `200` |
| `POST /conversations/{id}/unread` | — | Flag the chat unread until next opened. → `200` |
| `POST /conversations/{id}/mute` | `{ "mutedUntil": "2026-08-01T00:00:00" \| null }` | Mute until a time; `null` unmutes. → `200` |
| `POST /conversations/{id}/pin` | `{ "pinned": true }` | Pin the channel to the top of your inbox. → `200` |
| `POST /conversations/{id}/archive` | `{ "archived": true }` | Move to / out of the archive. → `200` |

These are **per-subscriber** and independent of the channel's own settings —
muting a channel only silences *your* notifications.

---

## Notifications

- **Every non-silent post** produces a persisted `CHANNEL_NEW_POST` inbox
  notification for **every active, non-muted subscriber** — at any channel
  size, online or not (a channel post is content, like `POST_NEW`). Posts
  coalesce per channel: a burst within the 60-minute window is **one** row
  ("Channel title: latest preview" with a bumped count), deep-linking to
  `/channels/{id}`. The fan-out runs async with a keyset scan (500-row pages,
  capped at 50 000 subscribers) and excludes muted members **in the query**.
- A [`silent`](posts.md#post-conversationschannelidmessages--post) post
  delivers, counts as unread, and appears in realtime — but skips the bell
  fan-out entirely (Telegram's "post without notification").
- Muting the channel (`POST /conversations/{id}/mute`) stops its bell rows
  while `mutedUntil` is in the future; unread still accrues. Exception: a post
  that `@`-mentions you by name pings through the mute as a `MESSAGE_MENTION`
  (and replaces your `CHANNEL_NEW_POST` for that post) — see
  [mentions](../../platform/mentions.md#chat--channel-mentions).
- Admin-facing: `CHANNEL_JOIN_REQUEST` (a new request, aggregated per channel);
  the requester gets `CHANNEL_JOIN_APPROVED` on approval — see
  [admins.md](admins.md#join-requests).
- All channel kinds are **in-app only** (never emailed) and land in the `CHAT`
  inbox tab. Transport + inbox API:
  [notifications/](../../notifications/notifications.md#chat-channel--live-notifications).

---

## Leaving & deleting the channel

### `POST /conversations/{id}/leave` · `DELETE /channels/{id}/subscribe` — leave

Equivalent endpoints: leaving a channel **is** unsubscribing. Your membership
row is removed (no `LEFT` tombstone, no SYSTEM message — channels don't announce
subscriber churn), `memberCount` drops by one, the remaining members get
`member.changed` (`UNSUBSCRIBED`, applied as a −1 delta) and your own tabs get
it directly, your unread badge is invalidated, and the chat disappears from your
inbox. Re-subscribing later brings it back with full history (channel history is
always visible to new subscribers). **Response** — `204`, idempotent (leaving a
channel you're not in is a no-op `204`). The **owner cannot leave** → `403
ACCESS_FORBIDDEN` — [transfer ownership](admins.md#ownership-kick-restrict) or
delete the channel instead.

### `DELETE /conversations/{id}` — delete the chat

Telegram parity — what happens depends on who you are:

- **Owner** → **deletes the channel for everyone**, identical to
  [`DELETE /channels/{id}`](overview.md#delete-channelsid--delete-the-channel)
  below.
- **Subscriber** → **leaves the channel** (exactly the semantics above). The
  chat drops off your list **for good** — unlike a DM/group "delete for me" it
  does not resurface on the channel's next post, because you are no longer a
  member.

**Response** — `204`. **Errors** — `403 NOT_A_MEMBER` (never subscribed);
`404` (unknown/already-deleted conversation).

### `DELETE /channels/{id}` — delete the channel *(owner only)*

Soft-deletes the channel for **everyone**: the thread drops out of every
subscriber's inbox at once, posting/reading starts failing, discovery and
by-handle lookups 404, and the channel is removed from the public-channel
search index. Member rows and the message log are retained (nothing is
purged). Broadcasts `conversation.updated` with `memberChange: "DELETED"` to
every active member. **Response** — `204`. **Errors** — `403 NOT_OWNER`
(admins and subscribers can't delete); `403 NOT_A_MEMBER`; `404` (unknown
channel).

# Channels — subscribers, inbox & lifecycle

The subscriber-facing side: seeing the member list, and managing a channel as an
entry in **your** inbox (read state, mute, pin-to-top, archive), plus deleting
the channel. These are the shared [conversation](../conversations.md) endpoints
applied to a channel's id.

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

- Each non-silent post notifies subscribers (respecting each subscriber's mute
  and email/push preferences). A [`silent`](posts.md#post-conversationschannelidmessages--post)
  post delivers with **no push**.
- Admin-facing: `CHANNEL_JOIN_REQUEST` (a new request, aggregated per channel);
  the requester gets `CHANNEL_JOIN_APPROVED` on approval — see
  [admins.md](admins.md#join-requests).
- Notification transport and the inbox API live in
  [notifications/](../../notifications/notifications.md).

---

## Deleting the channel

### `DELETE /conversations/{id}`
Owner only. Soft-deletes the channel (async cascade of its messages/members).
**Response** — `204`. The **owner cannot unsubscribe** while the channel exists
(`DELETE /channels/{id}/subscribe` → `403`) — delete it, or
[transfer ownership](admins.md#ownership-kick-restrict) first.

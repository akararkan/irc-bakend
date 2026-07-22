# 04 — Group Chats (Telegram / Messenger style)

Groups are just `conversations` with `type = GROUP` and more than two members.
Everything about the message log, delivery, and requests is identical; what's new
is **roles, an admin permission matrix, member lifecycle, and system messages**.

## Roles

| Role | Who | Notes |
|------|-----|-------|
| **OWNER** | The creator (exactly one) | Ultimate authority; can transfer ownership; only role that can delete the group |
| **ADMIN** | Promoted by owner/admin | Manages members and content per the matrix below |
| **MEMBER** | Everyone else | Sends and reads (unless `adminsOnly` mode) |

Plus a per-member **status**: `ACTIVE`, `RESTRICTED` (read-only / muted by
admins), `LEFT` (voluntary), `REMOVED` (kicked). Status is orthogonal to role.

## Permission matrix

| Action | OWNER | ADMIN | MEMBER |
|--------|:----:|:----:|:----:|
| Send messages | ✅ | ✅ | ✅¹ |
| Add members | ✅ | ✅ | ⚙️² |
| Remove members | ✅ | ✅³ | ❌ |
| Promote to admin | ✅ | ⚙️⁴ | ❌ |
| Demote admin | ✅ | ❌ | ❌ |
| Restrict / mute a member | ✅ | ✅³ | ❌ |
| Edit group name/avatar | ✅ | ✅ | ⚙️² |
| Change group settings | ✅ | ✅ | ❌ |
| Pin / unpin a message | ✅ | ✅ | ⚙️² |
| Delete anyone's message | ✅ | ✅ | ❌ |
| Delete own message | ✅ | ✅ | ✅ |
| Create/revoke invite link | ✅ | ✅ | ❌ |
| Transfer ownership | ✅ | ❌ | ❌ |
| Delete the group | ✅ | ❌ | ❌ |

¹ unless the group is in `adminsOnly` send mode.
² governed by a group setting (`whoCanAddMembers`, `whoCanEditInfo`, `whoCanPin`)
that the owner sets to `ALL_MEMBERS` or `ADMINS_ONLY`.
³ an admin cannot act on the owner or on another admin — only on members.
⁴ only if the group setting `adminsCanPromote` is on; otherwise owner-only.

Encode this as a single pure function `can(actor, action, target?, settings)` so
every controller calls the same authority check. Never scatter role logic across
endpoints.

## Group settings (stored on the conversation)

```jsonc
{
  "sendMode":       "ALL_MEMBERS | ADMINS_ONLY",
  "whoCanAddMembers":"ALL_MEMBERS | ADMINS_ONLY",
  "whoCanEditInfo":  "ALL_MEMBERS | ADMINS_ONLY",
  "whoCanPin":       "ALL_MEMBERS | ADMINS_ONLY",
  "adminsCanPromote": true,
  "joinBy":          "INVITE_ONLY | LINK",
  "historyForNewMembers": "VISIBLE | HIDDEN"   // do new joiners see old messages
}
```

Store as a `JSONB` column on `conversations` so you can add knobs without
migrations.

## Member lifecycle

```
                 add/invite            promote            demote
   (none) ─────────────────► MEMBER ───────────► ADMIN ──────────► MEMBER
      ▲                        │  ▲                                   │
      │        leave           │  │           restrict/unrestrict     │
      │◄───────────────────────┘  └── ACTIVE ◄────────────► RESTRICTED┘
      │        remove (kick)
      └──────────────────────── REMOVED
```

- **Add**: insert `conversation_members` row; emit a `SYSTEM` message
  `MEMBER_ADDED`; the new member gets a `member.changed` + fresh inbox entry.
- **Leave**: set `status = LEFT`; system message `MEMBER_LEFT`.
- **Remove (kick)**: set `status = REMOVED`; system message `MEMBER_REMOVED`;
  their read access stops immediately.
- **Restrict**: `status = RESTRICTED`; can read, `authorizeGroupSend` denies
  posting.
- **Promote/Demote**: change `role`; system message; new permissions apply on
  next action (invalidate any cached role in Redis).

## System messages

Group events are stored as ordinary rows in `messages_by_conversation` with
`type = SYSTEM` and a `system_event` code — so they appear inline in the
timeline, paginate normally, and need no separate mechanism.

| `system_event` | Rendered as |
|----------------|-------------|
| `GROUP_CREATED` | "Aram created the group" |
| `MEMBER_ADDED` | "Aram added Sara" |
| `MEMBER_LEFT` | "Sara left" |
| `MEMBER_REMOVED` | "Aram removed Sara" |
| `ROLE_CHANGED` | "Aram made Sara an admin" |
| `TITLE_CHANGED` | "Aram changed the group name to …" |
| `AVATAR_CHANGED` | "Aram changed the group photo" |
| `PINNED` | "Aram pinned a message" |

The client renders SYSTEM messages centred and non-interactive.

## Creating a group

```
POST /api/v1/conversations
{ "type": "GROUP", "title": "Fiqh Study Circle", "memberIds": ["...","..."] }
```

- Creator becomes `OWNER`; listed members inserted as `ACTIVE MEMBER`.
- **Each added member is still subject to the direct-message permission engine**
  against the *creator* for the purpose of whether they can be silently added vs.
  need to consent — a good abuse guard is: you can only add users who are
  CONNECTED to you (or the group is invite-link based). Decide this policy once
  and enforce it in `authorizeAddMember`.
- First message written is `GROUP_CREATED`.

## Invite links (optional, Telegram-style)

- `POST /conversations/{id}/invite-link` → returns an opaque token (store hash +
  `expires_at` + optional `max_uses` in Postgres).
- `POST /conversations/join?token=...` validates and inserts the member.
- Revoking rotates the token. Rate-limit joins per token in Redis.

## Group size tiers

| Tier | Members | Fan-out strategy |
|------|---------|------------------|
| Small | ≤ 256 | Eager unread fan-out (increment every member) — simple, fine |
| Large | ≤ 10k | Lazy/approximate unread (compute on read); batch fan-out publishes |
| Broadcast/Channel | > 10k | One-way channel model; no per-member unread; read state approximate |

Start with Small only; the schema already supports the rest without change. The
fan-out cutoff is the one place group size affects the design — detailed in
[06-algorithms.md](06-algorithms.md).

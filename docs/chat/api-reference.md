# Chat API — Reference Index

The chat & messaging API, documented **per topic** (mirroring the other feature
folders). This page holds the cross-cutting conventions and the full error-code
catalog; the endpoints themselves live in the focused files below.

## Endpoint topics

| File | Covers |
|------|--------|
| [conversations.md](conversations.md) | Inbox, create DM/group, get/update/delete (incl. "delete for me"), read marker, mark-unread, mute/pin/archive, disappearing timer, description |
| [messages.md](messages.md) | Send (idempotent), read (cursor page + gap sync), edit, delete (for me / everyone), forward, reactions, star, seen-by, scheduled, pinned messages, delivered receipts |
| [groups.md](groups.md) | Members, roles, restrict, leave, transfer ownership, invite links, join |
| [message-requests.md](message-requests.md) | The Message Requests inbox — accept / decline / block, and the stranger-contact flow |
| [channels/](channels/overview.md) | Telegram-style broadcast channels — [overview](channels/overview.md), [admins & invites](channels/admins.md), [posts](channels/posts.md), [discussion & drafts](channels/discussion.md), [inbox & members](channels/inbox.md), [stats & realtime](channels/stats.md) |
| [realtime.md](realtime.md) | The single per-user SSE stream + event catalog, typing, presence, unread badge |
| [settings.md](settings.md) | Chat privacy settings — read receipts, last-seen, typing (the symmetric-gate model) |
| [search.md](search.md) | In-conversation and cross-conversation message search |

## Conventions

| Aspect | Rule |
|--------|------|
| **Base path** | `/api/v1` |
| **Auth** | `Authorization: Bearer <accessToken>` on every endpoint. SSE also accepts `?token=<accessToken>` (EventSource can't set headers). Enforced by `@PreAuthorize` (secure-by-default). See [../user/security-model.md](../user/security-model.md). |
| **Errors** | The shared `ApiErrorResponse` envelope — switch on `errorCode`. Catalog below; full envelope in [../errors/error-handling.md](../errors/error-handling.md). |
| **IDs** | `conversationId`, `userId`, `messageRequestId` are **UUIDs**. `messageId`, `lastMessageId`, `replyToId`, `cursor` are **64-bit Snowflakes** (`bigint`) — JSON numbers, treated as opaque sortable integers. |
| **Postgres lists** | `?page=&size=&sort=` → Spring `Page<T>`. Default size 20 (members 30). |
| **Cassandra reads** | `?cursor=&limit=` cursor paging; `limit` clamped to **[1, 100]**. |
| **Timestamps** | UTC ISO-8601 `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`. |

## Error codes

| `errorCode` | HTTP | Meaning |
|-------------|------|---------|
| `BLOCKED` | 403 | A block relationship prevents messaging (never reveals who blocked whom). |
| `NOT_A_MEMBER` | 403 | Not a member of the conversation. |
| `READ_ONLY` | 403 | You are restricted from posting/interacting in this group. |
| `ADMINS_ONLY` | 403 | Action requires admin/owner (or admins-only send mode). |
| `NOT_OWNER` | 403 | Action requires the owner. |
| `REACTIONS_DISABLED` | 403 | The channel has reactions turned off (or the emoji isn't in `allowedReactions`). |
| `PROTECTED_CONTENT` | 403 | The channel forbids forwarding its posts out. |
| `SUBSCRIBERS_HIDDEN` | 403 | The channel's subscriber list is admins-only. |
| `CANNOT_ACT_ON_ADMIN` | 403 | An admin tried to act on the owner or another admin. |
| `REQUEST_LIMIT_REACHED` | 403 | Stranger exceeded the pre-acceptance message cap, or the request was declined. |
| `INVITE_INVALID` | 403 | Invite token expired / revoked / exhausted. |
| `CONVERSATION_NOT_FOUND` | 404 | — |
| `MESSAGE_NOT_FOUND` | 404 | — |
| `ACCESS_FORBIDDEN` | 403 | Generic authorization failure (e.g. editing another's message). |
| `BAD_REQUEST` | 400 | Validation / semantic error. |
| `RATE_LIMIT_EXCEEDED` | 429 | Send rate exceeded. |

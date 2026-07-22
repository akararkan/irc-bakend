# 07 — API Surface

Endpoints in your existing conventions: `Authorization: Bearer <jwt>`, the shared
`ApiErrorResponse` envelope with `errorCode`, **cursor** paging for
Cassandra-backed reads and **page/size** for Postgres lists, SSE streams accept
`?token=`. Base path `/api/v1`.

## Conversations (Postgres-backed lists → page/size)

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/conversations?page=&size=` | My inbox, newest first (pinned on top) |
| `POST` | `/conversations` | Create DIRECT (get-or-create) or GROUP |
| `GET` | `/conversations/{id}` | Conversation metadata + my member state |
| `PATCH` | `/conversations/{id}` | Edit group title/avatar/settings (permission-checked) |
| `DELETE` | `/conversations/{id}` | Owner deletes group / user hides DM |
| `POST` | `/conversations/{id}/read` | Advance read marker `{ lastReadMessageId }` |
| `POST` | `/conversations/{id}/mute` | `{ mutedUntil }` or null to unmute |
| `POST` | `/conversations/{id}/pin` | Pin/unpin in my inbox `{ pinned }` |
| `POST` | `/conversations/{id}/archive` | Archive/unarchive `{ archived }` |

**Create DIRECT**

```jsonc
POST /conversations
{ "type": "DIRECT", "recipientId": "..." }
// → 200 existing or 201 new; race-safe via direct_key
```

## Messages (Cassandra-backed reads → cursor)

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/conversations/{id}/messages?cursor=&limit=` | Page of messages, newest→older (bucket walk) |
| `GET` | `/conversations/{id}/messages?after=<id>` | Gap sync: everything newer than `id` |
| `POST` | `/conversations/{id}/messages` | Send a message |
| `GET` | `/messages/{messageId}` | Single message (reply/jump/forward) |
| `PATCH` | `/messages/{messageId}` | Edit own message `{ body }` |
| `DELETE` | `/messages/{messageId}` | Soft-delete (own; or any for group admins) |
| `POST` | `/messages/{messageId}/react` | `{ emoji }` add/toggle |
| `DELETE` | `/messages/{messageId}/react` | Remove my reaction |
| `POST` | `/conversations/{id}/messages/{messageId}/forward` | Forward to another conversation |
| `POST` | `/messages/{messageId}/delivered` | Mark delivered (from recipient device) |

**Send**

```jsonc
POST /conversations/{id}/messages
{
  "clientNonce": "c1f...",          // required — idempotency key
  "type": "TEXT",                   // TEXT|IMAGE|VIDEO|VOICE|FILE
  "body": "salam, how's the paper?",
  "replyToId": 172630000000000000,  // optional
  "media": [                        // optional; keys already uploaded via media API
    { "kind": "audio", "storageKey": "r2://voice/ab12.opus", "durationMs": 4200, "waveform": "..." }
  ]
}
// → 201 { message }, or 200 { message } on nonce replay
// errorCodes: BLOCKED, NOT_A_MEMBER, READ_ONLY, ADMINS_ONLY, REQUEST_LIMIT_REACHED
```

Media itself is uploaded through your **existing media/R2 proxy**, then referenced
here by `storageKey` — chat doesn't re-implement upload.

## Group membership

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/conversations/{id}/members?page=&size=` | List members + roles |
| `POST` | `/conversations/{id}/members` | Add members `{ userIds[] }` |
| `DELETE` | `/conversations/{id}/members/{userId}` | Remove (kick) |
| `POST` | `/conversations/{id}/members/{userId}/role` | Promote/demote `{ role }` |
| `POST` | `/conversations/{id}/members/{userId}/restrict` | Restrict/unrestrict `{ restricted }` |
| `POST` | `/conversations/{id}/leave` | Leave the group |
| `POST` | `/conversations/{id}/invite-link` | Create/rotate invite link |
| `DELETE` | `/conversations/{id}/invite-link` | Revoke |
| `POST` | `/conversations/join` | Join via `{ token }` |
| `POST` | `/conversations/{id}/transfer-owner` | `{ newOwnerId }` (owner only) |

## Message requests (Postgres → page/size)

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/message-requests?status=PENDING&page=&size=` | My requests inbox |
| `POST` | `/message-requests/{id}/accept` | Accept → graduates to normal chat |
| `POST` | `/message-requests/{id}/decline` | Decline (hide) |
| `POST` | `/message-requests/{id}/block` | Decline + block the requester |

## Realtime

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/messaging/stream?token=<jwt>` | The single SSE stream (all events) |
| `POST` | `/conversations/{id}/typing` | `{ isTyping }` ephemeral |

## Search & presence

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/conversations/{id}/messages/search?q=&page=&size=` | In-conversation search (Elasticsearch) |
| `GET` | `/messaging/search?q=` | Cross-conversation search |
| `GET` | `/presence?userIds=` | Batch presence lookup (Redis) |

## Error codes (add to your existing catalogue)

| `errorCode` | Meaning |
|-------------|---------|
| `BLOCKED` | Block relationship prevents messaging |
| `NOT_A_MEMBER` | Not a member of the conversation |
| `READ_ONLY` | Member is restricted / group is admins-only |
| `ADMINS_ONLY` | Action requires admin/owner |
| `NOT_OWNER` | Action requires owner |
| `CANNOT_ACT_ON_ADMIN` | Admin tried to act on owner/another admin |
| `REQUEST_LIMIT_REACHED` | Stranger exceeded pre-acceptance message cap |
| `INVITE_INVALID` | Invite token expired/revoked/used up |
| `CONVERSATION_NOT_FOUND` | — |
| `MESSAGE_NOT_FOUND` | — |

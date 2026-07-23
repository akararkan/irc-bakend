# Chat & Messaging

The Messenger/Telegram-style chat API, built on the platform's existing stack
(Spring Boot + Cassandra + PostgreSQL + Redis + Elasticsearch + SSE) and reusing
the `follow / block / restrict` social model — **no new transport, no WebSockets**.

The message log lives in **Cassandra** (partitioned per conversation + time
bucket, time-sortable **Snowflake** IDs); conversations, members, roles, read
state and requests live in **PostgreSQL**; presence, typing, unread badges and
idempotency nonces live in **Redis**; realtime fans out over **Redis pub/sub → SSE**;
search is **Elasticsearch**; media rides the existing R2/S3 proxy.

## API reference — one file per topic

| File | Covers |
|------|--------|
| [api-reference.md](api-reference.md) | **Start here** — conventions (auth, ids, paging) + the full error-code catalog |
| [conversations.md](conversations.md) | Inbox, create DM/group, get/update/delete (incl. "delete for me"), read/**mark-unread**/mute/pin/archive, **disappearing timer**, description |
| [messages.md](messages.md) | Send (idempotent), read (cursor page + gap sync), edit, **delete for me / everyone**, forward, reactions, **star**, **seen-by**, **scheduled**, pinned, delivered |
| [groups.md](groups.md) | Members, roles, restrict, leave, transfer ownership, invite links, join |
| [message-requests.md](message-requests.md) | The Message Requests inbox + the stranger-contact flow |
| [channels/](channels/overview.md) | Telegram-style broadcast **channels** — a subdirectory: [overview](channels/overview.md) · [admins & invites](channels/admins.md) · [posts](channels/posts.md) · [discussion & drafts](channels/discussion.md) · [stories & highlights](channels/stories.md) · [inbox & members](channels/inbox.md) · [stats & realtime](channels/stats.md) |
| [calls.md](calls.md) | Voice/video **calls** — ring/answer/decline/end + WebRTC SDP/ICE relay |
| [live-streaming.md](live-streaming.md) | **Live streaming** — go live, viewer registry, live chat |
| [realtime.md](realtime.md) | The single per-user SSE stream + event catalog, typing, presence, unread badge |
| [settings.md](settings.md) | Chat privacy — read receipts, last-seen, typing (the symmetric-gate model) |
| [search.md](search.md) | In-conversation and cross-conversation message search |

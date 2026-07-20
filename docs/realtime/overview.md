# Realtime Architecture Overview

The platform's realtime layer is **100% Server-Sent Events (SSE) + Redis pub/sub**. There are no
WebSockets anywhere: every live surface (notification inbox, post detail, story viewer, story tray,
Q&A page, research page, activity feed, admin audit console) is an `EventSource` subscription to an
HTTP `text/event-stream` endpoint, and **Redis pub/sub** fans events out across app instances.
RabbitMQ handles *asynchronous work* (notification creation, analytics), not browser delivery — see
[messaging.md](messaging.md).

```
service (tx commits) ──► *RealtimePublisher ──► Redis channel ──► *RealtimeSubscriber (every instance)
                                                                        │
                                                                        ▼
                                                            *RealtimeService (local SseEmitter registry)
                                                                        │
                                                                        ▼
                                                              browser EventSource
```

Each domain has a `realtime` package with the same four roles:

- `*RealtimeService` — per-instance `SseEmitter` registry (subscribe / broadcast / heartbeat)
- `*RealtimePublisher` — serialises the event and `PUBLISH`es it to a Redis channel
- `*RealtimeSubscriber` — receives the Redis message on every instance, forwards to the local service
- `*RealtimeBroadcaster` — defers the publish until **after the transaction commits**, so
  subscribers never see data that rolls back

All wired together in `src/main/java/ak/dev/irc/app/config/RedisMessagingConfig.java`.

---

## SSE endpoint catalog

| Endpoint | Auth | Event names | Heartbeat | Timeout | Notes |
|---|---|---|---|---|---|
| `GET /api/v1/notifications/stream` | Bearer or `?token=` (401 if neither) | `connected`, `notification`, `unread-count`, `read`, `deleted`, `heartbeat` | 15 s | 24 h | Max **5 concurrent connections per user** — oldest tab is evicted (LRU). Sends `retry: 3000` and a 2 KB comment frame to flush proxy buffers. |
| `GET /api/v1/stories/tray/stream` | Bearer or `?token=` (401 plain-text if neither) | `connected`, `new_story`, `story_removed`, `poll_vote_cast`, `heartbeat` | 25 s | 10 min | Per-viewer channel. Event names are **lowercased** `StoryTrayEventType` values. `poll_vote_cast` goes to the **story author only** and carries `pollId`, `voteA`, `voteB`, `voteTotal`. |
| `GET /api/v1/stories/{storyId}/stream` | Bearer or `?token=` (401 if neither) | `connected`, then **lowercased** `StoryRealtimeEventType`: `story_viewed`, `story_reacted`, `story_unreacted`, `story_replied`, `story_poll_voted`, `story_expired`, `story_deleted`, `view_count_updated`, `reaction_count_updated`, `reply_count_updated`, `heartbeat` | 25 s | 5 min | **New:** per-story live stream (defined in `CassandraStoryController`). Short timeout matches story-viewing sessions; `EventSource` auto-reconnects. |
| `GET /api/v1/posts/{id}/stream` | Optional — Bearer, `?token=`, or **anonymous** | `connected`, then **UPPERCASE** `PostRealtimeEventType`: `REACTION_ADDED/CHANGED/REMOVED`, `COMMENT_CREATED/EDITED/DELETED`, `REPLY_CREATED`, `COMMENT_REACTION_ADDED/CHANGED/REMOVED`, `VIEW_COUNT_UPDATED`, `SHARE_COUNT_UPDATED`, `SAVE_COUNT_UPDATED`, `POST_UPDATED`, `POST_DELETED`, `heartbeat` | 25 s | 24 h | Sends `retry: 3000`. Actor-echo suppression (see below). One JSON encode per broadcast regardless of subscriber count. |
| `GET /api/v1/researches/{researchId}/stream` | Optional Bearer (anonymous allowed; no `?token=` param) | `connected`, then **UPPERCASE** `ResearchRealtimeEventType`: reactions, comments/replies, comment reactions, `VIEW/DOWNLOAD/SHARE/SAVE/CITATION/REACTION/COMMENT_COUNT_UPDATED`, `RESEARCH_UPDATED/DELETED/PUBLISHED`, `heartbeat` | 25 s | none explicit (`0L`) | Actor-echo suppression. |
| `GET /api/v1/questions/{questionId}/stream` | Optional Bearer (anonymous allowed; no `?token=` param) | `connected`, then **UPPERCASE** `QnaRealtimeEventType`: `ANSWER_CREATED`, `REANSWER_CREATED`, `ANSWER_EDITED/DELETED`, `ANSWER_REACTION_ADDED/CHANGED/REMOVED`, `ANSWER_ACCEPTED/UNACCEPTED`, `QUESTION_UPDATED/DELETED/LOCKED/UNLOCKED`, `VIEW/SAVE/SHARE_COUNT_UPDATED`, `heartbeat` | 25 s | none explicit (`0L`) | Touches the question first so a missing/deleted question fails fast with the standard 404 instead of opening a zombie stream. Actor-echo suppression. |
| `GET /api/v1/users/me/activity/stream` | Bearer or `?token=` | `connected`, then the **UPPERCASE** `UserActivityType` name of each activity row (`POST_CREATED`, `GLOBAL_SEARCH`, `PROFILE_VIEW`, `STORY_VIEWED`, …), `heartbeat` | 25 s | none explicit (`0L`) | Every tab/device of the same user receives its own activity the moment the row is written. |
| `GET /api/v1/admin/audit/stream` | Admin only (chain-level `/api/v1/admin/**` requires `ROLE_ADMIN`) | `connected`, `audit` (one per audit row), `heartbeat` | 25 s | none explicit (`0L`) | Global firehose of every user action across all instances. |

Event names come straight from the `*RealtimeService` classes — note the casing split: **story and
story-tray streams lowercase** their enum names; **post, Q&A, research and activity streams use the
uppercase enum names verbatim**. Register `addEventListener` handlers accordingly.

## Authentication

Browser `EventSource` **cannot set request headers**, so every stream that needs identity accepts
the JWT access token as a query parameter:

```js
const es = new EventSource(`/api/v1/notifications/stream?token=${accessToken}`);
```

The controllers validate it with `JwtTokenProvider` (must be a valid `ACCESS`-type token) and fall
back to the security context when a Bearer header *is* present (server-to-server, fetch-based SSE).
Streams that require identity respond **401 without a JSON body** when unauthenticated — writing
the normal error envelope to an SSE-negotiated request would trigger a content-negotiation cascade;
see [../errors/error-handling.md](../errors/error-handling.md#sse-error-semantics). The post stream
is the one fully **anonymous-capable** stream (public posts are viewable logged-out); Q&A and
research streams also accept anonymous subscribers (Bearer only, no `?token=`).

## The delta model

**Realtime events carry the event type and its context — never authoritative counter values.**
Clients apply `+1`/`-1` to their locally rendered counters based on the event type (and direction
flags like `PostRealtimeEvent.saved` for `SAVE_COUNT_UPDATED`), then reconcile with the true
numbers on the next REST read. This avoids clients re-rendering stale counters that raced each
other over the wire.

- `PostRealtimeEvent` still *declares* legacy counter fields (`postReactionCount`, …) but they are
  `@JsonInclude(NON_NULL)` and no longer populated by publishers — with one deliberate exception:
  `SHARE_COUNT_UPDATED` carries a fresh `postShareCount` (shares have no client-side toggle state
  to delta from).
- Story-tray `poll_vote_cast` carries full tallies (`voteA`/`voteB`/`voteTotal`) because it renders
  a live poll result, not a delta-able counter.

## Actor-echo suppression

On topic streams (post, Q&A, research), each subscription remembers its `viewerId`, and each event
carries the `actorId` who triggered it. The broadcast loop **skips the actor's own subscription** —
the actor already has the result from their originating HTTP response, and echoing the event back
would render the comment/reaction twice. Other tabs of the *same user* still receive the event only
if they subscribed anonymously or as a different viewer; the notification and activity streams are
per-user by design and do not suppress.

The story stream does not track per-subscriber viewer ids and broadcasts to all story subscribers.

## Heartbeats & reconnect guidance

- Heartbeat cadence: **15 s** on the notification stream, **25 s** everywhere else — always faster
  than the ~30 s idle timeout of typical proxies (Cloudflare, Nginx, Railway edge).
- The notification and post streams send `retry: 3000` (`reconnectTime(3000L)`) in the handshake so
  `EventSource` backs off **3 s** between reconnect attempts instead of hammering a restarting JVM
  every ~100 ms.
- Finite timeouts (24 h posts/notifications, 10 min tray, 5 min story) deliberately avoid `0L`,
  which some servlet containers interpret as "default 30 s". On expiry the emitter completes and
  `EventSource` reconnects automatically.
- Streams set `X-Accel-Buffering: no` + `Cache-Control: no-cache` where proxies would otherwise
  buffer; the notification stream additionally pushes a 2 KB SSE comment frame to force an
  immediate flush.
- Client rule of thumb: treat a missed heartbeat (> 2× cadence) as a dead connection, close, and
  resubscribe; on `connected`, re-fetch the resource once via REST to reconcile counters (see the
  delta model above).

## Redis channels (multi-instance fan-out)

`SseEmitter` registries are **per-instance**, so every event is published to Redis and re-delivered
on all instances; only the instance holding the subscriber's connection actually writes to the
socket. Channel names (each `*RealtimePublisher` owns its prefix):

| Channel | Scope | Publisher class |
|---|---|---|
| `irc:notifications:{userId}` | per user | `NotificationRedisPublisher` |
| `irc:posts:{postId}` | per post | `PostRealtimePublisher` |
| `irc:stories:{storyId}` | per story | `StoryRealtimePublisher` |
| `irc:story-tray:{viewerId}` | per viewer | `StoryTrayRealtimePublisher` |
| `irc:questions:{questionId}` | per question | `QnaRealtimePublisher` |
| `irc:research:{researchId}` | per research | `ResearchRealtimePublisher` |
| `irc:activity:{userId}` | per user | `UserActivityRealtimePublisher` |
| `irc:audit:stream` | global (single channel) | `AuditRealtimePublisher` |
| `irc:feed:{userId}` | per user | `FeedRealtimePublisher` (home-feed push namespace; published on fanout, no SSE bridge wired in `RedisMessagingConfig` yet) |

`RedisMessagingConfig` subscribes with `PatternTopic(prefix + "*")` (the audit stream is a plain
`ChannelTopic`), dispatches on a **bounded** pool (`redis-sub-*`, 4–8 threads, queue 1000,
caller-runs on overload so bursts backpressure instead of dropping events), retries the connection
every 5 s, and tolerates Redis being down at boot (background retry instead of crashing the app —
local dev works before `docker compose up redis`).

## See also

- [messaging.md](messaging.md) — RabbitMQ topology feeding the notification pipeline
- [../errors/error-handling.md](../errors/error-handling.md) — error envelope + SSE status-only error semantics
- [../post/realtime.md](../post/realtime.md) — post-domain realtime details (event payload fields, client recipes)

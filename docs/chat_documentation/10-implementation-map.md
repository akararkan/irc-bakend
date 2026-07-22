# 10 — Implementation Map (the code, class by class)

Files 01–08 are the *design*; [09-api-reference.md](09-api-reference.md) is the
*wire contract*. This file and the deep-dives 11–16 document the **shipped
backend** under `ak.dev.irc.app.chat` — what every class is, how the layers fit,
and where to read next. Everything here reflects the real code (92 Java files),
not the sketch.

> **Deep dives:** [11-send-path](11-send-path.md) ·
> [12-read-path](12-read-path.md) · [13-realtime-internals](13-realtime-internals.md) ·
> [14-permissions-internals](14-permissions-internals.md) ·
> [15-data-model-internals](15-data-model-internals.md) ·
> [16-groups-search-notifications](16-groups-search-notifications.md)

---

## 1. The layered architecture

Chat is a classic layered Spring module. A request flows **top to bottom**; a
realtime event flows **bottom to top** back out over SSE.

```
 HTTP  ┌─────────────────────────────────────────────────────────────────────┐
 in →  │ controller/   ConversationController · MessageController              │
       │               GroupMemberController · MessageRequestController        │
       │               MessagingStreamController (SSE + typing + presence)     │
       ├─────────────────────────────────────────────────────────────────────┤
       │ service/      MessageService · MessageQueryService · ConversationSvc  │
       │  (business)   GroupMemberService · MessageRequestService · Reaction   │
       │               Presence · Typing · SystemMessage · ChatNotification    │
       │               ChatIdempotency · UnreadBadgeCache · ChatConversationFy │
       │ permission/   ChatPermissionEngine · ChatRelationshipService · GroupPermissions
       │ mapper/       ChatMapper (entity → response DTO)                       │
       ├─────────────────────────────────────────────────────────────────────┤
       │ repository/            (Postgres/JPA)   cassandra/repository (CQL)     │
       │ entity/  (JPA)         cassandra/entity (@Table + media_ref UDT)       │
       │ search/  (Elasticsearch document + repository + service)              │
       │ realtime/ (SSE emitters + Redis pub/sub publisher/subscriber/broadcaster)
       └─────────────────────────────────────────────────────────────────────┘
          Cassandra        PostgreSQL       Redis            Elasticsearch
          (message log)    (relational)     (ephemeral +     (search index)
                                             pub/sub)
```

**One rule to protect in every change:** no single-conversation global lock, and
no cross-partition Cassandra read on the hot path. That is the property that
keeps chat fast; see [12-read-path](12-read-path.md) and [15](15-data-model-internals.md).

---

## 2. Package & class catalogue

### `chat.util` — primitives
| Class | Role |
|-------|------|
| `SnowflakeIdGenerator` | 64-bit time-sortable message ids (41b ms + 10b node + 12b seq); `nextId()`, static `timestampOf(id)`. |
| `ChatBuckets` | `bucketOf(messageId)` = which Cassandra partition window a message lives in (`BUCKET_DAYS=10`). Writer and reader agree with no stored coupling. |
| `DirectKeys` | `of(a,b)` = `min:max` UUID string — the deterministic key that makes get-or-create-DM race-safe. |

### `chat.enums`
`ConversationType` (DIRECT/GROUP) · `MemberRole` (OWNER/ADMIN/MEMBER) ·
`MemberStatus` (ACTIVE/RESTRICTED/LEFT/REMOVED) · `MessageType`
(TEXT/IMAGE/VIDEO/VOICE/FILE/SYSTEM) · `SystemEventType` (GROUP_CREATED, …,
PINNED, OWNERSHIP_TRANSFERRED) · `MessageRequestStatus`
(PENDING/ACCEPTED/DECLINED/BLOCKED) · `MemberScope` (ALL_MEMBERS/ADMINS_ONLY) ·
`SendDecision` (ALLOW/ROUTE_TO_REQUEST/DELIVER_RESTRICTED/DENY) · `GroupAction`
(the permission-matrix action set).

### `chat.cassandra` — the message log (see [15](15-data-model-internals.md))
| Class | Role |
|-------|------|
| `entity/MediaRef` | `@UserDefinedType("media_ref")` — the project's **first** Cassandra UDT; one attachment. |
| `entity/MessageByConversationEntity` | the log: partition `((conversation_id, bucket))`, clustering `message_id DESC`. |
| `entity/MessageByIdEntity` | lookup by id alone (replies/forwards/edit/delete) — note `@Column("message_id")` is mandatory. |
| `entity/ReactionByMessageEntity` | one row per (message, user). |
| `repository/*` | `CassandraRepository` + raw `@Query` CQL (firstPage/pageBefore/pageAfter, editBody/tombstone). |
| `ChatCassandraSchemaInitializer` | `@PostConstruct` — creates the UDT then the tables idempotently, guaranteeing UDT-before-table order. |

### `chat.entity` + `chat.repository` — relational (see [15](15-data-model-internals.md))
| Entity | Table | Repository highlights |
|--------|-------|-----------------------|
| `Conversation` | `conversations` | `findByDirectKey`, `advanceLastMessage` (monotonic), `adjustMemberCount` (flush+clear), `softDelete`. |
| `ConversationMember` (+`ConversationMemberId`) | `conversation_members` | `findInbox` (pinned-first, excludes pending requests), `bumpUnreadForOthers`, `advanceOwnMarker`, `findReadableMemberIds`, `findMyConversationIds`. |
| `MessageRequest` | `message_requests` | `findByRecipientIdAndRequesterId`, `findInbox`. |
| `ConversationInvite` | `conversation_invites` | `findByTokenHash`, `consumeUse` (guarded atomic), `revokeAllForConversation`. |
| `ConversationPin` | `conversation_pins` | `findByConversationIdOrderByPinnedAtDesc`, `deletePin`. |

### `chat.permission` — authorization (see [14](14-permissions-internals.md))
| Class | Role |
|-------|------|
| `ChatPermissionEngine` | `authorizeDirectSend(sender,recipient)` → `SendDecision` (the DM truth table). |
| `ChatRelationshipService` | maps the social graph → chat states; `isConnected`, `isBlockedEitherWay`, `isRestrictedBy`, `hasAcceptedThread`, `suppressEphemeral`. |
| `GroupPermissions` | pure `can(actorRole, action, targetRole, settings)` — the whole group matrix in one function. |

### `chat.realtime` — delivery (see [13](13-realtime-internals.md))
| Class | Role |
|-------|------|
| `ChatSseService` | per-instance SSE emitter registry (per-user), heartbeat, multi-tab, LRU cap; refreshes presence. |
| `ChatRedisPublisher` | publishes `{event,data}` to `irc:chat:{userId}`. |
| `ChatRedisSubscriber` | consumes `irc:chat:*` on every instance → `ChatSseService.push`. |
| `ChatRealtimeBroadcaster` | fans an event to a recipient set **after commit**. |
| `ChatRealtimeEvent` / `ChatRealtimeEventType` | the single event shape + the `event:` names. |

### `chat.service` — business logic
| Class | Role | Deep dive |
|-------|------|-----------|
| `MessageService` | send / forward / edit / delete / react / delivered / pin | [11](11-send-path.md) |
| `MessageQueryService` | page (bucket walk) / sync / getOne / reactions / search / pinned | [12](12-read-path.md) |
| `ReactionService` | Cassandra rows + Redis count hash | [12](12-read-path.md) |
| `ConversationService` | create / inbox / get / update / read / mute / pin / archive / delete | [16](16-groups-search-notifications.md) |
| `ChatConversationFactory` | the transactional DIRECT insert (own bean, for race-catch) | [15](15-data-model-internals.md) |
| `GroupMemberService` | add / remove / role / restrict / leave / transfer / invites / join | [16](16-groups-search-notifications.md) |
| `MessageRequestService` | requests inbox — accept / decline / block | [14](14-permissions-internals.md) |
| `SystemMessageService` | write SYSTEM timeline messages | [16](16-groups-search-notifications.md) |
| `ChatNotificationService` | bridge to the platform notification pipeline | [16](16-groups-search-notifications.md) |
| `ChatIdempotencyService` | nonce claim / release / echo (exactly-once send) | [11](11-send-path.md) |
| `PresenceService` | Redis presence + last-seen, block-filtered | [13](13-realtime-internals.md) |
| `TypingService` | Redis TTL typing + suppression | [13](13-realtime-internals.md) |
| `UnreadBadgeCache` | total-unread badge, after-commit invalidation | [16](16-groups-search-notifications.md) |

### `chat.search` — Elasticsearch (see [16](16-groups-search-notifications.md))
`document/ChatMessageDocument` (`irc-chat-messages`) · `repository/ChatMessageSearchRepository` ·
`service/ChatSearchService` (async index/delete + membership-scoped query).

### `chat.controller`
`ConversationController` (`/conversations`) · `MessageController` (`/conversations/{id}/messages`, `/messages/{id}`) ·
`GroupMemberController` (`/conversations/{id}/members`, invites, join) ·
`MessageRequestController` (`/message-requests`) ·
`MessagingStreamController` (`/messaging/stream`, `/conversations/{id}/typing`, `/presence`, `/messaging/search`).

### Touch-points in existing code (not new files)
`config/RedisMessagingConfig` (registers `ChatRedisSubscriber`) ·
`common/notification/NotificationKind` + `user/enums/NotificationType` +
`user/enums/NotificationCategory` (three chat kinds + a `CHAT` tab) ·
`email/EmailTemplate` + `email/NotificationEmailDispatcher` (exhaustive-switch
arms) · `common/search/ElasticsearchIndexInitializer` (registers the chat index).

---

## 3. What lives where (polyglot persistence)

| Question | Store | Class(es) |
|----------|-------|-----------|
| Last 50 messages / older page | **Cassandra** `messages_by_conversation` (1 partition/query) | `MessageQueryService`, `MessageByConversationRepository` |
| One message (reply/jump/edit/delete) | **Cassandra** `message_by_id` | `MessageByIdRepository` |
| Who reacted | **Cassandra** `reactions_by_message` + **Redis** counts | `ReactionService` |
| My conversations, newest first | **PostgreSQL** join | `ConversationMemberRepository.findInbox` |
| Roles / membership / read state | **PostgreSQL** `conversation_members` | — |
| Pending requests | **PostgreSQL** `message_requests` | `MessageRequestService` |
| Presence / typing / unread badge / idempotency nonce | **Redis** (TTL) | `PresenceService`, `TypingService`, `UnreadBadgeCache`, `ChatIdempotencyService` |
| Cross-instance realtime fan-out | **Redis pub/sub** (`irc:chat:{userId}`) | `ChatRedisPublisher/Subscriber` |
| Full-text search | **Elasticsearch** `irc-chat-messages` | `ChatSearchService` |
| Media bytes | **R2/S3** via the existing proxy | referenced by `storageKey` only |

---

## 4. The bean dependency graph (the important edges)

```
MessageService ─► SnowflakeIdGenerator, ChatIdempotencyService,
                  ChatPermissionEngine ─► ChatRelationshipService ─► SocialGuard,
                  {Message,MessageById}Repository (Cassandra),
                  Conversation/Member/Request/PinRepository (JPA),
                  ReactionService, ChatRealtimeBroadcaster ─► ChatRedisPublisher,
                  ChatNotificationService ─► CassandraNotificationService (reuse),
                  PresenceService, UnreadBadgeCache, ChatMapper, SystemMessageService,
                  ChatSearchService, S3StorageService (reuse), RateLimiter (reuse)

ChatSseService ─► PresenceService              (heartbeat refreshes presence)
ChatRedisSubscriber ─► ChatSseService          (registered in RedisMessagingConfig)
GroupMemberService ─► ConversationService      (join returns get(); one-way, no cycle)
```

There are **no dependency cycles** — Spring starts the context cleanly. The one
place to be careful is `@Transactional` self-invocation: the controller calls
`ConversationService.createGroup` / `createDirect` **directly** (not through a
same-bean dispatcher) so the group-create transaction actually engages — see
[15-data-model-internals](15-data-model-internals.md).

---

## 5. Request lifecycle at a glance (send a message)

1. `MessageController.send` → `MessageService.send(conversationId, userId, req)`
   (`@PreAuthorize("isAuthenticated()")`, `@Valid` body).
2. Rate-limit → load conversation + my member → **mint Snowflake** → **claim nonce**
   (retry returns the existing message).
3. **Permission** (`ChatPermissionEngine` for DIRECT, `authorizeGroupSend` for GROUP).
4. Write **both** Cassandra rows → `advanceLastMessage` → advance sender's own marker
   → async ES index.
5. **Unread fan-out** (small groups, `ALLOW` only) → **realtime broadcast** (after commit)
   → **offline notifications**.
6. Return `201 MessageResponse`; recipients receive `message.new` over their SSE stream.

The full numbered walkthrough with code is [11-send-path](11-send-path.md).

---

## 6. Configuration & operations

| Concern | Setting / mechanism |
|---------|---------------------|
| Snowflake node id | `app.node-id` (env `APP_NODE_ID`); if unset, derived from hostname hash (0–1023). **Set it explicitly per instance in production.** |
| Cassandra schema | auto: `spring.cassandra.schema-action=create_if_not_exists` **+** `ChatCassandraSchemaInitializer` (UDT-before-table). Keyspace `irc_keyspace`. |
| Postgres schema | auto: `spring.jpa.hibernate.ddl-auto=update`. Manual DDL: `db/chat_schema.sql` (idempotent; new tables only — no ALTERs to existing tables). |
| Realtime | Redis pub/sub (no new bean — `ChatRedisSubscriber` added to the shared `RedisMessageListenerContainer`). |
| Search | ES index `irc-chat-messages`, created lazily / by `ElasticsearchIndexInitializer`; degrade-safe (falls back to a bounded Cassandra scan for in-conversation search). |
| Security | secure-by-default `@PreAuthorize`; the SSE stream self-authenticates the `?token=` query param. |
| Rate limit | `RateLimiter.check("chat-send", …, 30, 10s)` on the send path. |

---

## 7. Reading guide

- **"How does a send actually work?"** → [11-send-path](11-send-path.md)
- **"How is pagination O(1)-per-page?"** → [12-read-path](12-read-path.md)
- **"How do messages reach the other screen?"** → [13-realtime-internals](13-realtime-internals.md)
- **"Who can message/kick/pin whom, and why?"** → [14-permissions-internals](14-permissions-internals.md)
- **"What are the tables and why?"** → [15-data-model-internals](15-data-model-internals.md)
- **"Groups, invites, search, notifications?"** → [16-groups-search-notifications](16-groups-search-notifications.md)
- **"What's the HTTP contract?"** → [09-api-reference](09-api-reference.md)

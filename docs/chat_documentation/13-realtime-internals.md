# 13 — Realtime Internals

This is the as-built, line-level companion to [05-realtime-delivery.md](05-realtime-delivery.md).
Where 05 sketches the delivery *design*, this document describes exactly what the
shipped code in `ak.dev.irc.app.chat.realtime` (plus `PresenceService` and
`TypingService`) actually does — every timeout constant, every concurrency
guard, every event field — and *why* each decision was made and what it costs.
It is written for an engineer who has to debug a stuck stream, add a new event
type, or reason about a race at 3am.

The whole subsystem is deliberately small: **one SSE stream per user**, a
**single event class** multiplexed by an `eventType` tag, and a **Redis pub/sub
bridge** so the instance that *produces* an event and the instance that *holds*
the recipient's stream never have to be the same box. Everything else is
lifecycle bookkeeping.

## 0. The moving parts

| Class | File | Role |
|-------|------|------|
| `MessagingStreamController` | `chat/controller/MessagingStreamController.java` | HTTP surface: `GET /messaging/stream`, `POST /conversations/{id}/typing`, `GET /presence`, `GET /messaging/unread-count` |
| `ChatSseService` | `chat/realtime/ChatSseService.java` | In-memory registry of live emitters **on this instance**; subscribe / push / heartbeat |
| `ChatRealtimeBroadcaster` | `chat/realtime/ChatRealtimeBroadcaster.java` | Fans an event to a recipient set, **after DB commit** |
| `ChatRedisPublisher` | `chat/realtime/ChatRedisPublisher.java` | `convertAndSend` to `irc:chat:{userId}` with an `{event,data}` envelope |
| `ChatRedisSubscriber` | `chat/realtime/ChatRedisSubscriber.java` | `MessageListener` on `irc:chat:*`; forwards to the local `ChatSseService.push` |
| `RedisMessagingConfig` | `config/RedisMessagingConfig.java` | Registers the subscriber on the shared `RedisMessageListenerContainer` |
| `ChatRealtimeEvent` | `chat/realtime/ChatRealtimeEvent.java` | The **single** all-nullable event DTO |
| `ChatRealtimeEventType` | `chat/realtime/ChatRealtimeEventType.java` | Enum → SSE `event:` wire name |
| `PresenceService` | `chat/service/PresenceService.java` | Redis heartbeat + TTL presence, block-filtered batch lookup |
| `TypingService` | `chat/service/TypingService.java` | Redis TTL typing signal + ephemeral suppression |

The dependency arrows only ever point one way:

```
producer service (MessageService, ConversationService, TypingService, …)
        │  builds a ChatRealtimeEvent, picks a recipient set
        ▼
ChatRealtimeBroadcaster ──(after commit)──► ChatRedisPublisher
        │  convertAndSend("irc:chat:{recipientId}", {event,data})
        ▼
             ────────  Redis pub/sub  ────────
        ▼   (fans to EVERY instance that subscribed irc:chat:*)
ChatRedisSubscriber.onMessage  (runs on every instance)
        │  push(userId, eventName, data)
        ▼
ChatSseService  ──► writes to the userId's local emitter(s), if any
        ▼
EventSource on the client
```

Cross-instance delivery is **Redis pub/sub only** for the live SSE path — there
is no RabbitMQ hop and no server-side replay buffer in this code. (RabbitMQ in
the platform carries the *offline bell notification* pipeline, which the send
path hands off to separately; see [05-realtime-delivery.md](05-realtime-delivery.md)
and the send-path details in [11-send-path.md](11-send-path.md).)

---

## 1. The single per-user SSE stream

### 1.1 What it is

Every logged-in client opens **one** long-lived HTTP request and keeps it open
for the whole session:

```
GET /api/v1/messaging/stream?token=<jwt>
Accept: text/event-stream
```

Every realtime signal for that user — new messages in *any* conversation, edits,
deletes, reactions, receipts, typing, group membership changes, request arrivals —
is multiplexed down this one stream, tagged by the SSE `event:` name and carrying
a `conversationId` so the client can fan them out locally. There is no per-room
or per-conversation stream. This mirrors the platform's existing eight SSE
streams (posts, stories, notifications, …); chat is one more topic on the same
rails.

`MessagingStreamController.stream(...)`:

```java
@GetMapping(value = "/messaging/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
@PreAuthorize("permitAll()")
public SseEmitter stream(@RequestParam(value = "token", required = false) String token,
                         HttpServletResponse response) {
    UUID userId = SecurityUtils.getCurrentUserId().orElse(null);
    if (userId == null && StringUtils.hasText(token)) {
        try {
            if (jwtTokenProvider.validateToken(token)
                    && "ACCESS".equals(jwtTokenProvider.getTokenType(token))) {
                userId = jwtTokenProvider.getUserIdFromToken(token);
            }
        } catch (Exception ex) {
            log.warn("[CHAT-SSE] invalid token via query param: {}", ex.getMessage());
        }
    }
    ...
```

### 1.2 Token-in-query auth — why the query string

The browser `EventSource` API **cannot set request headers** — there is no way
to attach `Authorization: Bearer …` to an SSE connection. So the token rides in
the query string, and the controller resolves identity in two tiers:

1. **Header path (preferred):** if the normal filter chain already authenticated
   the request, `SecurityUtils.getCurrentUserId()` returns the id and the token
   param is ignored. This covers non-browser clients that *can* send the header.
2. **Query-param path (browsers):** if there is no security context, and a
   `token` param is present, the controller validates it *itself* via
   `jwtTokenProvider.validateToken(token)` **and** asserts
   `"ACCESS".equals(jwtTokenProvider.getTokenType(token))` — a refresh token or
   any other token type is rejected. Only then does it derive `userId`.

Because auth is resolved *inside the method*, the endpoint is annotated
`@PreAuthorize("permitAll()")`, which **overrides** the class-level
`@PreAuthorize("isAuthenticated()")` on `MessagingStreamController`. Without that
override the security layer would 401 the header-less EventSource request before
the method ever ran, and the manual token path could never fire. This is the
project's standard SSE auth shape and is consistent with the "security enforced
by default, with explicit opt-outs on the streaming endpoints" posture.

> **Why a query param is acceptable here.** Access tokens are short-lived, the
> stream is `Cache-Control: no-store` (so the URL is not cached), and the token
> only grants a read-only event stream scoped to the caller's own userId. The
> alternative — a cookie — would reintroduce CSRF surface and sticky-session
> assumptions the Redis bridge is specifically designed to avoid.

### 1.3 The 401-as-text / `return null` rule — why not throw

When no identity can be resolved:

```java
if (userId == null) {
    try {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.TEXT_PLAIN_VALUE);
        response.getWriter().write("Authentication required. Pass access token as ?token=<jwt>.");
        response.flushBuffer();
    } catch (Exception ignored) { /* connection closing anyway */ }
    return null;
}
```

The method writes a plain-text `401` **directly to the servlet response** and
returns `null` instead of throwing `UnauthorizedException`. Three reasons:

- **The response is `text/event-stream`.** If the method threw, Spring's
  exception resolver would try to render the standard JSON error envelope *into a
  stream the client is parsing as SSE frames*. Content negotiation on an
  already-committed `text/event-stream` producer is a landmine. Writing the body
  by hand and returning `null` tells Spring "the response is fully handled,
  render nothing," so the client sees a clean, self-describing `401`.
- **`EventSource` only exposes the HTTP status on the *initial* handshake**, via
  `onerror`. A deterministic `401` with a human-readable body is the most useful
  thing a client (or a curl-wielding engineer) can get; a serialized stack-trace
  envelope is not.
- **An unauthenticated connect is expected, not exceptional.** Tokens expire
  constantly; a browser reconnecting with a stale token is routine. Throwing
  would log it as an error and pollute error metrics. Returning `null` treats it
  as the ordinary control-flow outcome it is.

The `try/catch(Exception ignored)` around the write matters too: if the client
already hung up, `getWriter().write` / `flushBuffer` can throw `IOException`, and
there is nothing useful to do — the connection is closing anyway.

### 1.4 The four anti-buffering headers

On the success path, before handing back the emitter:

```java
response.setHeader("X-Accel-Buffering", "no");
response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
response.setHeader("Pragma", "no-cache");
response.setHeader("Connection", "keep-alive");
```

| Header | Purpose |
|--------|---------|
| `X-Accel-Buffering: no` | Tells nginx (and compatible reverse proxies) **not** to buffer the response body. Without it, nginx accumulates the stream and flushes in chunks — events arrive in bursts seconds late, or never until the buffer fills. This is the single most common cause of "SSE works locally but not in prod." |
| `Cache-Control: no-cache, no-store, must-revalidate` | Prevents any cache (browser, CDN, proxy) from storing or replaying the stream. An SSE stream is a live, per-user, infinitely-long body; caching it is nonsensical and dangerous (leaks one user's events to another). |
| `Pragma: no-cache` | HTTP/1.0 belt-and-braces for the same intent, for older intermediaries that ignore `Cache-Control`. |
| `Connection: keep-alive` | Signals the intent to hold the TCP connection open for the duration. |

These four are the minimum set proven out on the platform's other SSE streams;
chat reuses them verbatim.

---

## 2. Emitter lifecycle in `ChatSseService`

`ChatSseService` is the **only** stateful part of an otherwise-stateless app
instance: it holds the live emitters *physically connected to this box*. It
never talks to Redis to deliver (that is the subscriber's job) — it only ever
writes to emitters it holds locally.

### 2.1 The registry

```java
private static final long SSE_TIMEOUT_MS = 24L * 60L * 60L * 1000L; // 24h
private static final long HEARTBEAT_MS   = 15_000L;
private static final int  MAX_EMITTERS_PER_USER = 5;

private final Map<UUID, CopyOnWriteArrayList<Subscription>> emittersByUser = new ConcurrentHashMap<>();
private final AtomicLong seq = new AtomicLong();
```

- `emittersByUser` — a `ConcurrentHashMap<UUID, CopyOnWriteArrayList<Subscription>>`.
  One user maps to a **list** of subscriptions because a user can have several
  tabs/devices open at once, each with its own emitter. A push fans out to all of
  them.
- `Subscription` is a `record(long id, SseEmitter emitter)` whose `equals`/
  `hashCode` are defined **on `id` only** (a monotonic `seq`), so two subscriptions
  are never accidentally "equal" because their emitters compare equal, and
  `List.remove(sub)` removes exactly the intended one.

**Why `CopyOnWriteArrayList`.** The list is read (iterated during `push`/
`heartbeat`) far more often than it is written (a tab opens/closes). COW gives
lock-free, snapshot iteration: a concurrent `add`/`remove` never throws
`ConcurrentModificationException` mid-broadcast, and a push iterating the list
sees a stable snapshot. The write cost (array copy on every add/remove) is
irrelevant at "tabs per user" cardinality (≤5).

### 2.2 `subscribe(UUID userId)` — the handshake

```java
public SseEmitter subscribe(UUID userId) {
    presenceService.markOnline(userId);
    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
    Subscription sub = new Subscription(seq.incrementAndGet(), emitter);

    CopyOnWriteArrayList<Subscription> bucket =
            emittersByUser.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());

    // Evict oldest tabs beyond the cap (LRU) so one account can't exhaust memory.
    while (bucket.size() >= MAX_EMITTERS_PER_USER) {
        Subscription oldest = bucket.get(0);
        try { oldest.emitter().complete(); } catch (Exception ignore) { /* best-effort */ }
        bucket.remove(oldest);
    }
    bucket.add(sub);

    Runnable cleanup = () -> {
        CopyOnWriteArrayList<Subscription> b = emittersByUser.get(userId);
        if (b != null) {
            b.remove(sub);
            if (b.isEmpty()) emittersByUser.remove(userId, b);
        }
    };
    emitter.onCompletion(cleanup);
    emitter.onTimeout(cleanup);
    emitter.onError(ex -> cleanup.run());

    try {
        emitter.send(SseEmitter.event().reconnectTime(3000L));
        emitter.send(SseEmitter.event().comment(" ".repeat(2048)));
        emitter.send(SseEmitter.event()
                .name("connected")
                .data(Map.of(
                        "userId", userId.toString(),
                        "timestamp", LocalDateTime.now().toString(),
                        "tabs", bucket.size(),
                        "message", "Chat stream active")));
        ...
    } catch (IOException ex) {
        cleanup.run();
        ...
    }
    return emitter;
}
```

Step by step:

1. **`markOnline` immediately.** Presence flips to online the instant the stream
   opens, before the handshake even completes (see §7).
2. **Finite 24h timeout — never `0`.** `new SseEmitter(SSE_TIMEOUT_MS)` uses
   24 hours, *not* `new SseEmitter(0L)` (which Spring treats as "no timeout,
   ever"). A finite timeout is a **safety valve against leaked emitters**: if a
   client's TCP connection half-opens (dead network, killed process) and neither
   `onError` nor `onCompletion` ever fires, the servlet container still reclaims
   the async request when the 24h timeout elapses, running `cleanup`. Twenty-four
   hours is far longer than any real session with a working 15s heartbeat will
   reach — so a healthy user never notices — but short enough that a zombie
   connection cannot live forever. When a healthy client *does* hit it, the `3000ms`
   `reconnectTime` makes `EventSource` auto-reconnect and re-subscribe.
3. **LRU cap at 5 emitters/user.** Before adding, `while (bucket.size() >= 5)`
   completes and removes the **oldest** subscription (`bucket.get(0)`). This
   bounds memory per account: a buggy client or malicious actor cannot open
   10,000 tabs and pin 10,000 emitters + heartbeat writes. The oldest tab loses
   its stream (and auto-reconnects if still alive), which is the correct victim —
   the freshest tab is the one the user is actually looking at.
4. **Cleanup callbacks.** `onCompletion`, `onTimeout`, and `onError` all run the
   same `cleanup`: remove *this* subscription from the bucket, and if the bucket
   is now empty, remove the user's entry entirely. This is how presence
   eventually TTL-expires (no more heartbeat refreshes for a user with no tabs).
5. **The three handshake frames** are sent in order:
   - `reconnectTime(3000L)` — sets the browser's auto-reconnect backoff to 3s.
   - `comment(" ".repeat(2048))` — a **2 KB comment line** (`: ` + 2048 spaces).
     SSE comment frames are ignored by the client but count as bytes on the wire.
     Many proxies/browsers won't surface the stream to the application until some
     threshold of bytes has flushed; padding with 2 KB forces that initial flush
     immediately, so the client's `onopen` fires without waiting for the first
     real event. Combined with `X-Accel-Buffering: no` this defeats both proxy
     and browser buffering.
   - the `connected` event — a small JSON payload (`userId`, `timestamp`, current
     `tabs` count, a message) that the client can use to confirm the stream is
     live and know how many of its own tabs are connected.
6. **Handshake failure is self-healing.** If any of those sends throws
   `IOException` (client hung up during the handshake), `cleanup.run()` removes
   the just-added subscription so it never lingers.

### 2.3 `push(...)` — writing to local tabs

```java
public void push(UUID userId, String eventName, Object payload) {
    CopyOnWriteArrayList<Subscription> bucket = emittersByUser.get(userId);
    if (bucket == null || bucket.isEmpty()) return;
    for (Subscription sub : bucket) {
        try {
            sub.emitter.send(SseEmitter.event().name(eventName).data(payload));
        } catch (IOException | IllegalStateException ex) {
            bucket.remove(sub);
            if (bucket.isEmpty()) emittersByUser.remove(userId, bucket);
        }
    }
}
```

- If this instance holds **no** emitter for `userId`, `push` returns instantly —
  the event is for a user connected on some *other* instance (or offline). This
  is the common case in a multi-instance deployment: every instance receives
  every `irc:chat:*` message (§3) and silently drops the ones it can't deliver.
- For each held emitter it writes an SSE frame `event: <name>\n data: <json>`.
- **Stale-emitter pruning on write:** a send that throws `IOException` (socket
  closed) or `IllegalStateException` (emitter already completed) means that tab
  is dead; it is removed inline. So dead emitters are reaped both proactively
  (callbacks/heartbeat) and lazily (next push).

### 2.4 The two-arg `map.remove` — a real concurrency guard

Notice every teardown uses the **two-argument** `emittersByUser.remove(userId, bucket)`,
never the one-arg `remove(userId)`. This is `ConcurrentMap.remove(key, value)`:
*remove the mapping only if it currently maps to exactly this bucket instance.*

The race it prevents: user's last tab on this instance closes (bucket empties) at
the same moment a brand-new tab calls `subscribe`. If teardown used the one-arg
`remove(userId)`, it could delete the entry that `computeIfAbsent` *just*
installed for the new tab — silently orphaning a live connection that would then
receive nothing. The compare-and-remove makes teardown a no-op whenever the
bucket has been replaced, so the new tab's bucket survives.

### 2.5 `heartbeat()` — one shared sweep, never a thread per connection

```java
@Scheduled(fixedDelay = HEARTBEAT_MS)
public void heartbeat() {
    if (emittersByUser.isEmpty()) return;
    String ts = LocalDateTime.now().toString();
    emittersByUser.forEach((userId, bucket) -> {
        // Refresh presence while any tab is open; on last close it TTL-expires.
        presenceService.markOnline(userId);
        for (Subscription sub : bucket) {
            try {
                sub.emitter.send(SseEmitter.event().name("heartbeat").data(Map.of("timestamp", ts)));
            } catch (IOException | IllegalStateException ex) {
                bucket.remove(sub);
                if (bucket.isEmpty()) emittersByUser.remove(userId, bucket);
            }
        }
    });
}
```

- **One `@Scheduled(fixedDelay = 15_000)` method** sweeps **all** users and
  **all** their emitters every 15 seconds on a single scheduler thread. This is
  the deliberate opposite of the naïve "spawn a heartbeat thread per connection"
  design, which would cost O(connections) threads — thousands of threads, each
  parked most of the time, blowing up memory and context-switching. The sweep is
  O(total emitters) work on **one** thread, 4× per minute.
- **Why heartbeat at all.** Two jobs: (a) push bytes through idle proxies so they
  don't idle-timeout the connection; (b) detect dead emitters — a heartbeat that
  throws prunes the corpse exactly like `push` does.
- **Presence refresh is folded into the sweep.** `markOnline(userId)` is called
  once per user per sweep (15s), which is why presence stays "online" for as long
  as *any* tab is connected, with **no client ping required** (see §7). The TTL is
  30s > the 15s sweep, so there is always a refresh before expiry.
- The `if (emittersByUser.isEmpty()) return` short-circuit means an idle instance
  with zero connections does no work and touches Redis zero times per tick.

### 2.6 Stats

```java
public boolean isConnected(UUID userId) { ... }   // does THIS instance hold a tab?
public long connectedUserCount() { return emittersByUser.size(); }
```

`isConnected` answers only *"is a tab held on this instance?"* — it is **not** a
global online check (that is `PresenceService.isOnline`, which reads Redis and is
correct across instances). The send path uses `PresenceService.isOnline`, not
this, to decide whether to fire an offline notification.

### 2.7 Complexity, concurrency, failure modes

| Concern | Detail |
|---------|--------|
| `subscribe` | O(1) amortised; the LRU `while` loop runs at most `MAX_EMITTERS_PER_USER` times. One Redis write (`markOnline`). |
| `push` | O(tabs) local socket writes, no DB, no Redis. Typically 1–2 tabs. |
| `heartbeat` | O(total emitters on this instance) socket writes + O(distinct users) Redis writes, every 15s, one thread. |
| Concurrency | `ConcurrentHashMap` + `CopyOnWriteArrayList` + `AtomicLong` seq + two-arg `remove`. No explicit locks; iteration never sees a partial mutation. |
| Failure: dead socket | Pruned on next `push`/`heartbeat`, or via `onError`/`onCompletion`/`onTimeout` — whichever fires first. |
| Failure: Redis down during `markOnline` | Swallowed inside `PresenceService` (logged at debug); the SSE stream still works, presence just goes stale. |
| Failure: instance crash | All its emitters die with it; clients auto-reconnect (3s) to another instance and re-subscribe. No shared state is lost because the registry is intentionally per-instance and rebuildable. |

---

## 3. Cross-instance fan-out over Redis pub/sub

The producer of an event and the holder of the recipient's stream are, in a
multi-instance deployment, almost never the same box. Redis pub/sub bridges them.

### 3.1 The channel and the envelope

`ChatRedisPublisher` publishes to a **per-recipient channel**:

```java
public static final String CHANNEL_PREFIX = "irc:chat:";

public void publish(UUID recipientId, ChatRealtimeEvent event) {
    if (recipientId == null || event == null || event.getEventType() == null) return;
    try {
        Map<String, Object> envelope = Map.of(
                "event", event.getEventType().wire(),
                "data", event);
        redisTemplate.convertAndSend(CHANNEL_PREFIX + recipientId,
                objectMapper.writeValueAsString(envelope));
    } catch (Exception ex) {
        // Never let a Redis failure break the calling thread.
        log.error("[CHAT-PUB] failed to publish {} for user={}: {}",
                event.getEventType(), recipientId, ex.getMessage());
    }
}
```

- **Channel = `irc:chat:{recipientId}`.** One channel per *recipient*, not per
  conversation. The unit of routing is "a user's stream," which is exactly what a
  subscriber can deliver to.
- **`{event, data}` envelope.** The message body is JSON:
  `{"event":"message.new","data":{…the ChatRealtimeEvent…}}`. The `event` key is
  the SSE wire name (`ChatRealtimeEventType.wire()`), so the *subscriber* picks
  the SSE `event:` name **without sniffing the payload** — it just reads
  `root.path("event")`. This is the identical envelope shape the notification and
  post realtime channels use, so one mental model covers all of them.
- **Never break the caller.** The whole body is wrapped in `try/catch` that logs
  and returns. A Redis outage must not propagate an exception back up into the
  broadcaster and, from there, into a request thread that has *already committed*
  its DB transaction. Realtime is best-effort; the durable state is already
  persisted.
- **Guard clause.** A null recipient, null event, or event with no `eventType`
  is dropped silently — a malformed publish can never poison the channel.

### 3.2 The subscriber

Every instance runs one `ChatRedisSubscriber` registered against the pattern
`irc:chat:*`, so it receives **every** chat event for **every** user, regardless
of which instance produced it:

```java
@Override
public void onMessage(@NonNull Message message, byte[] pattern) {
    String channel = new String(message.getChannel());
    String userIdStr = channel.replace(ChatRedisPublisher.CHANNEL_PREFIX, "");
    try {
        UUID userId = UUID.fromString(userIdStr);
        JsonNode root = objectMapper.readTree(message.getBody());
        String eventName = root.path("event").asText("message.new");
        JsonNode dataNode = root.has("data") ? root.path("data") : root;
        Object data = objectMapper.treeToValue(dataNode, Object.class);
        sseService.push(userId, eventName, data);
    } catch (IllegalArgumentException ex) {
        log.warn("[CHAT-SUB] bad userId in channel='{}': {}", channel, ex.getMessage());
    } catch (Exception ex) {
        log.error("[CHAT-SUB] failed to process chat event: {}", ex.getMessage());
    }
}
```

- It parses `userId` out of the channel name (`irc:chat:{userId}` → `{userId}`).
- It reads the `event` name from the envelope, defaulting to `"message.new"` if
  absent, and unwraps `data` (falling back to the whole root for envelope-less
  legacy messages).
- It calls `sseService.push(userId, eventName, data)`. On **this** instance that
  is a no-op if no tab for `userId` is held here (§2.3); on the instance that
  *does* hold the tab, it writes the frame. **Every instance runs this for every
  message; exactly the instances holding the recipient deliver it.** That is the
  entire cross-instance mechanism.
- Two catch tiers: an `IllegalArgumentException` (malformed userId in the channel)
  is a warn, everything else is an error — but neither propagates, so one bad
  message can't kill the listener.

### 3.3 Registration in `RedisMessagingConfig`

The subscriber is added to the shared `RedisMessageListenerContainer` alongside
the platform's other realtime subscribers:

```java
// Per-user chat channels — the single chat SSE stream's cross-instance
// bridge (new messages, edits, deletes, reactions, receipts, typing,
// presence, group events), keyed by recipient userId.
container.addMessageListener(chatSubscriber,
        new PatternTopic(ChatRedisPublisher.CHANNEL_PREFIX + "*"));
```

Two container details worth knowing when debugging:

- **Bounded dispatch executor.** `container.setTaskExecutor(redisListenerExecutor())`
  installs a `ThreadPoolTaskExecutor` (core 4, max 8, queue 1000) with a
  `CallerRunsPolicy`. The default `SimpleAsyncTaskExecutor` spawns an unbounded
  thread per dispatch burst; the bounded pool means a slow SSE consumer can only
  tie up a bounded number of workers while other channels keep flowing, and on
  overload the Redis dispatch thread **runs the task itself** — natural
  backpressure instead of dropped events.
- **Boot resilience.** The container is a `ResilientRedisMessageListenerContainer`
  that logs and retries every 5s if Redis is unreachable at boot, instead of
  crashing the app — so local dev works before `docker compose up redis`.

### 3.4 Complexity

| Step | Cost |
|------|------|
| `publish` | One `convertAndSend` (a single Redis `PUBLISH`), one JSON serialize. O(1). |
| Redis fan-out | Redis delivers the message to every instance subscribed to the matching pattern. O(instances). |
| `onMessage` per instance | One JSON parse + one `push`. `push` is O(local tabs), usually 0 (drop) or 1–2 (deliver). |

There are **zero DB round-trips** on the delivery path. The recipient *set* was
resolved once, in the producer service, before publishing (see §4.4).

---

## 4. `ChatRealtimeBroadcaster` — after-commit fan-out

The broadcaster sits between the producer services and the publisher. Its two
jobs: **defer publishing until the DB transaction commits**, and **iterate the
recipient set**.

### 4.1 Broadcast only after commit

```java
private void runAfterCommit(Runnable action) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { action.run(); } catch (Exception e) {
                    log.warn("[CHAT-BROADCAST] post-commit publish failed: {}", e.getMessage());
                }
            }
        });
    } else {
        try { action.run(); } catch (Exception e) {
            log.warn("[CHAT-BROADCAST] publish failed: {}", e.getMessage());
        }
    }
}
```

- If called **inside** a Spring transaction (the normal case — every producer
  method is `@Transactional`), the publish is registered as a
  `TransactionSynchronization` and runs **only in `afterCommit()`**. If the
  transaction rolls back, `afterCommit` never fires and **nothing is published.**
- If called **outside** a transaction, it runs immediately.

**Why this matters.** Without it, a client could receive a `message.new` event
for a row whose transaction subsequently *rolled back* — a message that does not
exist. Every state a recipient can observe over the stream is therefore a state
that is already durably committed. This is the same discipline the platform's
`PostRealtimeBroadcaster` enforces, and it is why the send path can publish
optimistically without worrying about phantom deliveries.

- The `afterCommit` body is itself wrapped in `try/catch`: a publish failure at
  this point (Redis hiccup) must not throw, because the transaction has *already
  committed* — there is nothing to undo, and throwing here would only surface a
  spurious error to a caller whose write already succeeded.

### 4.2 The three recipient-set methods

```java
public void broadcast(Collection<UUID> recipientIds, ChatRealtimeEvent event) { … }          // all
public void broadcastExcept(Collection<UUID> recipientIds, UUID excludeUserId, ChatRealtimeEvent event) { … }  // all but one
public void broadcastTo(UUID recipientId, ChatRealtimeEvent event) { … }                      // exactly one
```

- **`broadcast`** — publish to every id in the set. Used for events every member
  should see: `message.new`, `message.edited`, `message.deleted`,
  `message.reaction`, `conversation.updated`, `member.changed`.
- **`broadcastExcept`** — publish to everyone except the actor. Used for signals
  the actor shouldn't be echoed: **typing** (you don't tell someone they're
  typing), **`receipt.read`** and **`receipt.delivered`** (the reader/receiver
  doesn't need their own receipt).
- **`broadcastTo`** — publish to a single user. Used for targeted events:
  `request.new` (only the recipient of the request), a `member.changed` echo to
  the affected user, `conversation.updated`/`REQUEST_ACCEPTED` to the requester.

All three are null/empty-guarded and route through `runAfterCommit`.

### 4.3 Membership authorization lives with the caller

The broadcaster does **not** check room membership. As its own Javadoc states,
*"the recipient set is the resolved conversation participants; the SSE layer only
checks identity ('is this my userId'), never room membership."* Authorization
happened upstream (the send path already verified the sender may post; see
[03-permissions-and-requests.md](03-permissions-and-requests.md)), and the
recipient set is computed from the member table. A user can only ever receive
events published to *their own* `irc:chat:{userId}` channel, and the producer
only publishes to members it looked up. There is no way to subscribe to another
user's channel from the client — the client just holds *its* SSE stream.

### 4.4 The recipient-set queries

Producers pick one of two member queries depending on who should see the event:

```java
// Active member ids — the eager unread-fanout + realtime recipient set.
@Query("SELECT m.id.userId FROM ConversationMember m
        WHERE m.id.conversationId = :cid AND m.status = ACTIVE")
List<UUID> findActiveMemberIds(UUID conversationId);

// Members who can read (ACTIVE or RESTRICTED) — realtime recipients for edits/deletes.
@Query("SELECT m.id.userId FROM ConversationMember m
        WHERE m.id.conversationId = :cid AND (m.status = ACTIVE OR m.status = RESTRICTED)")
List<UUID> findReadableMemberIds(UUID conversationId);
```

- **`findReadableMemberIds` (ACTIVE ∪ RESTRICTED)** is used for
  content-visibility events — `message.new`, `message.edited`, `message.deleted`,
  `message.reaction`, pin/unpin. A **restricted** member can still *read* the
  thread (they just can't post), so they must keep receiving message updates.
- **`findActiveMemberIds` (ACTIVE only)** is used for interactive/presence-ish
  events — `receipt.read`, `receipt.delivered`, `typing`, `conversation.updated`
  (title/avatar), `member.changed`. A restricted member is deliberately excluded
  from typing/receipts (they shouldn't be fed the room's live social signals).

This is one indexed Postgres query per broadcast, on `(conversationId, status)`.
For large groups the send path caps eager unread fan-out (see the
`LARGE_GROUP_CUTOFF` branch in the send path, [11-send-path.md](11-send-path.md)),
but the realtime broadcast itself is a single query returning the member ids and
a `PUBLISH` per id.

---

## 5. The single event shape and the full catalogue

### 5.1 One class, all-nullable, dispatched on `eventType`

There is exactly **one** DTO on the wire, `ChatRealtimeEvent`, with all-nullable
fields. Clients dispatch on `eventType`/the SSE `event:` name and read only the
fields relevant to it. `@JsonInclude(NON_NULL)` means unused fields are omitted
from the JSON, so each event is compact despite the union-shaped class.

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatRealtimeEvent {
    private ChatRealtimeEventType eventType;
    private UUID conversationId;

    // message.new / (edited carries the full message too for convenience)
    private MessageResponse message;

    // message.edited / message.deleted / message.reaction / receipt.delivered
    private Long messageId;
    private String body;
    private Instant editedAt;

    // message.reaction
    private String emoji;
    private Boolean added;

    // receipt.read / typing / presence / member.changed — the subject user
    private UUID userId;
    private Long lastReadMessageId;
    private Boolean isTyping;
    private String presenceStatus;   // "online" | "offline"
    private Long lastSeenEpochMs;

    // conversation.updated
    private ConversationResponse conversation;

    // member.changed
    private String memberChange;     // ADDED | REMOVED | LEFT | PROMOTED | DEMOTED | RESTRICTED | UNRESTRICTED
    private String role;

    // request.new
    private MessageRequestResponse request;

    @Builder.Default
    private Instant timestamp = Instant.now();
}
```

**Why one all-nullable class instead of a sealed hierarchy or per-event DTOs.**
The platform already uses this convention for `PostRealtimeEvent` and
`StoryTrayEvent`. It keeps the publisher, subscriber, and client dispatcher
trivially uniform (one serializer, one envelope, one `switch` on the client), at
the cost of a slightly loose schema. Every builder call sets `eventType` +
`conversationId` + only the fields that event needs; `NON_NULL` drops the rest.
The `timestamp` defaults to `Instant.now()` at build time.

### 5.2 The enum → wire mapping

```java
MESSAGE_NEW("message.new"),          MESSAGE_EDITED("message.edited"),
MESSAGE_DELETED("message.deleted"),  MESSAGE_REACTION("message.reaction"),
RECEIPT_READ("receipt.read"),        RECEIPT_DELIVERED("receipt.delivered"),
TYPING("typing"),                    PRESENCE("presence"),
CONVERSATION_UPDATED("conversation.updated"),
MEMBER_CHANGED("member.changed"),    REQUEST_NEW("request.new");
```

The `wire()` value is the SSE `event:` name the browser subscribes to
(`eventSource.addEventListener("message.new", …)`). One stream carries all of a
user's conversations; the client fans them out locally by `conversationId`.

### 5.3 The full event catalogue — producer, recipients, and populated fields

Every row below is a real builder call site verified in the code.

| `event:` | Producer (method) | Broadcast method / recipient set | Populated fields (besides `eventType`, `timestamp`) |
|----------|-------------------|----------------------------------|------------------------------------------------------|
| `message.new` | `MessageService.dispatch` | `broadcast` — see routing note | `conversationId`, `message` (full `MessageResponse`) |
| `message.new` (system msg) | `SystemMessageService.write` | `broadcast(findReadableMemberIds)` | `conversationId`, `message` |
| `message.edited` | `MessageService.edit` | `broadcast(findReadableMemberIds)` | `conversationId`, `messageId`, `body`, `editedAt` |
| `message.deleted` | `MessageService.delete` | `broadcast(findReadableMemberIds)` | `conversationId`, `messageId` |
| `message.reaction` | `MessageService.broadcastReaction` (react/unreact) | `broadcast(findReadableMemberIds)` | `conversationId`, `messageId`, `userId`, `emoji`, `added` |
| `receipt.read` | `ConversationService.markRead` | `broadcastExcept(findActiveMemberIds, reader)` | `conversationId`, `userId` (reader), `lastReadMessageId` |
| `receipt.delivered` | `MessageService.markDelivered` | `broadcastExcept(findActiveMemberIds, receiver)` | `conversationId`, `messageId`, `userId` (receiver) |
| `typing` | `TypingService.handleTyping` | `broadcastExcept(findActiveMemberIds, sender)` | `conversationId`, `userId` (sender), `isTyping` |
| `conversation.updated` (info) | `ConversationService.update` | `broadcast(findActiveMemberIds)` | `conversationId`, `conversation` (full `ConversationResponse`) |
| `conversation.updated` (group deleted) | `ConversationService.delete` | `broadcast(findActiveMemberIds)` | `conversationId`, `memberChange="DELETED"` |
| `conversation.updated` (pin) | `MessageService.pinMessage` | `broadcast(findReadableMemberIds)` | `conversationId`, `messageId`, `memberChange="PINNED"` |
| `conversation.updated` (unpin) | `MessageService.unpinMessage` | `broadcast(findReadableMemberIds)` | `conversationId`, `messageId`, `memberChange="UNPINNED"` |
| `conversation.updated` (request accepted) | `MessageRequestService.accept` | `broadcastTo(requester)` | `conversationId`, `memberChange="REQUEST_ACCEPTED"` |
| `member.changed` | `GroupMemberService.emitMemberChange` (via add/remove/role/restrict/leave/transfer); `ConversationService.createGroup` | `emitMemberChange`: `broadcast(findActiveMemberIds)` **and** `broadcastTo(affectedUser)`. `createGroup`: `broadcastTo(addedUser)` only — existing members learn of the group via the `GROUP_CREATED` `message.new` | `conversationId`, `userId`, `memberChange` (ADDED/REMOVED/LEFT/PROMOTED/DEMOTED/RESTRICTED/UNRESTRICTED), `role` |
| `request.new` | `MessageService.dispatch` (ROUTE_TO_REQUEST, first message) | `broadcastTo(peer)` | `conversationId`, `request` (`MessageRequestResponse`) |

**Two things the table makes explicit:**

1. **`conversation.updated` is overloaded** — it is the catch-all for
   conversation-level changes, and its **`memberChange`** field is reused as a
   *sub-type discriminator* (`PINNED`, `UNPINNED`, `DELETED`,
   `REQUEST_ACCEPTED`) when the change isn't a full info update. Only the
   info-update variant carries the full `conversation` object; the others carry
   just the discriminator (and `messageId` for pin/unpin). Clients switch on
   `memberChange` under this event.
2. **`member.changed` is double-delivered** — `emitMemberChange` broadcasts to
   the active group *and* `broadcastTo(userId)` the affected user, precisely so a
   user who was just **removed/left** (and is therefore no longer in the active
   set) still receives the event that tells their client to drop the conversation.

### 5.4 `message.new` routing note (the send path)

`MessageService.dispatch` chooses recipients by the send **decision** (full
detail in [11-send-path.md](11-send-path.md) and
[03-permissions-and-requests.md](03-permissions-and-requests.md)):

- `ROUTE_TO_REQUEST` → `broadcast(List.of(directPeer, senderId))` — the peer's
  *Requests* tray plus the sender's own other devices — and, if the request row
  was just created, an additional `request.new` `broadcastTo(directPeer)`.
- `DELIVER_RESTRICTED` → `broadcast(List.of(directPeer, senderId))` — the peer's
  restricted tray plus the sender's devices; the sender never learns they were
  restricted.
- otherwise (`ALLOW`) → `broadcast(recipients)` — all readable members,
  **including the sender's own other devices** so multi-device stays in sync
  (clients dedupe by `messageId`, see §9).

### 5.5 `PRESENCE` — defined but not currently pushed

The enum value `PRESENCE("presence")` and the `presenceStatus` / `lastSeenEpochMs`
fields exist on `ChatRealtimeEvent`, **but no producer currently builds a
`PRESENCE` event** — a grep for `ChatRealtimeEventType.PRESENCE` finds no
`builder()` call. Presence is delivered by the **pull** endpoint
`GET /presence` (§7) plus heartbeat-refresh + TTL, not by a pushed event. The
fields are reserved for a future presence-push (a client would then also
receive live online/offline transitions over the stream) without changing the
event shape. This is an accepted, deliberate gap, not an omission: the current
model has clients query presence when they render a conversation list, which is
sufficient and avoids fanning presence changes to every contact.

---

## 6. Delta-not-counts model

`ChatRealtimeEvent` **omits counter values on purpose.** There is no
`unreadCount`, no `reactionCount`, no `totalMembers` on the reaction/receipt/
message events. From the class Javadoc:

> *Counter values are intentionally omitted: per the platform's delta-not-counts
> realtime model, the client applies +1/-1 to its local unread/reaction counters
> when it sees an event, avoiding stale re-reads.*

**What the client does.** On `message.new` in a conversation it isn't currently
viewing, the client increments its local unread badge by 1. On `message.reaction`
with `added=true`, it adds the emoji locally; with `added=false`, it removes it.
On `receipt.read` it advances the other party's read marker to
`lastReadMessageId`.

**Why deltas, not absolute counts.** Two reasons:

1. **No stale re-reads.** If the event carried an absolute count, that count was
   computed at publish time on the producing instance; by the time a slow or
   reordered delivery lands, the "authoritative" number may already be wrong, and
   the client would flip-flop between the delta it can compute locally and a stale
   server number. Local increment is monotonic and consistent with what the user
   is actually seeing.
2. **Cheaper everywhere.** The producer doesn't recompute a counter on the hot
   path just to attach it, and the payload is smaller. The one place an absolute
   count *is* needed — the initial badge on app open — is served by the pull
   endpoint `GET /messaging/unread-count` (`UnreadBadgeCache.total`), which the
   client calls once and then maintains by delta.

This is the same model as the platform's post realtime events (see the project
memory note *"Realtime events carry deltas not counts"*). The authoritative
counter of record still lives in Postgres (`conversation_members.unread_count`,
reset to 0 on `markRead`); the stream just keeps the client's *view* in sync
cheaply between reads.

---

## 7. Presence — heartbeat-refresh + TTL, no client ping

`PresenceService` is pure Redis: two keys per user, a 30s TTL, and a
server-driven refresh.

```java
private static final Duration PRESENCE_TTL = Duration.ofSeconds(30);
private static final String PRESENCE_PREFIX = "chat:presence:";
private static final String LASTSEEN_PREFIX = "chat:lastseen:";

public void markOnline(UUID userId) {
    try {
        redis.opsForValue().set(PRESENCE_PREFIX + userId, "online", PRESENCE_TTL);
        redis.opsForValue().set(LASTSEEN_PREFIX + userId, Long.toString(System.currentTimeMillis()));
    } catch (Exception e) { log.debug("[PRESENCE] set failed for {}: {}", userId, e.getMessage()); }
}
```

### 7.1 How online/offline is decided

- **Online** = the key `chat:presence:{userId}` exists (`isOnline` →
  `redis.hasKey`).
- `markOnline` is called from exactly two places, both in `ChatSseService`:
  `subscribe` (stream opens) and the 15s `heartbeat` sweep. There is **no client
  ping** — the server refreshes presence on the client's behalf as long as it is
  holding a live emitter. TTL 30s > sweep 15s guarantees a refresh always lands
  before expiry.
- **Offline** is implicit: when the last tab closes, `cleanup` removes the user
  from `emittersByUser`, so the next heartbeat sweep no longer refreshes them,
  and the key **TTL-expires within 30s**. `markOffline` exists but is **not wired
  into the SSE path** — the design relies entirely on expiry. This is the whole
  point: **no explicit offline signal is needed**, which is what makes it correct
  under crashes and network drops (a client that vanishes can't send a "goodbye").

### 7.2 Why `lastseen` is stamped on every refresh

`markOnline` writes `chat:lastseen:{userId}` = now on **every** call (with no
TTL). So when the presence key later expires, the recorded last-seen is
≈ the final heartbeat — no separate "record last-seen on disconnect" step is
needed, and it's correct even if the disconnect was ungraceful. `presenceOf`
reports `lastSeen` only when the user is offline:

```java
public PresenceResponse presenceOf(UUID userId) {
    boolean online = isOnline(userId);
    Long lastSeen = null;
    if (!online) {
        String v = redis.opsForValue().get(LASTSEEN_PREFIX + userId);
        lastSeen = v == null ? null : Long.parseLong(v);
    }
    return new PresenceResponse(userId, online ? "online" : "offline", lastSeen);
}
```

### 7.3 Multi-tab and multi-instance correctness

- **Multi-tab, same instance:** every tab's presence is the same single Redis
  key; any one tab keeps it alive.
- **Multi-instance:** if a user has tabs on instance A *and* B, **both**
  instances' heartbeat sweeps call `markOnline` independently, each writing the
  same key with a fresh 30s TTL. The user stays online as long as **any**
  instance holds a tab; the key only expires once *every* instance has dropped
  *every* tab. There is no coordination and no leader — the shared key + idempotent
  refresh is the coordination.

### 7.4 Block-filtered batch lookup

The controller's `GET /presence` takes a viewer and a set of user ids and never
leaks presence across a block:

```java
public List<PresenceResponse> presenceOf(Collection<UUID> userIds, UUID viewerId) {
    Set<UUID> blocked = viewerId == null ? Set.of()
            : new HashSet<>(socialGuard.findRelatedBlockedIds(viewerId));
    List<PresenceResponse> out = new ArrayList<>(userIds.size());
    for (UUID id : userIds) {
        if (blocked.contains(id)) out.add(new PresenceResponse(id, "offline", null));
        else out.add(presenceOf(id));
    }
    return out;
}
```

- **One** cached block lookup for the whole batch —
  `SocialGuard.findRelatedBlockedIds(viewerId)` is `@Cacheable`
  (`user-blocked-ids`) — rather than a per-id block query. Anyone in a block
  relationship with the viewer (either direction) is hard-coded to `offline` with
  **no** last-seen, so presence is never leaked across a block (consistent with
  [03-permissions-and-requests.md](03-permissions-and-requests.md)).
- Complexity: one cached set fetch + N Redis `hasKey`/`get` reads for a batch of
  N ids. A `viewerId` of `null` (anonymous) blocks nothing but sees only raw
  presence.
- Failure mode: every Redis op is individually `try/catch`'d to a safe default
  (`isOnline` → false, last-seen → null), so a Redis blip degrades to "everyone
  offline" rather than an error.

---

## 8. Typing — Redis TTL auto-clear + ephemeral suppression

`TypingService.handleTyping` is the smallest realtime path but shows the same
two ideas — TTL instead of explicit stop, and privacy suppression.

```java
private static final Duration TYPING_TTL = Duration.ofSeconds(6);

public void handleTyping(UUID conversationId, UUID senderId, boolean isTyping) {
    var member = memberRepo.findMember(conversationId, senderId)
            .filter(m -> m.getStatus() != null && m.isActive())
            .orElseThrow(() -> new ForbiddenException(
                    "You are not an active member of this conversation.", "NOT_A_MEMBER"));

    String key = "chat:typing:" + conversationId + ":" + senderId;
    if (isTyping) redis.opsForValue().set(key, "1", TYPING_TTL);
    else redis.delete(key);

    if (relationships.suppressEphemeral(conversationId, senderId)) return;

    List<UUID> recipients = memberRepo.findActiveMemberIds(conversationId);
    ChatRealtimeEvent event = ChatRealtimeEvent.builder()
            .eventType(ChatRealtimeEventType.TYPING)
            .conversationId(conversationId)
            .userId(senderId)
            .isTyping(isTyping)
            .build();
    broadcaster.broadcastExcept(recipients, senderId, event);
}
```

- **Active-member gate.** Only an ACTIVE member can signal typing — a restricted
  or removed member is rejected with `NOT_A_MEMBER`.
- **TTL auto-clear.** `chat:typing:{conv}:{sender}` is set with a **6s** TTL when
  `isTyping=true`. If the client simply *stops sending* typing pings (or crashes),
  the key expires on its own — **no explicit "stopped typing" signal is
  required**. A client that pings every few seconds keeps the "…is typing"
  indicator alive; the moment it stops, the indicator naturally clears. An
  explicit `isTyping=false` deletes the key immediately.
- **`suppressEphemeral` privacy gate.** Before broadcasting,
  `ChatRelationshipService.suppressEphemeral(conversationId, senderId)` returns
  `true` — and the method returns without broadcasting — when the conversation's
  message request is still **PENDING**, or (for a DIRECT thread) a **restrict**
  relationship exists in either direction. The other party must never learn the
  sender is present before the relationship is settled. Note the Redis key is
  still written even when the broadcast is suppressed; only the fan-out is
  skipped. The **same** `suppressEphemeral` guard gates `receipt.delivered`
  (`MessageService.markDelivered`) and `receipt.read`
  (`ConversationService.markRead`) — typing and receipts are the three "ephemeral
  presence-ish" signals held back on unsettled threads.
- **Recipients.** `broadcastExcept(findActiveMemberIds, senderId)` — everyone
  active except the sender. `receipt.*` events are ephemeral too but, unlike
  typing, they *are* meaningful to persist implicitly via the read marker; the
  typing key is the only thing that is truly throwaway.
- Complexity: one member lookup + one Redis set/delete + (if not suppressed) one
  `findActiveMemberIds` query + a `PUBLISH` per active recipient. The suppress
  check is a couple of indexed reads.

---

## 9. Delivery guarantees, and why SSE not WebSockets

### 9.1 At-least-once + client dedupe by `messageId`

The realtime path is **at-least-once, not exactly-once**, and the system is
explicitly designed around that:

- A user's **multiple tabs** each receive every event → the same `message.new`
  arrives more than once for the same person.
- The send path deliberately delivers a user's own message to that user's **other
  devices** (`ALLOW` → `broadcast(recipients)` includes the sender), so a
  sender's second tab gets the message it also created optimistically.
- A reconnect (24h timeout, network blip, instance failover) can re-deliver an
  event the client already applied.

The reconciliation rule is **client-side dedupe by `messageId`.** Message ids are
time-sortable Snowflakes (see [06-algorithms.md](06-algorithms.md)); a client
keys its message store by id, so applying the same `message.new` twice is
idempotent — the second is a no-op. The code comment says it directly:
*"Deliver to all members INCLUDING the sender's other devices (clients dedupe by
messageId), so multi-device stays in sync."*

**No server-side replay buffer.** This code has no `Last-Event-ID` replay store
(the 05 design sketches one; it is not implemented here). Events missed while a
client was disconnected are recovered by the client **refetching / gap-syncing**
the conversation on reconnect (walk the message partition from its last known id;
see [06-algorithms.md](06-algorithms.md)), not by the stream replaying them. The
stream is a *liveness accelerator* over an authoritative, re-readable log — not
the system of record. That is what lets the realtime layer be best-effort
(swallow Redis errors, drop on rollback) without ever losing a message.

The `reconnectTime(3000L)` handshake frame ensures a dropped `EventSource`
auto-reconnects in 3s and re-subscribes, at which point the client's gap-sync
closes any hole.

### 9.2 Why SSE, not WebSockets

Chat feels bidirectional, but the two directions have very different shapes, and
the codebase commits fully to **SSE down, HTTP POST up**:

- **Down (server → client):** high-volume, must be push. This is exactly what SSE
  is for — a durable one-way event stream over ordinary HTTP, with built-in
  auto-reconnect (`reconnectTime`) and no special handshake. It rides the same
  reverse proxy, JWT filter, and Redis bridge as the platform's eight other SSE
  streams. Crucially, **no sticky sessions are required**: because delivery goes
  through Redis pub/sub to *whichever* instance holds the stream, any instance can
  produce and any instance can deliver.
- **Up (client → server):** low-volume request/response — send a message, mark
  read, post a typing ping. Plain `POST`s are simpler, trivially idempotent (the
  send path carries a client nonce; see [11-send-path.md](11-send-path.md)), and
  need no duplex socket.

WebSockets would add a second transport, its own scaling/sticky-session concerns,
and no benefit for text/media/typing/receipts. They remain a *possible* future
lever only for something genuinely duplex and latency-critical (voice/video call
signalling), as noted in [08-scaling-and-roadmap.md](08-scaling-and-roadmap.md).
Everything chat needs today is covered by SSE down + POST up with **zero
WebSocket infrastructure**.

---

## Appendix — failure-mode cheat-sheet

| Failure | Behaviour | Where |
|---------|-----------|-------|
| Client sends no/expired token to `/messaging/stream` | Clean `401 text/plain`, `return null` — not an exception, not logged as error | `MessagingStreamController.stream` |
| Client TCP dies silently (half-open) | Emitter reclaimed at the 24h timeout, or pruned on the next `push`/`heartbeat` throw | `ChatSseService` |
| >5 tabs for one user | Oldest emitter `complete()`d + evicted (LRU) | `ChatSseService.subscribe` |
| Redis down at publish | Logged, swallowed; the request thread (already committed) is unaffected; event is lost (client gap-syncs on reconnect) | `ChatRedisPublisher.publish` |
| Redis down at boot | Container logs + retries every 5s; app still starts | `ResilientRedisMessageListenerContainer` |
| DB transaction rolls back after building an event | `afterCommit` never fires → nothing published | `ChatRealtimeBroadcaster.runAfterCommit` |
| Malformed message on `irc:chat:*` | Warn/error logged; listener keeps running | `ChatRedisSubscriber.onMessage` |
| Redis down at presence read | Degrades to "offline" / null last-seen, no error | `PresenceService` |
| Duplicate delivery (multi-tab / reconnect) | Client dedupes by `messageId` (Snowflake) — idempotent apply | client + send path |
| Presence after ungraceful disconnect | Key TTL-expires within 30s; `lastseen` ≈ final heartbeat | `PresenceService` + heartbeat sweep |

See also: [01-architecture.md](01-architecture.md) (component map + event
catalogue overview), [05-realtime-delivery.md](05-realtime-delivery.md) (the
delivery design at a glance), [09-api-reference.md](09-api-reference.md) (the
as-built endpoint + event payload contract), and [11-send-path.md](11-send-path.md)
(the produce side that feeds this stream).

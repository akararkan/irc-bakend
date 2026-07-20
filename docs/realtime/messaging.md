# RabbitMQ Messaging Topology (backend contributors)

How domain events travel from a committed transaction to the notification/analytics pipeline.
This is the **asynchronous work** side of realtime; browser delivery is SSE + Redis pub/sub — see
[overview.md](overview.md). Sources: `src/main/java/ak/dev/irc/app/rabbitmq/**` (constants in
`constants/RabbitMQConstants.java`, infrastructure in `config/RabbitMQConfig.java`).

```
irc.topic.exchange (topic)
  user.social.#        ──►  irc.queue.notifications
  research.lifecycle.# ──►  irc.queue.notifications
  research.social.#    ──►  irc.queue.notifications
  post.lifecycle.#     ──►  irc.queue.notifications
  post.social.#        ──►  irc.queue.notifications
  qna.lifecycle.#      ──►  irc.queue.notifications
  qna.social.#         ──►  irc.queue.notifications
  research.analytics.# ──►  irc.queue.analytics

irc.dlx.exchange (direct)
  dead-letter          ──►  irc.queue.dead-letter
```

## Exchanges

| Exchange | Type | Purpose |
|---|---|---|
| `irc.topic.exchange` | topic, durable | All IRC domain events flow through here. |
| `irc.dlx.exchange` | direct, durable | Dead-letter exchange — receives messages that exhaust their retries or exceed the queue TTL. |

## Queues

| Queue | Arguments | Consumer |
|---|---|---|
| `irc.queue.notifications` | durable; `x-dead-letter-exchange=irc.dlx.exchange`, `x-dead-letter-routing-key=dead-letter`, `x-message-ttl=86400000` (**24 h** max age) | `NotificationEventConsumer` — creates `Notification` records from social + lifecycle events (dispatched per event class via `@RabbitHandler`). |
| `irc.queue.analytics` | durable; same DLX wiring + **24 h** TTL | `ResearchAnalyticsConsumer` — persists download analytics. |
| `irc.queue.dead-letter` | durable, no TTL — parking lot until drained | DLQ listener in `RabbitMQConfig` (see below). |

The 24-hour `x-message-ttl` sits on the two **work queues**: a message nobody consumed within 24 h
is dead-lettered rather than rotting invisibly.

## Bindings

| Pattern | Queue | Covers |
|---|---|---|
| `user.social.#` | notifications | follow / unfollow / block / unblock / mention |
| `research.lifecycle.#` | notifications | research published |
| `research.social.#` | notifications | research reactions + comments |
| `post.lifecycle.#` | notifications | post created / deleted |
| `post.social.#` | notifications | post reactions, comments, shares |
| `qna.lifecycle.#` | notifications | question/answer lifecycle |
| `qna.social.#` | notifications | answers, answer reactions, accepts |
| `research.analytics.#` | analytics | research downloads (views moved to an inline Redis-NX path and no longer flow through Rabbit) |
| `dead-letter` | dead-letter (via `irc.dlx.exchange`) | everything rejected or expired |

## Routing keys (`RabbitMQConstants`)

| Constant | Routing key |
|---|---|
| `USER_FOLLOWED` | `user.social.followed` |
| `USER_UNFOLLOWED` | `user.social.unfollowed` |
| `USER_BLOCKED` | `user.social.blocked` |
| `USER_UNBLOCKED` | `user.social.unblocked` |
| `USER_MENTIONED` | `user.social.mentioned` |
| `RESEARCH_PUBLISHED` | `research.lifecycle.published` |
| `RESEARCH_REACTED` | `research.social.reacted` |
| `RESEARCH_COMMENTED` | `research.social.commented` |
| `RESEARCH_COMMENT_REACTED` | `research.social.comment.reacted` |
| `RESEARCH_DOWNLOADED` | `research.analytics.downloaded` |
| `QNA_QUESTION_CREATED` | `qna.lifecycle.created` |
| `QNA_QUESTION_DELETED` | `qna.lifecycle.deleted` |
| `QNA_ANSWER_DELETED` | `qna.lifecycle.answer.deleted` |
| `QNA_QUESTION_ANSWERED` | `qna.social.answered` |
| `QNA_ANSWER_REACTED` | `qna.social.answer.reacted` |
| `QNA_ANSWER_UNREACTED` | `qna.social.answer.unreacted` |
| `QNA_ANSWER_ACCEPTED` | `qna.social.accepted` |
| `POST_CREATED` | `post.lifecycle.created` |
| `POST_DELETED` | `post.lifecycle.deleted` |
| `POST_REACTED` | `post.social.reacted` |
| `POST_UNREACTED` | `post.social.unreacted` |
| `POST_COMMENTED` | `post.social.commented` |
| `POST_COMMENT_DELETED` | `post.social.comment.deleted` |
| `POST_COMMENT_REACTED` | `post.social.comment.reacted` |
| `POST_SHARED` | `post.social.shared` |

Wildcard patterns also live in the constants class: `POST_LIFECYCLE_PATTERN` (`post.lifecycle.#`),
`POST_SOCIAL_PATTERN` (`post.social.#`), `QNA_LIFECYCLE_PATTERN` (`qna.lifecycle.#`),
`QNA_SOCIAL_PATTERN` (`qna.social.#`). Publishers: `UserEventPublisher`,
`UserMentionEventPublisher`, `ResearchEventPublisher`, `QuestionEventPublisher`,
`PostEventPublisher` (all under `rabbitmq/publisher/`).

## Serialization

Messages are JSON via `Jackson2JsonMessageConverter`; the `__TypeId__` header carries the full
event class name so Spring AMQP dispatches to the matching `@RabbitHandler` method. Incoming types
are restricted to the trusted packages `ak.dev.irc.app.rabbitmq.event.{user,research,post,qna}`
(plus two legacy `irc_security` packages) — **put new event classes under these packages or extend
the trusted list** in `RabbitMQConfig.messageConverter`, or the consumer will reject them.

## Consumer retry policy

Configured in `RabbitMQConfig.rabbitListenerContainerFactory`:

- **3 attempts total** (1 original + 2 retries), stateless retry interceptor
- **Exponential back-off: 1 s → ×2.0 → capped at 10 s** (`backOffOptions(1_000, 2.0, 10_000)`)
- After exhausting retries: `RejectAndDontRequeueRecoverer` — the message is **rejected without
  requeue** and routed to `irc.dlx.exchange` → `irc.queue.dead-letter`. There is **no infinite
  requeue loop**: a poison message costs at most 3 processing attempts.
- `AcknowledgeMode.AUTO`, prefetch 10, 2–5 concurrent consumers.
- When the broker itself is unreachable (local dev without `docker compose up rabbitmq`), the
  container recovery backs off exponentially 5 s → 60 s instead of restarting every 5 s, and
  `missingQueuesFatal=false` keeps a brief queue absence during reconnect from killing the
  container.

## Publisher confirms, returns, and the DLQ drain

`application.yaml` enables `publisher-confirm-type: correlated` and `publisher-returns: true`;
`RabbitMQConfig.rabbitTemplate` wires the callbacks that make that overhead actually observable:

- **Confirm callback** — a broker **NACK** is logged at ERROR
  (`[RABBIT] broker NACKed publish … — event NOT delivered`) with the correlation id.
- **Returns callback** — `setMandatory(true)` + the callback log every **unroutable** message
  (wrong routing key / missing binding) at ERROR with exchange, routing key, and reply text,
  instead of silently dropping it.
- **DLQ listener** — a `@RabbitListener` on `irc.queue.dead-letter` (`drainDeadLetter`) logs every
  dead-lettered message at **ERROR** with its `x-first-death-exchange` / `x-first-death-queue`
  headers, `__TypeId__`, body size, and full header map — enough envelope detail to reprocess it.
  Without it the DLQ is a silent parking lot.

So every lost-message path — broker NACK, unroutable publish, retry exhaustion, TTL expiry — leaves
an ERROR log line.

## Transactional publishing (`afterCommit`)

All event publishers check `TransactionSynchronizationManager.isSynchronizationActive()` and, when
inside a transaction, register a `TransactionSynchronization` that publishes in **`afterCommit`**.
Consequences:

- Subscribers **never see rolled-back data** — if the transaction aborts, the event is simply never
  sent.
- Publish failures cannot roll back the business transaction (the commit has already happened);
  they surface through the confirm/returns logging above.
- Outside a transaction (e.g. async jobs), the publish happens immediately.

Follow the same pattern when adding a publisher method: never `convertAndSend` mid-transaction.

## Downstream: from queue to browser

`NotificationEventConsumer` saves the `Notification` row, then fires a Spring
`NotificationPushedEvent`; `NotificationPushEventListener` (AFTER_COMMIT, `@Async`) publishes to
Redis `irc:notifications:{userId}`, and the SSE layer delivers it to every open tab — see
[overview.md](overview.md#redis-channels-multi-instance-fan-out) and
[../post/realtime.md](../post/realtime.md). Failures in this pipeline never surface as API errors
to the acting user; they are retried/dead-lettered as described above
(cf. [../errors/error-handling.md](../errors/error-handling.md)).

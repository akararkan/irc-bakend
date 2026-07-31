# Indexing Pipeline & Admin Reindex

The write side of search: how the 8 Elasticsearch indices stay in sync with
their canonical stores, and how to rebuild them.

## Principles

1. **The canonical store is never ES.** Posts/reels/sounds live in
   Cassandra; users/questions/answers/research/channels in Postgres; chat
   messages in Cassandra. Every index is a rebuildable projection.
2. **Async, best-effort, off the request path.** Every `indexAsync` /
   `deleteAsync` runs on the shared task executor and swallows failures
   with a WARN. A lost write means an entity is *temporarily unsearchable*
   — never a failed user request. Search is eventually consistent
   (typically < 1 s: ES's default refresh interval).
3. **Transient-failure self-healing.** Every ES call (read and write) goes
   through `EsRetry` — one retry on the stale-pooled-connection class of
   error, which covers the connection-recycling race the pool tuning in
   `ElasticsearchConfig` can't fully eliminate.
4. **Boot-safe.** All documents declare `createIndex=false`;
   `ElasticsearchIndexInitializer` provisions each index **with its
   annotated mapping** after startup (idempotent, `exists()`-first). A
   missing ES never crashes boot — provisioning is skipped with a WARN and
   search degrades gracefully.
5. **Privacy at write time where possible.** Private channels are never
   written to `irc-channels`; disappearing chat messages are never written
   to `irc-chat-messages`; soft-deleted anything is deleted from its index.
   A filter you never index is a filter that can't regress.

## The 8 indices and their triggers

| Index | Canonical store | Indexed on | Removed on |
|---|---|---|---|
| `irc-posts` | Cassandra `posts_by_id` | post create / update | post delete |
| `irc-qna` | Postgres `questions` | question create / update | question delete (soft-delete de-indexes too) |
| `irc-answers` | Postgres `question_answers` | answer/reanswer create, edit, **accept/unaccept** (boost freshness) | answer delete; **question delete purges all its answer docs** (delete-by-query on `questionId`) |
| `irc-research` | Postgres `researches` | publish / update | delete / retract |
| `irc-users` | Postgres `users`+`user_profiles` | registration, identity update, profile update, **follow/unfollow** (refreshes `followerCount`) | account soft-delete |
| `irc-channels` | Postgres `conversations` (type=CHANNEL) | create, info update, verify, subscribe/unsubscribe (refreshes `subscriberCount`) | delete, **turning private** |
| `irc-sounds` | Cassandra `sounds_by_id` | upload (as PENDING), approve, every adoption (refreshes `useCount`) | — (status filter hides non-APPROVED) |
| `irc-chat-messages` | Cassandra `messages_by_conversation` | send / edit (TTL-gated: disappearing messages never indexed) | delete |

### The commit-race rule (users)

Most indexers receive the already-loaded entity, so they carry their data
with them. The **user** indexer instead re-loads user + profile + follower
count inside the background thread (avoids shipping lazy JPA proxies across
threads) — which creates a race: the background read could run before the
caller's transaction commits and see nothing. So `UserSearchService.indexAsync`
defers the hand-off with a `TransactionSynchronization.afterCommit` hook
when a transaction is active, and only then submits to the executor.
**Copy this pattern for any future indexer that re-reads Postgres.**

### Known, accepted drift

Engagement counters inside index docs (`reactionCount`, `commentCount`,
`viewCount` on posts; `reactionCount` on answers) are stamped at index time
and drift until the next entity mutation or admin reindex. They are ranking
*signals*, not displayed numbers — live counters always come from the
hydration endpoints. `subscriberCount` may briefly lag one membership event
(the atomic DB counter update isn't visible to the in-flight entity).

## Admin reindex — `POST /api/v1/admin/search/{corpus}/reindex`

**Auth:** `ROLE_ADMIN`. All runs are **synchronous** — the response carries
final counts so the caller knows it completed. Per-page failures are logged
and skipped (partial reindex beats zero); writes go through `EsRetry`.

| Corpus | Walks | Notes |
|---|---|---|
| `posts` | Cassandra `posts_by_id` in driver pages | nothing held in memory |
| `questions` | Postgres, live questions, 100/page | |
| `answers` | Postgres, live answers (reanswers incl.), 100/page | |
| `research` | Postgres, PUBLISHED only, 100/page | the original endpoint — response shape is the same |
| `users` | Postgres, active accounts, 100/page | recomputes `followerCount` per user |
| `channels` | Postgres, live public channels, 100/page | |
| `sounds` | Full Cassandra library walk + counter reads | fine: bounded curated catalog |

There is deliberately **no chat-messages reindex**: the store is partitioned
per-conversation for the read path (no efficient full scan), and the index
self-heals per-message on send/edit/delete.

### Parameters & response

```
POST /api/v1/admin/search/users/reindex?drop=true
```

| Param | Default | Meaning |
|---|---|---|
| `drop` | `true` | Delete the index, **explicitly recreate it from the entity mapping**, then re-emit. Required to land new `@Field`s — and the repair path below. `drop=false` refreshes document values without touching the mapping. |

```json
{ "indexDropped": true, "documentsIndexed": 1842, "pages": 19,
  "durationMs": 5210, "note": "Reindexed 1842 …" }
```

### The dynamic-mapping trap (and its repair)

> **Incident, fixed:** an index that gets auto-created by its *first
> document write* (ES dynamic mapping) instead of by the initializer maps
> every string as `text` — including lifecycle fields like `visibility`,
> `status`, `postType` that the documents declare as `Keyword`. Exact
> `term` filters silently never match analyzed text ("FOLLOWERS_ONLY" ≠
> the lowercased indexed token), so **the visibility/status filters did
> not filter** on the legacy `irc-posts`/`irc-qna` indices, and the REEL
> type split didn't split. This is why every reindex now **explicitly
> `createWithMapping()` after dropping** — relying on the first `save()`
> to recreate the index would re-enter the trap — and why the boot
> initializer provisions mappings up front.
>
> **If you ever see a term filter mysteriously not filtering:** check
> `GET {index}/_mapping` — if the keyword fields say `"type": "text"`,
> run that corpus's reindex with `drop=true`.

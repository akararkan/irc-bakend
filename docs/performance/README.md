# Data Retrieval & Database Optimization

How this codebase applies the standard Spring Data retrieval strategies —
pagination, DTO projections, fetch optimization (`JOIN FETCH` /
`@EntityGraph` / `@BatchSize`), indexes, bulk `IN` reads, and Redis caching
— and the results of the 2026-08 read-path audit.

| Doc | Contents |
|---|---|
| [read-path-audit.md](./read-path-audit.md) | The full audit: every finding, what was fixed (with file references), and what was deliberately deferred |

---

## House rules for retrieval code (enforced patterns)

1. **Never unbounded `findAll()` on request paths.** Every list endpoint
   takes `pageSize`/`limit` clamped by `Pages.clamp` (1..100) or returns a
   Spring `Page`. `findAll()` is tolerated only for tiny fixed vocabularies
   (topics/madhhabs — now cached) and admin reindex jobs.

2. **The `User.profile` trap.** `User.profile` is a *non-owning*
   (`mappedBy`) one-to-one — Hibernate cannot proxy it, so **every `User`
   row loaded without a profile fetch fires one extra SELECT** the moment
   the row materializes or `getProfileImage()` is read. Any query that
   feeds a mapper touching avatars MUST either:
   - `LEFT JOIN FETCH u.profile` (what the social/feed/research/qna list
     queries now do), or
   - bulk-load via `UserRepository.findActiveWithProfileByIdIn(ids)` (what
     hydrators, notifications, mentions, suggestions do).
   Plain `findAllById` before reading avatars is a bug.

3. **Page + fetch join needs an explicit `countQuery`.** Spring Data cannot
   derive a count from a fetch-join query — every `Page<>` @Query with
   `JOIN FETCH` in this repo carries one.

4. **Collections on listed entities carry `@BatchSize`.** Profile
   collections (specializations/links/contacts/attachments), research tags,
   research-comment replies — so a page of rows loads each collection in
   one `IN` batch instead of one query per row. `User` and `Madhhab` are
   class-level `@BatchSize(50)` so lazy to-one *proxies* batch too.

5. **Bulk `IN`, never point reads in loops.** The Cassandra hydrators
   (`PostHydrator`, counters, liked/saved flags), notification actors,
   mention candidates, suggestion signals and the sound reindex all load
   per *page/batch*, not per row.

6. **Cache the hot, tolerate-staleness reads in Redis** (existing pattern:
   `RedisCacheConfig`). Current cache map: `user-profile` 5m ·
   `user-following-ids`/`user-blocked-ids` 1m · `research-by-id/-slug` 5m ·
   `research-feed` 2m · `trending-tags` 10m · `search-results` 60s ·
   `mention-suggestions` 30s · `user-email-ctx` 60s · `user-stats` 30s ·
   `chat-suppress-ephemeral` 10s · **`knowledge-topics`/`knowledge-madhhabs`
   1h (new)**.

7. **Indexes follow the queries.** Every JPA entity's custom `@Query`
   filters ride a declared `@Index` (status, FK-ish UUID columns,
   `deleted_at`). Cassandra reads are single-partition by design — a
   multi-partition scan on a request path is a design bug.

8. **DTOs over entities on the JPA side** (`UserResponse`,
   `ConversationResponse`, `MessageResponse`…). A handful of Cassandra
   endpoints still return raw rows — accepted debt, see the audit.

## Applied in the 2026-08 pass (summary)

- **N+1 kills** (the dominant finding — "row loaded, avatar lazy-fires"):
  followers/following/blocked/restricted lists, research feed ×3 queries,
  question feed ×6 queries, answers ×4 entity graphs (now include
  `author.profile`), research comments ×2 queries, notification inbox
  actors, mention suggestions.
- **`@BatchSize`**: User + Madhhab (class-level), UserProfile ×4
  collections, Research.tags, ResearchComment.replies.
- **Unbounded load removed**: `ChannelService.listAdmins` now uses a
  targeted `role IN (OWNER, ADMIN)` query instead of loading every
  subscriber of the channel.
- **Bulk IN**: sound reindex counter reads batched (100 per query).
- **Caching**: knowledge vocabularies (1h TTL).
- **Index**: `research_comment_likes(user_id)` for parity with sibling
  reaction tables.

## Deliberately deferred (with designs in the audit doc)

- Caching the per-request JWT principal load (security-path change — needs
  live verification; design documented).
- DTO-wrapping the raw-entity Cassandra endpoints (API contract change).
- Slim summary projections for research/question cards (drops large HTML
  columns from feed queries).
- Token-range paging for the admin post-tag backfill full scan.

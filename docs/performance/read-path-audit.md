# Read-Path Audit — 2026-08

Full-codebase sweep of GET/read paths against the standard retrieval
checklist (unbounded loads, missing pagination, N+1, missing projections,
missing indexes, per-row point reads, uncached hot lookups). Status legend:
✅ fixed in this pass · 📋 deferred (accepted debt, design below) ·
✔ already fine.

The **dominant root cause** found: `User.profile` is a non-owning
(`mappedBy`) one-to-one that Hibernate cannot lazily proxy — every `User`
loaded without a profile fetch costs one extra SELECT, and most list
mappers read `getProfileImage()`. The same shape repeated across user,
research, qna and notification paths.

---

## ✅ Fixed

### N+1: author/actor profile loads on list endpoints

| Path (endpoint) | What fired per row | Fix |
|---|---|---|
| `GET /users/{id}/followers`, `/following` | profile SELECT per user | `LEFT JOIN FETCH profile` + countQuery (`UserFollowRepository`) |
| `GET /users/blocked`, `/restricted` | same | same (`UserBlockRepository`, `UserRestrictionRepository`) |
| `GET /notifications`, `/unread` | profile SELECT per distinct actor | `findAllById` → `findActiveWithProfileByIdIn` (`NotificationServiceImpl`) |
| mention picker (`MentionSuggestionService`) | same | same swap |
| `GET /research/feed`, `/feed/following`, public feed | researcher + profile SELECT per card | `JOIN FETCH r.researcher + profile` on 3 queries + countQueries (`ResearchRepository`) |
| `GET /questions/feed` (all 6 variants incl. cursor + block-aware + following) | author + profile SELECT per card | `JOIN FETCH q.author + profile` (`QuestionRepository`) |
| `GET /questions/{id}/answers`, reanswers | profile SELECT per answer (author was already in the graph) | `@EntityGraph` widened to `author.profile` ×4 (`QuestionAnswerRepository`) |
| `GET /research/{id}/comments` | user + profile SELECT per comment | derived finders → fetch-join `@Query` ×2 (`ResearchCommentRepository`) |

### Collection walks batched (`@BatchSize`)

- `UserProfile.specializations/links/contacts/attachments` (size 100) — the
  full-profile mapper walk on user lists collapses to one `IN` per
  collection per page.
- `Research.tags`, `ResearchComment.replies` (size 50).
- Class-level `@BatchSize(50)` on `User` (batches lazy to-one proxy inits:
  feed authors, comment users, actors) and `Madhhab`.

### Unbounded loads

- ✅ `ChannelService.listAdmins` loaded **every** `conversation_members`
  row of a channel (potentially millions for a public channel) to filter
  out ~3 staff. Now a targeted query:
  `ConversationMemberRepository.findActiveStaff` (`role IN (OWNER, ADMIN)
  AND status = ACTIVE`).

### Per-row point reads

- ✅ Sound reindex (`SoundSearchService`): one `sound_counters` point read
  per sound → bulk `findAllBySoundIdIn` per 100-sound batch.

### Caching

- ✅ `GET /topics`, `GET /madhhabs` — full-table read per picker keystroke →
  `KnowledgeVocabularyService` with `@Cacheable` (`knowledge-topics`,
  `knowledge-madhhabs`, 1 h TTL registered in `RedisCacheConfig`).

### Indexes

- ✅ `research_comment_likes` had no `@Index` (sole outlier among reaction
  tables) → `idx_rcomment_like_user (user_id)`; the composite PK already
  covers the comment-side lookups.

---

## 📋 Deferred (accepted debt + designs)

### 1. Per-request principal load is uncached (highest-frequency query)

`JwtAuthenticationFilter` → `CustomUserDetailsService` loads the full
`User` row on **every authenticated request**. Not changed here because it
is a security path with entity-detachment traps (a Redis-cached, detached
`User` would NPE/lazy-fail on `getProfileImage()` in controllers) and needs
live verification.

**Design when picked up:** cache a *slim principal record* (id, username,
email, role, enabled/locked flags, password hash if needed for
re-validation) keyed by identifier, TTL 30–60 s, in the existing
`RedisCacheConfig` style; evict on user disable/delete/role change; rebuild
a lightweight `UserDetails` from it and keep the enabled/locked re-check.
Controllers that need the full entity keep loading it lazily by id.

### 2. Raw Cassandra entities returned by ~10 endpoints

`/suggestions` (raw variant), `/users/{id}/reactions`, `/{postId}/shares`,
`/hashtags/{tag}/posts`, `/users/{id}/mentions`, highlights ×2, stories
×3, `/close-friends`, poll voters. Leaks storage layout into the API
contract (no field hiding). Fix is mechanical (thin response records like
`FeedItemResponse`), but it is a **client-visible contract change** —
coordinate with the frontend before wrapping.

### 3. Summary projections for research/question cards

Feed queries still materialize full entities including large
`description_html` / `body` TEXT columns the cards never render. The author
N+1 (the bigger cost) is fixed; a projection pass would additionally drop
the heavy columns from the wire. Needs mapper surgery — do it when feed
latency numbers justify it.

### 4. Admin backfill full scan

`TagAdminController.backfillPosts` iterates `postByIdRepo.findAll()`
(entire posts table) — acceptable for a one-shot admin job today; page via
driver fetch-size/token ranges if the table grows past memory comfort.

### 5. Minor

- Highlight list endpoints have no explicit page cap (bounded in practice
  by per-user highlight counts).
- `ConversationService.get` resolves a DM peer with two queries (bounded:
  2 members) — the inbox list path already batches correctly.

---

## ✔ Verified fine (no action)

- All feed/messaging/channel/group/live/research/qna list endpoints are
  paginated (`Page<>` or clamped `pageSize`).
- Core social/chat entities are fully indexed for their query shapes
  (`UserFollow` incl. composite `(following_id, followed_at DESC)`,
  `ConversationMember` inbox index, `LiveStream(status/host_id)`, all
  research/qna reaction+save tables).
- `User`'s `authorities`/`refreshTokens`/`notifications` collections are
  LAZY and untouched on read paths.
- Cassandra read paths are single-partition slices with bulk `IN`
  hydration (see [../feed/algorithm.md](../feed/algorithm.md)).
- JPA endpoints return DTOs (`UserResponse`, `ConversationResponse`,
  `MessageResponse`) — no raw `User`/`Conversation` leaks.

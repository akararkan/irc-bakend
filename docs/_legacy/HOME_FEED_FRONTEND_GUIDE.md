# Home Feed (Mixed) — React Frontend Guide

The home feed at `GET /api/v1/posts/feed` now returns **posts, research,
and Q&A** in a single chronological stream. Each item carries an
`entityType` discriminator that tells the frontend which detail
endpoint to navigate to and which card variant to render.

Audience: frontend engineers wiring the home page.

---

## 1. What changed (and what didn't)

**Endpoint** — unchanged:
- `GET /api/v1/posts/feed?limit=20[&cursor=…]`
- `GET /api/v1/posts/feed/cursor` (alias)
- Auth: JWT required (cursor pagination is identical to before).

**Response shape** — one new field on each item: `entityType`.

```ts
type EntityType = 'POST' | 'RESEARCH' | 'QUESTION';

type FeedItemResponse = {
  id: string;            // postId | researchId | questionId
  authorId: string;
  author: { id, fullName, username, profileImage };
  entityType: EntityType;            // ← NEW: the discriminator
  postType: string;                  // POST | REEL | SHARE for POST entries
                                     // 'PUBLICATION' for RESEARCH
                                     // 'QUESTION' for QUESTION
  textPreview: string;               // for POST: text; for RESEARCH: title; for QUESTION: title
  mediaUrl: string | null;           // for POST: cover/photo; for RESEARCH: cover image; for QUESTION: null
  videoUrl: string | null;           // REEL only — null for everything else
  reactionCount: number;             // POST only — 0 for RESEARCH / QUESTION
  commentCount: number;              // POST only — 0 for RESEARCH / QUESTION
  viewCount: number;                 // POST only — 0 for RESEARCH / QUESTION
  saveCount: number;                 // POST only — 0 for RESEARCH / QUESTION
  shareCount: number;                // POST only — 0 for RESEARCH / QUESTION
  likedByMe: boolean;                // POST only — false for RESEARCH / QUESTION
  savedByMe: boolean;                // POST only — false for RESEARCH / QUESTION
  createdAt: string;                 // ISO instant — sort key
};
```

**Why counters are zero for RESEARCH/QUESTION** — see [§5](#5-why-no-counters-on-research--qna-cards).

---

## 2. The dispatch contract — read this first

The **only** correct way to render a feed item is to branch on
`entityType` FIRST, then optionally on `postType` for POST flavours.
Never branch only on `postType` — `postType=POST` is ambiguous between
a real post and the absence of a sub-type on a non-POST row.

```tsx
function FeedItem({ item }: { item: FeedItemResponse }) {
  switch (item.entityType) {
    case 'POST':     return <PostCard post={item} />;       // PostCard handles REEL via postType
    case 'RESEARCH': return <ResearchCard research={item} />;
    case 'QUESTION': return <QuestionCard question={item} />;
    default:
      // Forward-compatible: a future entity type renders nothing rather than crashing.
      console.warn('[FEED] unknown entityType:', item.entityType);
      return null;
  }
}
```

### Click-through routing

Each card opens its own detail page. Use react-router (or your router
of choice) with this mapping:

| entityType | Route | Backend detail endpoint |
|---|---|---|
| `POST` | `/posts/:id` | `GET /api/v1/posts/{id}` |
| `RESEARCH` | `/researches/:id` (or `/researches/:slug` if you have it) | `GET /api/v1/researches/{id}` |
| `QUESTION` | `/questions/:id` | `GET /api/v1/questions/{id}` |

```tsx
function cardHref(item: FeedItemResponse): string {
  switch (item.entityType) {
    case 'POST':     return `/posts/${item.id}`;
    case 'RESEARCH': return `/researches/${item.id}`;
    case 'QUESTION': return `/questions/${item.id}`;
  }
}
```

---

## 3. Card components — minimal recipes

### `<PostCard>` (unchanged from your current feed)

The existing post card keeps working without modification — all
post-shaped fields (`reactionCount`, `likedByMe`, `videoUrl`, …) are
populated exactly as before for `entityType=POST` rows.

### `<ResearchCard>` — new

Shows the cover image (when present), the title (carried in
`textPreview`), the author, a "Publication" badge, and an "Open" CTA.
Counters live on the research detail page — don't try to render them
here.

```tsx
function ResearchCard({ research }: { research: FeedItemResponse }) {
  return (
    <article className="card card--research">
      <Badge>📄 Publication</Badge>
      {research.mediaUrl && (
        <img src={research.mediaUrl} alt="" className="card__cover" />
      )}
      <h3>{research.textPreview}</h3>
      <AuthorRow author={research.author} createdAt={research.createdAt} />
      <Link to={`/researches/${research.id}`}>Open paper →</Link>
    </article>
  );
}
```

### `<QuestionCard>` — new

Question title only — no cover image (the snapshot's `mediaUrl` is
always null for QUESTION rows). Show a "Q&A" badge + an "Answer" CTA
that navigates to the detail page.

```tsx
function QuestionCard({ question }: { question: FeedItemResponse }) {
  return (
    <article className="card card--question">
      <Badge>❓ Question</Badge>
      <h3>{question.textPreview}</h3>
      <AuthorRow author={question.author} createdAt={question.createdAt} />
      <Link to={`/questions/${question.id}`}>Answer this →</Link>
    </article>
  );
}
```

---

## 4. Optional — fetch live counters on demand

If you want the card to show "12 reactions • 4 comments" for research /
Q&A, fire a **lazy** fetch per visible card. Don't block the whole feed
render on it.

```tsx
function useLiveCounters(item: FeedItemResponse) {
  // Only POST has counters baked into the feed response.
  if (item.entityType === 'POST') {
    return {
      reactionCount: item.reactionCount,
      commentCount: item.commentCount,
    };
  }
  // Lazy fetch for research / qna — only when the card is on screen.
  const detailUrl =
    item.entityType === 'RESEARCH' ? `/api/v1/researches/${item.id}` :
    item.entityType === 'QUESTION' ? `/api/v1/questions/${item.id}`   : null;

  const { data } = useQuery({
    queryKey: ['feed-counters', item.entityType, item.id],
    queryFn:  () => fetch(`${API_BASE}${detailUrl}`).then(r => r.json()),
    staleTime: 60_000,
    enabled:  !!detailUrl,
  });
  return {
    reactionCount: data?.reactionCount ?? 0,
    commentCount: data?.commentCount ?? data?.answerCount ?? 0,
  };
}
```

Pair with `react-intersection-observer` so the fetch fires only when
the card scrolls into view.

> **Tradeoff**: each visible non-POST card costs 1 extra HTTP round
> trip. Worth it if showing engagement numbers is important; skip it
> for v1 if the card is fine without numbers.

---

## 5. Why no counters on RESEARCH / QnA cards?

The backend deliberately does NOT bulk-hydrate research/Q&A counters
at feed read time. Reasons:

1. **Cross-domain coupling** — `PostHydrator` would need to call into
   `ResearchRepository` and `QuestionRepository`, dragging JPA reads
   into a Cassandra-only read path.
2. **Latency budget** — the feed read is ~20 items × 3 entity types
   would need ~3 separate JPA round-trips per page, hurting the p99.
3. **Stale-by-design** — `feed_by_user` snapshots `textPreview` /
   `mediaUrl` at fanout time; counters move every second, so even if
   we fetched them they'd be stale by the time the user scrolls past.

The mental model: **the feed card is an invitation, the detail page is
the truth.** All live numbers (reactions / answers / citations) come
from the detail endpoint, which already does the fast `existsById`
lookup for `likedByMe` / `savedByMe` and returns the authoritative
counters.

---

## 6. Realtime updates — fanout vs. broadcast

The realtime push for new posts (`FEED_NEW_POST` on the user's SSE
channel) **also** fires for research and Q&A now — same channel, same
event shape. Your existing notification SSE handler does not need to
change to receive them; the feed list refresh on `FEED_NEW_POST`
already covers it.

If you want to render a "new content above" banner that includes the
entity type, the realtime payload carries `entityId` and `authorId`
but not `entityType` (the existing event schema kept tight to avoid
breaking older clients). The simplest path: when the banner is
clicked, refetch the feed page from cursor=null — the new
research/Q&A entries naturally appear in the response with their
correct `entityType`.

---

## 7. Sort & pagination — pure chronological, unchanged

Items come back **strict newest-first by `createdAt`** regardless of
`entityType`. A 2-hour-old post and a 2-hour-old research land next
to each other; a day-old question sits below both.

Cursor pagination (`?cursor=…`) is unchanged — the cursor token is
opaque and round-trips back to the next `?cursor=`. Stable across
inserts: a new fanout landing mid-scroll never reshuffles a later page.

---

## 8. Edge cases the backend handles for you

- **Author deleted between fanout and read** → `author` is null on the
  item. Render a graceful "User unavailable" row.
- **Research deleted before the feed row TTL expires (30 days)** →
  detail endpoint will 404. Catch and show a "This research was
  removed" card OR auto-hide the row.
- **Question soft-deleted by author** → same as above.
- **Research went PRIVATE after publish** → already in followers'
  feed_by_user rows; the detail endpoint will 403 for non-followers.
  Catch and hide.
- **Mixed-entity TTL** — `feed_by_user` rows for research/Q&A use the
  same 30-day TTL as posts. After 30 days a stale row drops off; the
  detail page still works, the card just disappears from the feed.

---

## 9. Migration checklist for your home page

- [ ] Add `entityType` to your `FeedItemResponse` TypeScript type.
- [ ] Replace any direct `<PostCard item={item} />` with a `<FeedItem item={item} />` switch on `entityType`.
- [ ] Add `<ResearchCard>` and `<QuestionCard>` components.
- [ ] Update click-through routing per §2.
- [ ] (Optional) Add `useLiveCounters` for non-POST cards if you want engagement numbers.
- [ ] No changes needed to the SSE handler, the cursor pagination, or the rate-limit logic.

---

## Appendix — backend mental model (for context)

- One Cassandra table (`feed_by_user`) holds rows for all three entity
  types, partitioned by `user_id`. New column: `entity_type text`.
- Fanout fires from three event handlers:
  - `Post.created` → existing `FeedTimelineService.fanoutAsync(…)`
  - `ResearchPublishedEvent` (RabbitMQ) → `fanoutResearchPublished(…)`
  - `QuestionCreatedEvent` (RabbitMQ) → `fanoutQuestionCreated(…)`
- Each follower gets one row per published item, written via the
  bounded `taskExecutor` (no JVM common-pool burn) with the existing
  circuit-breaker fronting the Cassandra writes.
- Read path: `PostHydrator.hydrateHomeFeed(…)` branches on
  `entity_type` and only bulk-loads `post_counters` / `posts_by_id`
  for POST rows. RESEARCH / QUESTION rows pass through with snapshot
  preview + zeroed counters.
- Research visibility (`PRIVATE`) skips the follower fanout; only the
  author's own feed gets the row.

The Cassandra migration is additive — no destructive change:

```cql
ALTER TABLE feed_by_user ADD entity_type text;
```

Pre-migration rows read back as `entity_type=null`, which the read
layer treats as `POST`.

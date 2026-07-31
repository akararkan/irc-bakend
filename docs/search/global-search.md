# Global Search API — `GET /api/v1/search`

One Elasticsearch query over up to **seven indices in parallel**, returning a
single score-ordered merge across **eight entity types**:

| `types=` value | Index | Entity |
|---|---|---|
| `POST` | `irc-posts` | Social posts (TEXT / EMBEDDED / VOICE_POST / REPOST) |
| `REEL` | `irc-posts` (filtered `postType=REEL`) | Reels |
| `QUESTION` | `irc-qna` | Q&A questions |
| `ANSWER` | `irc-answers` | Q&A answers **and reanswers** |
| `RESEARCH` | `irc-research` | Published research |
| `USER` | `irc-users` | Accounts (public profile fields only) |
| `CHANNEL` | `irc-channels` | **Public** broadcast channels |
| `SOUND` | `irc-sounds` | **Approved** sound-library entries |

This is the only cross-entity full-text entry point. The retired per-entity
`/posts/search`, `/questions/search`, `/researches/search` endpoints are gone
for good; the dedicated surfaces that remain
([users](users.md), [channels](channels.md), [sounds](sounds.md),
[chat](../chat/search.md)) exist because they carry extra semantics
(exact-store consistency, membership scoping, hydrated response shapes) — not
because they run a different engine.

**Auth:** public — no token required. Because anyone can call it, the query
enforces the *public-visibility* rule for everyone (see Privacy below).

---

## Request

```
GET /api/v1/search?q={text}&types={CSV}&size={n}&cursor={token}&page={p}&expand={bool}
```

| Param | Type | Default | Notes |
|---|---|---|---|
| `q` | string | — (required) | Free text. Present-but-blank → `200` with `results: []`. Missing → `400 MISSING_PARAMETER`. |
| `types` | CSV | all 8 | Any subset of the table above, case-insensitive. Unknown tokens silently skipped; nothing valid left → searches **all** types. |
| `size` | int | `20` | Results per page across all selected indices combined. Clamped `[1, 100]`. |
| `cursor` | string | — | Opaque token from the previous response's `nextCursor`. When present, `page` is ignored and paging runs via ES `search_after`. **Preferred.** An unparseable cursor value (e.g. `cursor=head`) starts cursor mode at the head. |
| `page` | int | `0` | Offset mode (legacy). Fine for shallow static pages; drifts on live data. |
| `expand` | bool | `true` | Inline preview fields on each hit (zero extra round-trips). `false` → bare `(contentType, contentId, score)`. |

### Cursor vs offset

Cursor mode sorts on `(score DESC, _doc ASC)` and seeks with `search_after`:

- **Stable across inserts** — a document indexed mid-scroll can't shift,
  duplicate, or skip later pages.
- **O(t·log n + k) per page at any depth** — offset mode must collect and
  discard `page × size` hits first.

The token is base64 of the last hit's sort values — treat as opaque.
`nextCursor: ""` means no further page.

---

## Response `200`

```json
{
  "query":    "tafsir methodology",
  "types":    ["POST","REEL","QUESTION","ANSWER","RESEARCH","USER","CHANNEL","SOUND"],
  "page":     0,
  "size":     20,
  "results":  [ /* GlobalSearchHit, score-ordered — render in order */ ],
  "degraded": false,
  "nextCursor": "WzEzLjQyLDQ1Nl0"      // cursor mode only
}
```

`degraded: true` (mirrored by the `X-Search-Degraded: true` header) means the
ES call failed and `results` is empty — show a "search is degraded" banner,
not an error toast. A missing index on a fresh cluster is a legitimately
empty result, **not** degraded.

### Hit shape (`GlobalSearchHit`, null fields omitted)

| Field | Type | Meaning |
|---|---|---|
| `contentType` | string | One of the 8 types. Posts/reels share an index and are split by the `postType` source field. |
| `contentId` | UUID | Canonical entity id — hydrate by type (below). |
| `parentId` | UUID | **ANSWER hits only**: the owning question. Always present on answers (needed for deep-linking, not just preview). |
| `type`, `id` | — | Deprecated aliases of `contentType`/`contentId`; migrate. |
| `score` | double | Relevance (BM25 × function_score). Ordering only. |
| `titlePreview` | string | `expand` only. ≤280 chars of the primary text — per-type: post `textContent`, question/research/channel/sound `title`, answer `body`, user `displayName` (fallback `fname lname`). |
| `authorUsername` | string | `expand` only. Per-type: content author's handle; **USER → the user's own username; CHANNEL → the channel `@handle`**. |
| `authorName` | string | `expand` only. Display name; **SOUND → the artist name**. |
| `createdAt` | instant | `expand` only. Entity creation time. |

Hydration endpoints per type:

| `contentType` | Hydrate with |
|---|---|
| `POST` / `REEL` | `GET /api/v1/posts/{id}` |
| `QUESTION` | `GET /api/v1/questions/{id}` |
| `ANSWER` | `GET /api/v1/questions/{parentId}` (answer listed within) |
| `RESEARCH` | `GET /api/v1/researches/{id}` |
| `USER` | `GET /api/v1/users/{id}` / profile endpoints |
| `CHANNEL` | `GET /api/v1/channels/{id}` (404s if since deleted/private — drop the hit) |
| `SOUND` | `GET /api/v1/sounds/{id}` |

No viewer-specific fields (`savedByMe`, `isFollowing`, …) are ever inlined —
those belong to the hydration endpoints, which also own live counters and
moderation state.

---

## Ranking (always on, no query knobs)

One `bool` query wrapped in a `function_score` — six relevance layers, then
score shaping. Full math in
[algorithms-and-complexity.md](algorithms-and-complexity.md).

1. **Weighted multi-field recall** (`multi_match`, BestFields,
   `tieBreaker=0.3`): `title^4`, `username^4`, `handle^4`, `displayName^3`,
   `usernameTokens^3`, `handleTokens^3`, `textContent^3`, `abstractText^2`,
   `keywords^2`, `body^2`, `tags^2`, `hashtags^2`, `fname^2`, `lname^2`,
   `questionTitle^2`, `artistName^2`, plus bio/affiliation/description/
   author-name/location fields at ×1. Fields a given index doesn't have are
   silent no-ops, so one field list serves all seven indices.
2. **Typo tolerance** — `fuzziness=AUTO` (1 edit at 3–5 chars, 2 at 6+).
3. **Phrase boost** (×2.0) — exact phrases beat scattered tokens.
4. **Typeahead** — `match_phrase_prefix` (×1.5) catches the in-flight last
   word (`"ramad"` → `"ramadan"`), incl. username/handle prefixes.
5. **Required overlap** — `minimum_should_match=75%` keeps
   single-common-token noise out of multi-word queries.
6. **Lifecycle + privacy filters** — see below.

Score shaping (`function_score`, `score_mode=sum`, `boost_mode=multiply` —
i.e. `final = BM25 × Σ(functions)`):

| Function | Applies to | Why |
|---|---|---|
| Gaussian recency decay on `createdAt` (full strength ≤1 d, ×0.5 at 30 d) | content indices | fresh content outranks equally-relevant stale content |
| Constant weight 1.0 | `irc-users`, `irc-channels`, `irc-sounds`, `irc-answers` | **entity baseline** — people/channels/sounds/answers are long-lived; without this, an old account with no followers would multiply BM25 by ~0 and vanish |
| `log1p(reactionCount)` ×1.0 | posts (+answers carry it too) | social approval |
| `log1p(answerCount)` ×2.0 | questions | more answers = better question |
| `log1p(citationCount)` ×1.5 | research | academic gold standard |
| `log1p(followerCount)` ×1.0 | users | reach; log1p keeps a 1M-follower account from drowning an exact-handle match |
| `log1p(subscriberCount)` ×1.0 | channels | reach |
| `log1p(useCount)` ×1.0 | sounds | adoption |
| `viewCount` ×0.5 (index-scoped) | questions | tiebreaker only |
| `commentCount` ×0.5 (index-scoped) | posts | discussion tiebreaker |
| `downloadCount` ×0.5 (index-scoped) | research | access tiebreaker |
| Constant weight **2.0** where `accepted=true` | answers | the author-accepted answer outranks sibling answers of equal relevance |

### Lifecycle filter

Documents in any of these statuses never surface, on any index
(`mustNot term` on a missing field is a no-op, so one list serves all):

```
DELETED · DRAFT · ARCHIVED · RETRACTED · REMOVED_BY_MODERATOR
REMOVED (posts) · PENDING_REVIEW · REJECTED (sounds)
```

### Privacy filter

> **Fixed (previous behavior leaked):** non-public content used to be
> searchable by anyone. The query now excludes
> `visibility ∈ {FOLLOWERS_ONLY, ONLY_ME, PRIVATE}` on every index that has
> a visibility field (posts, research). The rule is deliberate and absolute:
> **search is a public discovery surface** — non-public content is reached
> through its own feeds (home timeline, profile, saved), never through
> search, not even by its owner. Private channels are excluded further
> upstream: they are never written to the index at all
> ([indexing-and-reindex.md](indexing-and-reindex.md)), with a query-side
> `publicChannel=true` belt on the discover path. Chat messages are excluded
> from global search entirely — they are membership-scoped and live behind
> [their own API](../chat/search.md).

## Errors

| Status | `errorCode` | When |
|---|---|---|
| 400 | `MISSING_PARAMETER` | `q` not supplied at all |
| 200 | — (`degraded: true`) | Elasticsearch unreachable — deliberate soft-fail |

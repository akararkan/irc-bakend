# Channel Search & Discovery

Public broadcast channels are searchable in two places, both backed by the
`irc-channels` Elasticsearch index. **Only public channels are ever
indexed** — privacy is enforced at write time (a channel that turns private
is deleted from the index on that same update), with a query-side
`publicChannel=true` filter as a belt on top, and Postgres re-checks
public + live state at hydration. Three layers; a stale index row cannot
leak a private channel.

| Surface | Purpose |
|---|---|
| `GET /api/v1/channels/discover?q=&category=` | The in-app channel directory — hydrated `ChannelResponse[]` incl. your membership state |
| `GET /api/v1/search?types=CHANNEL` | The unified search bar — channels ranked against all other content |
| `GET /api/v1/channels/by-handle/{handle}` | Exact `@handle` resolution (O(1) unique-index lookup, not a search) |

---

## `GET /api/v1/channels/discover`

```
GET /api/v1/channels/discover?q={text}&category={cat}
```

**Auth:** Bearer JWT.

> **Rewritten (current behavior):** non-blank queries no longer run the
> sequential-scan `LOWER(title) LIKE '%q%'`. They now search the
> `irc-channels` ES index — BM25 over `handle^4` / `handleTokens^3` /
> `title^3` / `description` with `fuzziness=AUTO` (typo tolerance) and a
> phrase-prefix layer (typeahead: `"ilm"` matches `@ilmhub`), multiplied by
> `1 + log1p(subscriberCount)` so big channels win ties while relevance
> stays in charge. `handleTokens` is the handle pre-split on `._-` so a
> query for one segment (`hub`) still matches `@ilm_hub`. `O(t · log n)`.
>
> **Fallbacks, in order:** blank `q` (the "browse all" directory) → the
> original Postgres listing ordered by subscribers; ES unavailable or
> zero ES hits (cold index) → the bounded Postgres `LIKE` scan
> (O(n) but capped at top-50). Discovery never goes dark because ES is.

| Param | Notes |
|---|---|
| `q` | Search text. Blank = list all public channels, most-subscribed first |
| `category` | Optional exact category filter (applies on both paths) |

**Response `200`:** `ChannelResponse[]` (≤50), ES-relevance-ordered for
non-blank `q`, hydrated from Postgres with one batched membership query —
each row carries your own `membership` state, `subscriberCount`, `verified`,
etc. Deleted/now-private channels are dropped during hydration.

**Side effects:** none.

## Global search — `types=CHANNEL`

Contract in [global-search.md](global-search.md). Channel-specific mapping:
`titlePreview` = channel title, `authorUsername` = the `@handle`.
Hydrate with `GET /api/v1/channels/{id}` (a 404 means it was deleted or went
private since indexing — drop the hit).

## Index lifecycle

`irc-channels` docs: `title`, `handle`, `handleTokens`, `description`,
`category` (keyword), `verified`, `publicChannel`, `subscriberCount`,
`createdAt`.

Re-indexed (async, best-effort) on: create, profile update (title /
description / category / handle / public↔private — private = de-index),
verified-badge change, and subscribe/unsubscribe (keeps the
`subscriberCount` ranking signal fresh; the counter may briefly lag by one —
it self-corrects on the next membership event or reindex). Admin rebuild:
`POST /api/v1/admin/search/channels/reindex`
([indexing-and-reindex.md](indexing-and-reindex.md)).

# People Search

Two complementary surfaces search the same people, on different engines —
each kept because it does something the other can't:

| Surface | Engine | Use it for |
|---|---|---|
| `GET /api/v1/search?types=USER` | Elasticsearch `irc-users` | The unified search bar — people ranked **against content** in one score scale, follower-count boost, typo tolerance |
| `GET /api/v1/users/search` | Postgres FTS + pg_trgm | The dedicated people picker — transactionally fresh (zero indexing lag), role filtering (`eligibleContributor`), full `Page<UserResponse>` hydration |
| `GET /api/v1/mentions/suggest` | Postgres prefix + trigram | @-mention typeahead — block-filtered, cached ([../platform/mentions.md](../platform/mentions.md)) |

---

## 1. Global — `GET /api/v1/search?types=USER`

Searches the `irc-users` index. Full request/response contract in
[global-search.md](global-search.md); user-specific behavior:

**Indexed fields** (all public-profile data): `username` (^4), a
`usernameTokens` companion (^3) with the handle pre-split on `._-`
(`"ahmad.rashid"` → `"ahmad rashid"`, so searching just `rashid` matches —
the standard analyzer keeps `letter.letter` as one token), `displayName`
(^3), `fname`/`lname` (^2), `bio`, `academicTitle`, `institutionName`,
`role`, `followerCount`, `createdAt`.

**Ranking:** `BM25 × (1 + log1p(followerCount))` — the constant entity
baseline means an account is never buried for being old or unfollowed, and
`log1p` means popularity breaks ties without letting a huge account drown an
exact handle match.

**Privacy:**
- **Emails are not indexed** — same rule as the Postgres search. Searching
  an address finds nothing; use the exact-email lookup endpoint instead.
- **Locked (private) profiles remain discoverable** by name/handle — every
  indexed field is public-profile data (standard private-account behavior).
- **Soft-deleted / disabled accounts are removed** from the index on
  deletion, and the indexer double-checks account liveness on every write.

**Hit mapping:** `titlePreview` = displayName (fallback `fname lname`),
`authorUsername` = the account's handle.

**Freshness:** indexed on registration, identity change (name/username),
profile update (displayName/bio/title/institution), and follow/unfollow
(to refresh `followerCount`). Writes are async and deferred to
**after the DB transaction commits** — see
[indexing-and-reindex.md](indexing-and-reindex.md).

## 2. Dedicated — `GET /api/v1/users/search`

Unchanged, and still the canonical people-picker API. Full reference:
[../user/search.md](../user/search.md). Summary of the mechanism:

1. **Postgres full-text search** — GIN index over
   `to_tsvector('simple', username ‖ fname ‖ lname ‖ profile_bio)` matched
   with `websearch_to_tsquery`, ranked by `ts_rank_cd`, capped at the top
   200 matches. `O(log n + m)`.
2. **Trigram fuzzy fallback** — when FTS finds nothing and the query is ≥3
   chars, `pg_trgm` similarity over username/fname/lname catches typos.
3. **Prefix matcher** — queries under 3 chars use mention-style prefix
   ranking (username-prefix first).

The ranked ids then hydrate in **one active-user batch with the profile
join-fetched** (`findActiveWithProfileByIdIn`) — a single round-trip, no
per-row lazy profile loads — before the rank order is re-applied.

Blank `q` lists all active users (plain paging);
`eligibleContributor=true` restricts to RESEARCHER/SCHOLAR.

## Choosing between them

- Search box in the app header → **global** (`types=USER` or no filter).
- Research co-author picker, admin user lookup, any flow that needs
  `Page<UserResponse>` with roles/badges → **dedicated**.
- Composer `@…` typeahead → **mention suggest**.

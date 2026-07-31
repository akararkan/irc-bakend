# Friend Suggestion Algorithm — Specification

Deterministic multi-signal scoring — no ML infrastructure — but shaped
exactly like the production pipelines (candidate generation → signals →
ranking → privacy filtering → diversity), so a learned
P(connect) model can replace the weighted sum later without touching the
pipeline.

Everything below happens inside `FriendSuggestionService.recomputeFor(user)`
(async). Results are persisted to `friend_suggestions_by_user`; reads are a
single partition scan.

---

## Stage 1 — Candidate generation

Six bounded sources, unioned (each fail-open to empty):

| # | Source | Query | Cap |
|---|---|---|---|
| 1 | **GRAPH** — friends-of-friends | walk my follow set, tally per-candidate mutuals | 400 friends expanded |
| 2 | **CONTACTS** — address-book matches | hash join: my `CONTACT` rows ∩ others' `IDENTITY` rows | — |
| 3 | **MESSAGING** — DM peers | peers of my ACTIVE `DIRECT` conversations | — |
| 4 | **GROUPS** — community overlap | co-members of my ACTIVE `GROUP` conversations, with shared-group counts | 200 rows |
| 5 | **INTERACTIONS** — engaged authors | my `user_author_affinity` partition (authors I view/like/comment/save/share) | 500 rows |
| 6 | **AFFILIATION** — colleagues | active users with my exact `institutionName` (case-insensitive) | 50 rows |

Self and already-followed users are removed. If the union exceeds **300**,
strong-signal candidates (contacts / DM / groups) are kept unconditionally
and the friends-of-friends tail is trimmed by mutual count.

## Stage 2 — Bulk signal loads

Per recompute (not per candidate): users + profiles
(`findActiveWithProfileByIdIn` — join-fetched so avatars/roles are real),
profiles with specializations + topics (fetch-joined, no N+1), my own
profile, bidirectional contact set, blocked / restricted / dismissed sets.

## Stage 3 — Scoring

```
score = Σ signal contributions + quality
```

| Signal | Contribution | Rationale |
|---|---|---|
| mutual follows | `3.0 × min(mutuals, 15)` | classic strongest predictor; capped so mega-hubs don't dominate |
| contact match | `+12.0` | best cold-start signal — they're in your address book |
| bidirectional contact | `+6.0` extra | you're in each other's address books |
| DM thread exists | `+10.0` | communication history strongly predicts connection |
| shared groups | `2.5 × min(groups, 4)` | shared communities |
| engagement affinity | `1.5 × ln(1 + interactions)` | interest graph (same counter the feed ranker uses); log-damped |
| same institution | `+4.0` | workplace/university matching (academic network) |
| same location | `+2.5` | geographic proximity (profile-declared, coarse — privacy-safe) |
| shared specializations | `1.5 × min(topics, 3)` | shared interest topics |
| same content language | `+0.5` | weak contextual signal |
| academic badge (RESEARCHER/SCHOLAR/ADMIN) | `+0.75` | profile quality |
| completed profile (bio + avatar) | `+0.75` | filters throwaway accounts |

**Threshold:** candidates below **2.0** are not stored — a lone weak signal
(language match, quality alone) never produces a suggestion.

**Reason label:** the top ≤3 contributing signal labels, joined with "·" —
e.g. `4 mutual follows · in your contacts · same institution`. Stored on
the row, surfaced verbatim by the API.

## Stage 4 — Privacy & safety filtering

Dropped outright (bulk queries, both directions where applicable): blocked,
restricted, **dismissed** (`suggestion_dismissals` — explicit negative
feedback, permanent), deleted accounts, **locked profiles** (unfollowable
by rule — see `UserSocialServiceImpl.follow`). Contact matching itself is
privacy-preserving: only client-side SHA-256 hashes are ever uploaded, and
`DELETE /users/contacts` wipes them.

## Stage 5 — Diversity + persistence

The top 50 by score are stored, with one guarantee: **every candidate
source that produced at least one surviving candidate is represented inside
the top 20** (its best candidate is swapped in for the weakest head entry
if needed). Prevents a wall of one signal type (e.g. all
friends-of-friends) from hiding a fresh contact match.

Stored score = `round(score × 10)` (the table's clustering `int`).

---

## Recompute triggers & cost

Triggered on: manual recompute, follow, unfollow, contact sync/clear.
All async; the FoF walk dominates cost at
`O(follows_expanded × avg_following)` reads, every other source is a single
bounded query. No scheduled batch yet — an inactive user's list refreshes
on their next graph/contact mutation or manual recompute.

## Future work

- Scheduled nightly refresh for active users (staleness bound).
- Profile-visit / search-history signals (needs a visit-tracking surface).
- Phone-number identity hashes (lights up when phone signup exists).
- Story-view and co-appearance signals.
- Learned P(connect) model over the same feature vector.

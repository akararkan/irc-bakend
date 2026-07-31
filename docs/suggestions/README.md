# Friend Suggestions — "People You May Know"

The friend-suggestion engine answers *"which users is this person most
likely to know or want to connect with?"* using the standard multi-stage
pipeline of modern platforms:

```
Candidate Generation → Signal Collection → Scoring
        → Privacy & Safety Filtering → Diversity → Delivery
```

It replaced the single-signal engine (friends-of-friends mutual count only)
with a **six-source hybrid** that combines the platform's whole relationship
surface, while keeping the same storage and sub-millisecond read path
(one Cassandra partition scan of `friend_suggestions_by_user`).

**This directory is the canonical suggestions documentation.**

| Doc | Contents |
|---|---|
| [algorithm.md](./algorithm.md) | Full pipeline spec — candidate sources, every signal weight, filters, diversity rule, cost budget |
| [api-reference.md](./api-reference.md) | Every suggestion API analyzed — reads, dismissal, contact sync, recompute triggers |

---

## Candidate sources

| Source | Signal | Where it comes from |
|---|---|---|
| `GRAPH` | friends-of-friends mutual count | follow graph walk (Postgres `user_follows`) |
| `CONTACTS` | hashed address-book match (± bidirectional) | `user_contact_hashes` (new — client-side SHA-256, server never sees raw contacts) |
| `MESSAGING` | existing DM thread | ACTIVE `DIRECT` conversations |
| `GROUPS` | shared group memberships | ACTIVE `GROUP` conversation co-members |
| `INTERACTIONS` | you engage with their content | `user_author_affinity` interest graph (shared with the feed ranker) |
| `AFFILIATION` | same institution | `UserProfile.institutionName` |

Plus profile-overlap scoring signals (location, topic specializations,
content language) and quality nudges (academic badge, completed profile).

## Negative signals (all enforced)

- **blocks** (either direction) — excluded
- **restrictions** (either direction) — excluded
- **dismissed suggestions** — new `suggestion_dismissals` table; "don't show
  me this person" is permanent across recomputes
- **locked profiles** — excluded (they can't be followed anyway)
- **deleted accounts** — excluded

## Key components

| Component | File | Role |
|---|---|---|
| `FriendSuggestionService` | `app/post/cassandra/service/FriendSuggestionService.java` | The pipeline (rewritten) |
| `ContactMatchService` | `app/user/service/ContactMatchService.java` | Hashed contact sync + matching + identity backfill |
| `WhoToFollowService` | `app/post/cassandra/service/WhoToFollowService.java` | Popular/verified fallback for cold graphs (unchanged, dismiss now persistent) |
| `UserContactHash` | `app/user/entity/UserContactHash.java` | CONTACT + IDENTITY hash rows (Postgres) |
| `SuggestionDismissal` | `app/user/entity/SuggestionDismissal.java` | Persistent negative feedback (Postgres) |
| `friend_suggestions_by_user` | Cassandra | Precomputed store, score DESC clustering (unchanged) |

## Recompute triggers

1. `POST /api/v1/posts/suggestions/recompute` (manual / onboarding)
2. **follow / unfollow** (new — the graph changed)
3. **contact sync / clear** (new — matches surface immediately)

All async on the app executor; a recompute failure never breaks the caller.

## Storage notes

- Stored `score` is ×10 fixed-point (the Cassandra clustering column is an
  `int`); the detailed API returns the real double.
- `user_contact_hashes` and `suggestion_dismissals` are new Postgres tables
  (auto-created by `ddl-auto: update`); an async startup backfill writes
  IDENTITY hashes for existing accounts so they're contact-matchable.
- Fixed along the way: the Cassandra dismiss `DELETE` previously omitted the
  `score` clustering column and failed at runtime; both dismiss paths now
  read-then-delete with the full primary key.

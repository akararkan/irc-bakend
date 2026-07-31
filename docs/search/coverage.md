# Search Coverage Matrix

The entity-by-entity answer to "can I search this?" — including what is
deliberately *not* searchable and why. This is the checklist that keeps
"every entity has the search it deserves" true as the platform grows.

## Searchable entities

| Entity | Surface(s) | Mechanism | Doc |
|---|---|---|---|
| Post (TEXT/EMBEDDED/VOICE/REPOST) | `types=POST` | ES `irc-posts` | [global-search.md](global-search.md) |
| Reel | `types=REEL` | ES `irc-posts` (`postType` filter) | [global-search.md](global-search.md) |
| Question | `types=QUESTION` | ES `irc-qna` | [qna.md](qna.md) |
| Answer + reanswer | `types=ANSWER` | ES `irc-answers`, accepted boost, `parentId` | [qna.md](qna.md) |
| Research | `types=RESEARCH` | ES `irc-research` | [global-search.md](global-search.md) · [tag-filter search](../research/feeds-discovery.md) |
| User / profile | `types=USER` · `/users/search` · `/mentions/suggest` | ES + Postgres FTS/trigram | [users.md](users.md) |
| Channel (public) | `types=CHANNEL` · `/channels/discover` · by-handle | ES `irc-channels` + LIKE fallback | [channels.md](channels.md) |
| Sound (approved) | `types=SOUND` · `/sounds/search` | ES `irc-sounds` | [sounds.md](sounds.md) |
| Chat message | `/conversations/{id}/messages/search` · `/messaging/search` | ES `irc-chat-messages`, membership-scoped | [../chat/search.md](../chat/search.md) |
| Tag / hashtag | `/tags/search` (prefix) · trending · tag feeds | Cassandra counters/partitions | [../platform/tags.md](../platform/tags.md) |
| Topic / Madhhab | `/topics?q=` · `/madhhabs?q=` | In-memory over tiny tables | [knowledge.md](knowledge.md) |

## Deliberately NOT searchable

| Entity | Why exclusion is the right call |
|---|---|
| **Story / highlight** | Ephemeral (24 h Cassandra TTL) and privacy-graded per-viewer (CLOSE_FRIENDS / FOLLOWERS_ONLY / CHANNEL). Indexing would race the TTL and force per-viewer close-friends resolution inside every query. Stories are a *tray* experience, not a discovery corpus. Highlights inherit the same viewer model. |
| **Private channel / group / DM conversation** | Membership *is* the access model. Private channels are never written to the channel index; groups/DMs are reachable only through your own inbox. |
| **Chat messages in global search** | Searchable — but only behind the membership-scoped chat API. Merging them into the public `/search` merge would mean per-caller filters in a public endpoint; kept separate by design. |
| **Disappearing messages** | Never indexed at all (TTL-gated at the indexer) — a vanishing message must not outlive itself in a search index. |
| **Comments / replies (posts, research)** | Comment text is conversation *within* content, not standalone findable content; the parent post/research is the search target. (Answers are different: an answer is the payload of Q&A.) Revisit only with a concrete product ask. |
| **Notifications, activity log, audit log** | Personal/operational streams — filterable by their own structured endpoints (`/admin/audit` filters userId/operation/date), never text-searched. |
| **Reactions, saves, views, counters, junction rows** | Signals, not content. They *feed* ranking (function_score) instead. |
| **Auth artifacts (tokens, settings, drafts, scheduled messages)** | Internal state; drafts additionally have a dedicated per-conversation endpoint. |
| **Emails (as a search key)** | Privacy rule: no search surface indexes email addresses — exact-email resolution is its own authenticated endpoint. |

## Rules for the next entity you add

1. Is it **user-facing content** someone would type words to find? If not
   (junction/log/counter) — stop, wire it into ranking signals if relevant.
2. Does a user-visible privacy model gate it? Enforce **at write time**
   (don't index what shouldn't surface) *and* keep a query-side belt.
3. Give it: a `*SearchDocument` (+ `createIndex=false`), registration in
   `ElasticsearchIndexInitializer`, an async indexer wired into every
   mutation path, an `EntityType` + index constant + preview mapping in
   `GlobalSearchService`, an admin reindex endpoint, and a page in this
   directory with its complexity noted.
4. If the corpus is tiny and fixed (like [knowledge](knowledge.md)) —
   don't build an index at all.

# Chat Search API — In-Conversation & Cross-Conversation

Full-text search over chat messages, backed by the Elasticsearch `irc-chat-messages`
index (BM25 + `AUTO` fuzziness + phrase-prefix typeahead). Two endpoints: one scoped
to a single conversation (with a bounded Cassandra-scan fallback when the index is
cold), and one that searches every conversation you belong to at once. **Membership
is enforced inside the query itself** — you can never search a room you are not in.

- **Base path:** `/api/v1`
- **Auth:** `Authorization: Bearer <jwt>` on every endpoint (SSE-only endpoints also
  accept `?token=`; search is not SSE). Enforced by `@PreAuthorize` — secure by default.
- **Errors:** unified envelope — see [Error handling](../errors/error-handling.md).
  Switch on `errorCode`.
- **`limit` clamp:** the requested `limit` is clamped into **1..100** (default `20`).
- **Result shape:** `List<MessageResponse>` (full shape in [messages.md](messages.md)),
  **relevance-ranked** by Elasticsearch — *not* chronological. Empty/blank `q` returns
  `[]` with `200`, never an error.

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/conversations/{id}/messages/search?q=&limit=` | Search within one conversation (ES, Cassandra-scan fallback) |
| `GET` | `/messaging/search?q=&limit=` | Search across every conversation you can read (ES-only) |

Related: [conversations.md](conversations.md) ·
[messages.md](messages.md) · [groups.md](groups.md) · [realtime.md](realtime.md).

---

## The index (`irc-chat-messages`)

One Elasticsearch document per **text** message (`ChatMessageDocument`:
`messageId`, `conversationId`, `senderId`, `body`, `type`, `createdAt`).

- **Written async, best-effort** on send/edit — never on the request thread, never
  blocking the send path. `SYSTEM` messages and empty-body (pure-media) messages are
  **not** indexed. Removed from the index on delete.
- Cassandra is the **canonical store**; a lost index write only means a message is
  *temporarily unsearchable*, and in-conversation search still falls back to a scan.
  Writes are wrapped in `EsRetry`.

**Query construction** (both endpoints share `searchMessageIds`):

- `must` — `match` on `body` with `fuzziness("AUTO")` (typo tolerance).
- `should` — `match_phrase_prefix` on `body` (`boost 1.5`) for typeahead ranking.
- `filter` — `terms` over `conversationId` restricted to the **caller's own
  conversation ids** (the membership scope; see below).
- `must_not` — `type = SYSTEM`, so system/timeline messages never surface.

ES returns ranked `messageId`s; the service then hydrates them from Cassandra into
full `MessageResponse` objects, dropping any that fall below the caller's floors.

---

## Membership scoping — you can never search a room you're not in

The conversation scope is applied **inside** the Elasticsearch `terms` filter, not
as a post-filter, so a message from a conversation you don't belong to is never even
a candidate hit:

- **In-conversation** — the caller must first pass `requireReadableMember` (→
  `NOT_A_MEMBER` otherwise); the scope is then the single `{id}`.
- **Cross-conversation** — the scope is `findMyConversationIds(userId)`, i.e. every
  conversation the caller is currently a member of. No membership → `[]`.

There is no way to widen the scope from the request; an attacker passing another
room's id simply gets no results (in-conversation) or is bounded to their own rooms
(cross-conversation).

---

## History & clear floors on results

After ES (or the scan) returns candidate ids, results are filtered through the same
per-conversation visibility floor the read path uses (`floorMessageId`) — a single
high-water mark combining:

- the **hidden-history join floor** — in a history-hidden group, messages sent
  *before you joined* are not searchable, and
- the per-user **"delete for me" clear floor** (`clearedBeforeMessageId`) — anything
  you cleared stays hidden from search too.

Cross-conversation search computes this floor **per conversation** (each room has its
own join floor) and additionally excludes **soft-deleted** conversations
(`deletedAt` set). A deleted message that lingers in a stale index shard is dropped
on hydration. Net effect: search can only ever return messages you are already
allowed to read in that room.

---

## `GET /conversations/{id}/messages/search`

In-conversation full-text search.

```
GET /api/v1/conversations/{id}/messages/search?q=fiqh&limit=20
```

**Auth:** required; caller must be a readable member of `{id}`.

| Param | In | Description |
|-------|----|-----|
| `id` | path (UUID) | The conversation to search |
| `q` | query | Search text; blank → `[]` |
| `limit` | query | Max hits, default `20`, clamped 1..100 |

**Response `200`:** `List<MessageResponse>`, relevance-ranked, floor-filtered.

**Serving path**

1. **Elasticsearch first** — `irc-chat-messages`, scoped to this one conversation.
   If it returns hits, they are hydrated and floor-filtered and returned.
2. **Bounded Cassandra-scan fallback** — used when ES returns no hits *or* throws
   (index cold / unavailable). Walks this conversation's single partition
   **newest→older**, doing a case-insensitive substring match on `body`, bounded by
   **`SEARCH_MAX_BUCKETS = 24`** (~240 days), **`SEARCH_MAX_SCANNED = 3000`** rows,
   and `limit` matches — so it never scans blindly. It skips `SYSTEM`, deleted, and
   below-floor rows. This keeps search working before the index is warm; results
   come back newest-first rather than BM25-ranked.

**Side effects:** none (pure read).

**Errors:** `NOT_A_MEMBER` (403) if you're not a member of the conversation,
`CONVERSATION_NOT_FOUND` (404). See [error-handling.md](../errors/error-handling.md).

---

## `GET /messaging/search`

Cross-conversation full-text search over every conversation you can read.

```
GET /api/v1/messaging/search?q=deadline&limit=20
```

**Auth:** required.

| Param | In | Description |
|-------|----|-----|
| `q` | query | Search text; blank → `[]` |
| `limit` | query | Max hits, default `20`, clamped 1..100 |

**Response `200`:** `List<MessageResponse>`, relevance-ranked across all your rooms,
each hit floor-filtered against *its own* conversation's join/clear floor and
excluded if that conversation was soft-deleted.

**Serving path — ES-only.** There is deliberately **no scan fallback** here (an
all-conversations scan would be unbounded). If Elasticsearch is unavailable, or you
have no memberships, the endpoint returns `[]` rather than erroring.

**Side effects:** none (pure read).

**Errors:** none beyond auth — the scope is implicit, so there is no per-conversation
404/403; an out-of-scope room simply produces no hits. See
[error-handling.md](../errors/error-handling.md).

# Q&A Search — Questions and Answers

Q&A is searchable at **two granularities**, each in its own index:

| `types=` | Index | Finds |
|---|---|---|
| `QUESTION` | `irc-qna` | Questions by title/body/keywords/tags/author |
| `ANSWER` | `irc-answers` | **Answer text** — incl. reanswers (depth-1 replies) |

Both are served exclusively by the unified endpoint
([global-search.md](global-search.md)); there is no `/questions/search`.

**Why a separate answer index (and not answer text folded into the question
doc):** an answer is the thing users actually want to find ("someone
explained this exact issue"), it carries its own ranking signals (accepted
flag, reactions, author), and folding N answer bodies into one question doc
would re-index the whole blob on every answer edit and make per-answer
deep-linking impossible. Separate docs keep both sides cheap and precise.

---

## `types=QUESTION` — the `irc-qna` index

Fields: `title`, `body`, `keywords`, `tags` (keyword), `authorId`,
`authorName`, `authorUsername`, `status`, `answerCount`, `viewCount`,
`saveCount`, `createdAt`.

Ranking extras: `log1p(answerCount) × 2.0` ("more answers = better
question") + `viewCount × 0.5` as an index-scoped tiebreaker, on top of the
standard BM25/recency stack.

Lifecycle: soft-deleted questions are removed from the index; `ARCHIVED`
is filtered at query time. Indexed on create/update, removed on delete.

## `types=ANSWER` — the `irc-answers` index

Fields: `body` (primary), `questionTitle` (^2 — secondary relevance +
context), `questionId` (keyword), `authorId/Name/Username`, `accepted`,
`reactionCount`, `createdAt`.

**Hit shape:** every ANSWER hit carries **`parentId` = the owning question
id** — always present, expand or not — so the client deep-links straight to
the question page (`GET /api/v1/questions/{parentId}`) and scrolls to the
answer. `titlePreview` is the answer body (≤280 chars).

**Ranking extras:**
- **Accepted-answer boost** — a flat `+2.0` weight where `accepted=true`:
  the author-accepted answer outranks sibling answers of equal text
  relevance. (Accept/unaccept re-indexes the answer immediately, so the
  boost tracks the current accept state.)
- `log1p(reactionCount)` — community approval.
- The **entity baseline** (constant 1.0) instead of pure recency decay:
  a five-year-old accepted answer is evergreen knowledge, not stale content.

**Write path:** indexed on answer create (incl. reanswers — the same
depth-1 model as everywhere: [replies are flat](../qna/answers.md)) and
edit; re-indexed on accept/unaccept; removed on answer delete; and when a
whole **question** is deleted, a delete-by-query purges every answer doc of
that question so no orphans linger.

Admin rebuilds: `POST /api/v1/admin/search/questions/reindex` and
`POST /api/v1/admin/search/answers/reindex`
([indexing-and-reindex.md](indexing-and-reindex.md)).

## Example

```
GET /api/v1/search?types=ANSWER&q=deep+sleep+breaks+ablution
```

```json
{
  "results": [
    {
      "contentType": "ANSWER",
      "contentId":  "f8603680-…",
      "parentId":   "15ef97ca-…",          // ← the question to open
      "score":       47.43,
      "titlePreview": "deep sleep breaks ablution according to…",
      "authorUsername": "karwan.zebari",
      "authorName": "Karwan Zebari",
      "createdAt": "2026-07-31T10:49:41Z"
    }
  ]
}
```

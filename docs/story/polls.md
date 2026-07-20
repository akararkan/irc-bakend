# Story Polls API

Instagram-style **two-option polls** attached to a [story](stories.md). One
poll per story; one vote per user per poll; changing sides is allowed and
never double-counts; re-submitting the same choice is idempotent.

**Base paths:** `/api/v1/stories/{storyId}/poll` (poll lifecycle) and
`/api/v1/polls/{pollId}/…` (voting & results).

**Auth:** `Authorization: Bearer <JWT>`. Voting requires auth; reading live
tallies does not.

**Lifetime:** poll rows (question, votes, voter lists, reverse index) inherit
the **parent story's remaining TTL**, so they expire with the story. The live
tally lives in a Cassandra `COUNTER` table which cannot carry a per-write TTL
— an orphaned counter row may linger up to the table-default 24 h, but it is
unreachable once the poll's index rows are gone.

**Errors:** shared envelope — see [Error handling](../errors/error-handling.md).

Sibling docs: [Stories](stories.md) · [Close friends](close-friends.md) ·
[Highlights](highlights.md) · [Realtime (SSE)](realtime.md)

---

## Attach a poll to a story

```
POST /api/v1/stories/{storyId}/poll
```

**Auth:** required. **Story author only** — the author is resolved from the
JWT and compared against the story's owner.

Attaches a single two-option poll to an existing story.

### Path parameters

| Param | Type | Description |
|---|---|---|
| `storyId` | UUID | The story to attach the poll to |

### Request body

```json
{
  "question": "Which usul method do you find more convincing?",
  "optionA": "Istihsan",
  "optionB": "Maslaha"
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `question` | string | yes | The poll prompt |
| `optionA` | string | yes | Label of choice `A` |
| `optionB` | string | yes | Label of choice `B` |

### Response — `200 OK`

```json
{
  "storyId": "0c9d8e7f-6a5b-4c3d-9e2f-1a0b9c8d7e6f",
  "pollId": "5a4b3c2d-1e0f-4a9b-8c7d-6e5f4a3b2c1d",
  "question": "Which usul method do you find more convincing?",
  "optionA": "Istihsan",
  "optionB": "Maslaha",
  "authorId": "6f1a2b3c-4d5e-4f60-8a71-92b3c4d5e6f7",
  "createdAt": "2026-07-20T09:20:00Z"
}
```

| Field | Type | Description |
|---|---|---|
| `storyId` | UUID | Parent story |
| `pollId` | UUID | Server-generated poll id — use it for all `/polls/{pollId}/…` calls |
| `question` / `optionA` / `optionB` | string | As submitted |
| `authorId` | UUID | Poll (= story) owner |
| `createdAt` | ISO-8601 instant | Attach time |

### Errors

| Status | errorCode | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid bearer token |
| 403 | `FORBIDDEN` | Caller is not the story author — **also returned when the story does not exist / already expired** (`"Not the story author"`) |
| 400 | `MALFORMED_JSON` | Body is not valid JSON |

### Side effects

- Writes the poll row (`poll_by_story`) and a reverse index row
  (`poll_by_id`: `pollId → storyId, authorId, expiresAt`), both TTL'd to the
  story's remaining lifetime. The reverse index powers the realtime tally
  push and the author-only voter list; its write is best-effort — if it
  fails, the poll still works but loses those two features.

---

## Get the poll attached to a story

```
GET /api/v1/stories/{storyId}/poll
```

**Auth:** none.

Returns the poll attached to a story, if any.

### Path parameters

| Param | Type | Description |
|---|---|---|
| `storyId` | UUID | The story to inspect |

### Response — `200 OK`

Same poll object as the create response above.

### Errors

| Status | errorCode | When |
|---|---|---|
| 404 | — (empty body) | Story has no poll, or the poll expired with its story |
| 400 | `TYPE_MISMATCH` | `storyId` is not a valid UUID |

---

## Cast or change a vote

```
POST /api/v1/polls/{pollId}/vote
```

**Auth:** required.

Casts the caller's vote, or moves it if they voted the other way before.
Re-submitting the same choice is a **no-op** that just returns the latest
tallies.

> The choice travels as a **query parameter**, not a JSON body (an older
> draft of these docs showed `{ "choice": "A" }` — that is wrong).

### Path parameters

| Param | Type | Description |
|---|---|---|
| `pollId` | UUID | The poll to vote on |

### Query parameters

| Param | Type | Required | Description |
|---|---|---|---|
| `choice` | string | yes | Exactly `A` or `B` (case-sensitive) |

### Example

```bash
curl -X POST "https://api.irc.example/api/v1/polls/5a4b3c2d-1e0f-4a9b-8c7d-6e5f4a3b2c1d/vote?choice=A" \
  -H "Authorization: Bearer $TOKEN"
```

### Response — `200 OK`

```json
{ "choice": "A", "voteA": 18, "voteB": 9 }
```

| Field | Type | Description |
|---|---|---|
| `choice` | string | The caller's (new) pick |
| `voteA` | long | Live tally for option A, including this vote |
| `voteB` | long | Live tally for option B |

### Errors

| Status | errorCode | When |
|---|---|---|
| 401 | `AUTH_UNAUTHORIZED` | No / invalid bearer token |
| 400 | `ILLEGAL_ARGUMENT` | `choice` is anything other than `A` or `B` (`"Choice must be A or B"`) |
| 400 | `MISSING_PARAMETER` | `choice` query param omitted |

### Side effects

- Switching sides deletes the old voter row and decrements the old counter
  before writing the new ones — the tally never double-counts.
- Vote rows are TTL'd to the parent story's remaining lifetime.
- Pushes a **`poll_vote_cast`** event to the **poll author only** on the
  [story-tray SSE stream](realtime.md#story-tray-stream), carrying `pollId`,
  `voteA`, `voteB`, `voteTotal` — the StoryEditor's results pane updates
  without polling. Best-effort: the vote is already persisted if the push
  fails. No notification row is created.

> **Consistency note:** votes are deliberately **not** guarded by LWT — at
> poll volumes a rare stale double-tap is cheaper than a Paxos round per
> write. The counter is the authoritative tally; the per-user vote row is
> what the user sees as "their pick".

---

## My vote

```
GET /api/v1/polls/{pollId}/vote/me
```

**Auth:** optional (see below).

Tells the poll UI what the caller already picked.

### Path parameters

| Param | Type | Description |
|---|---|---|
| `pollId` | UUID | The poll to inspect |

### Response — `200 OK` (voted)

```json
{
  "pollId": "5a4b3c2d-1e0f-4a9b-8c7d-6e5f4a3b2c1d",
  "voterId": "3e2d1c0b-9a87-4654-b321-0fedcba98765",
  "choice": "A",
  "votedAt": "2026-07-20T10:05:33Z"
}
```

### Response — `200 OK` (anonymous caller)

```json
{ "pollId": "5a4b3c2d-1e0f-4a9b-8c7d-6e5f4a3b2c1d" }
```

| Field | Type | Description |
|---|---|---|
| `pollId` | UUID | Echo of the path parameter |
| `voterId` | UUID | Present only for authenticated callers |
| `choice` | string \| null | `A` / `B`; the contract is `null` when the caller has not voted |
| `votedAt` | ISO-8601 instant | Present only when voted |

### Response — `200 OK` (authenticated, has not voted)

```json
{
  "pollId": "5a4b3c2d-1e0f-4a9b-8c7d-6e5f4a3b2c1d",
  "voterId": "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "choice": null
}
```

### Errors

| Status | errorCode | When |
|---|---|---|
| 400 | `TYPE_MISMATCH` | `pollId` is not a valid UUID |

---

## Live results

```
GET /api/v1/polls/{pollId}/results
```

**Auth:** none — mirrors what the poll UI shows to any viewer.

### Path parameters

| Param | Type | Description |
|---|---|---|
| `pollId` | UUID | The poll to tally |

### Response — `200 OK`

```json
{ "choice": null, "voteA": 18, "voteB": 9 }
```

`choice` is always `null` here (it is only populated on the vote response).
An unknown or expired `pollId` returns zero tallies rather than 404:

```json
{ "choice": null, "voteA": 0, "voteB": 0 }
```

### Errors

| Status | errorCode | When |
|---|---|---|
| 400 | `TYPE_MISMATCH` | `pollId` is not a valid UUID |

For push-based live tallies (author only), prefer the `poll_vote_cast` SSE
event over polling this endpoint — see [Realtime](realtime.md).

---

## Voter list per side (author only)

```
GET /api/v1/polls/{pollId}/voters/{choice}
```

**Auth:** required. **Poll author only** — ownership is resolved through the
`poll_by_id` reverse index, so the path only needs `pollId`.

Lists who voted for one side, newest first.

### Path parameters

| Param | Type | Description |
|---|---|---|
| `pollId` | UUID | The poll |
| `choice` | string | `A` or `B` — which side's voters to list |

### Query parameters

| Param | Type | Default | Description |
|---|---|---|---|
| `pageSize` | int | `50` | Maximum rows returned (first page only) |

### Response — `200 OK`

```json
[
  {
    "pollId": "5a4b3c2d-1e0f-4a9b-8c7d-6e5f4a3b2c1d",
    "choice": "A",
    "votedAt": "2026-07-20T10:05:33Z",
    "voterId": "3e2d1c0b-9a87-4654-b321-0fedcba98765"
  }
]
```

| Field | Type | Description |
|---|---|---|
| `pollId` | UUID | Echo of the path |
| `choice` | string | Echo of the path |
| `votedAt` | ISO-8601 instant | When the vote landed (clustering key, DESC) |
| `voterId` | UUID | Who voted |

### Errors

| Status | errorCode | When |
|---|---|---|
| 401 / 403 | `AUTH_UNAUTHORIZED` / `ACCESS_DENIED` | Anonymous caller (`@PreAuthorize("isAuthenticated()")`) |
| 403 | `ACCESS_FORBIDDEN` | Authenticated but not the poll's author (`"Only the poll's author can list voters"`) |
| 403 | `ACCESS_FORBIDDEN` | Poll created before the `poll_by_id` reverse index existed — ownership can't be proven, treated as not-owner |
| 400 | `TYPE_MISMATCH` | `pollId` is not a valid UUID |

---

## Related

- The parent stories: [stories.md](stories.md)
- Live tally pushes (`poll_vote_cast`): [realtime.md](realtime.md)

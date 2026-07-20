# Research API — Realtime (SSE)

Live per-research event stream: reactions, comments, counters, and lifecycle changes pushed
to every open detail page.

**Base path:** `/api/v1/researches`

Sibling documents:

- [Lifecycle](./research.md) — the actions that emit lifecycle events
- [Media, sources & contributors](./media-sources-contributors.md)
- [Social interactions](./social.md) — the actions that emit engagement events
- [Feeds & discovery](./feeds-discovery.md)

---

## Subscribe

```
GET /api/v1/researches/{researchId}/stream
```

**Auth:** optional — anonymous-safe. The subscriber identity comes from the authenticated
**principal**, resolved by the standard JWT filter from the `access_token` cookie or the
`Authorization: Bearer <token>` header. Browsers using `EventSource` cannot set headers, so
cookie auth is the practical path there. An unauthenticated subscriber still receives every
event; authentication matters for [actor suppression](#actor-suppression).

**Content type:** `text/event-stream`. The connection has no server-side timeout; stale
emitters are pruned on the 25-second heartbeat or on any failed send. Multi-tab safe — each
tab is its own subscription. Events fan out cluster-wide over Redis pub/sub
(`irc:research:{researchId}`), so it doesn't matter which app instance handled the write.

| Param | In | Required | Notes |
|---|---|---|---|
| `researchId` | path | yes | UUID of the research to watch |

### Connection lifecycle events

On subscribe, a lowercase `connected` handshake fires:

```
event: connected
data: {"researchId":"R-uuid","viewerId":"U-uuid","timestamp":"2026-05-21T10:15:00.012"}
```

`viewerId` is the string `"anonymous"` for unauthenticated subscribers.

Every **25 seconds** a lowercase `heartbeat` keeps proxies from idling the connection out:

```
event: heartbeat
data: {"timestamp":"2026-05-21T10:15:25.000"}
```

### Domain events

Every other SSE `event:` name is the **uppercase enum name** from
`ResearchRealtimeEventType` — exactly as written below (unlike the story tray, names are
*not* lowercased):

| Event | Emitted when |
|---|---|
| `REACTION_ADDED` | A user liked the paper (also re-broadcast on an idempotent repeat so UIs reconcile) |
| `REACTION_CHANGED` | Reserved (single LIKE type today — never fires) |
| `REACTION_REMOVED` | A user removed their like |
| `COMMENT_CREATED` | New top-level comment |
| `COMMENT_EDITED` | Comment author edited the body |
| `COMMENT_DELETED` | Comment deleted (author or research owner) |
| `REPLY_CREATED` | New depth-1 reply |
| `COMMENT_REACTION_ADDED` | LIKE on a comment |
| `COMMENT_REACTION_CHANGED` | Reserved (never fires today) |
| `COMMENT_REACTION_REMOVED` | LIKE removed from a comment |
| `VIEW_COUNT_UPDATED` | New unique (deduped) view |
| `DOWNLOAD_COUNT_UPDATED` | New counted (deduped) download |
| `SHARE_COUNT_UPDATED` | Share recorded |
| `SAVE_COUNT_UPDATED` | Save **or** unsave (read `saveCount` — no toggle ambiguity) |
| `CITATION_COUNT_UPDATED` | Citation recorded |
| `REACTION_COUNT_UPDATED` | Reserved — reaction totals ride on `REACTION_ADDED` / `REACTION_REMOVED` |
| `COMMENT_COUNT_UPDATED` | Reserved — comment totals ride on the `COMMENT_*` events |
| `RESEARCH_UPDATED` | Metadata edit, **and** the unpublish / archive / retract status transitions |
| `RESEARCH_DELETED` | Paper hard-deleted |
| `RESEARCH_PUBLISHED` | `DRAFT → PUBLISHED` — manual **or** [scheduled auto-publish](./research.md#scheduled-publishing) |

---

## Event payload — `ResearchRealtimeEvent`

One flat JSON shape covers every event; **null fields are omitted from the wire**, so read
whichever fields are present and dispatch on `eventType`. There is no `data` wrapper.

```json
{
  "eventType":      "COMMENT_CREATED",
  "researchId":     "R-uuid",

  "actorId":        "U-uuid",
  "actorUsername":  "ali",
  "actorAvatarUrl": "https://cdn…/ali.jpg",

  "commentId":        "C-uuid",
  "parentCommentId":  null,
  "comment":          { "…": "full CommentResponse — see social.md" },
  "body":             "Beautiful methodology.",
  "mediaUrl":         null,
  "mediaType":        null,
  "mediaThumbnailUrl": null,

  "status":               null,
  "reactionType":         null,
  "previousReactionType": null,

  "reactionCount": 213, "commentCount": 32, "shareCount": 7, "saveCount": 58,
  "viewCount": 1247, "downloadCount": 89, "citationCount": 4,
  "commentReplyCount": null,
  "commentReactionCount": null,
  "commentLikeCount": null,

  "timestamp": "2026-05-21T10:15:00.012"
}
```

| Field | Present on | Notes |
|---|---|---|
| `eventType` / `researchId` | all | Dispatch keys |
| `actorId` / `actorUsername` / `actorAvatarUrl` | most | Who did it (absent on anonymous views/shares) |
| `commentId` | `COMMENT_*`, `REPLY_*` | Target comment |
| `parentCommentId` | reply + comment-reaction events | Root comment id; `null` for top-level |
| `comment` | `COMMENT_CREATED`, `REPLY_CREATED`, `COMMENT_EDITED` | The **full, freshly mapped `CommentResponse`** — patch the row in place, no refetch. Its `myReaction` is neutral (`null`); resolve per-viewer. |
| `body`, `mediaUrl`, `mediaType`, `mediaThumbnailUrl` | comment events | Snippet + inline media |
| `status` | `RESEARCH_PUBLISHED` / `RESEARCH_UPDATED` / `RESEARCH_DELETED` | Fresh `ResearchStatus` name — patch the status pill in place (`"DRAFT"` on unpublish, `"ARCHIVED"`, `"RETRACTED"`, …) |
| `reactionType` / `previousReactionType` | reaction events | Always `"LIKE"` today |
| `reactionCount` … `citationCount` | counter-bearing events | Fresh **absolute** counters after the action |
| `commentReplyCount` | `REPLY_CREATED`, `COMMENT_DELETED` | Parent's fresh reply count |
| `commentReactionCount` | `COMMENT_REACTION_*` | Fresh total reactions on the comment |
| `commentLikeCount` | `COMMENT_REACTION_*` | **Deprecated** alias of `commentReactionCount` — kept on the wire for older clients |
| `timestamp` | all | Server time of the event |

---

## Counter semantics

Events signal **what happened** (`eventType` + actor + target); counter state reconciles
from the authoritative values the server sends:

- Counter fields carry the **post-action absolute value** — **set** your local counter from
  them, never apply a local `+1`/`-1` on top. `SAVE_COUNT_UPDATED` therefore has no
  save-vs-unsave ambiguity: just read `saveCount`.
- Counter-bearing events (reactions, saves, shares, citations, comment add/delete) populate
  **all seven** research counters at once, so any of them can be patched from any such event.
- Events are broadcast **after the database commit** (`afterCommit`), so a subscriber never
  sees a value the DB is about to roll back.
- After an SSE gap (disconnect, tab sleep, missed events), reconcile via REST — re-fetch
  [`GET /api/v1/researches/{id}`](./research.md#get-by-id) (or the feed page) for the
  authoritative counters, then resume patching from the stream.

## Actor suppression

When the subscriber is authenticated, the server **does not echo the subscriber's own
actions back** to them: any event whose `actorId` equals the subscription's viewer id is
skipped for that subscription. The actor already has the authoritative result in the HTTP
response of the action itself — this is how optimistic UIs avoid double-counting. The check
is per-viewer, so a second tab of the same user is suppressed too. Anonymous subscriptions
receive every event.

## Client sketch

```js
const es = new EventSource(`/api/v1/researches/${id}/stream`); // cookie auth

es.addEventListener("connected", e => console.log(JSON.parse(e.data)));
es.addEventListener("heartbeat", () => {});

for (const name of ["REACTION_ADDED", "REACTION_REMOVED", "SAVE_COUNT_UPDATED",
                    "SHARE_COUNT_UPDATED", "VIEW_COUNT_UPDATED",
                    "DOWNLOAD_COUNT_UPDATED", "CITATION_COUNT_UPDATED"]) {
  es.addEventListener(name, e => setCounters(JSON.parse(e.data))); // absolute values
}

es.addEventListener("COMMENT_CREATED", e => prependComment(JSON.parse(e.data).comment));
es.addEventListener("REPLY_CREATED",   e => attachReply(JSON.parse(e.data)));
es.addEventListener("COMMENT_EDITED",  e => patchComment(JSON.parse(e.data).comment));
es.addEventListener("COMMENT_DELETED", e => removeComment(JSON.parse(e.data)));
es.addEventListener("RESEARCH_PUBLISHED", e => setStatus(JSON.parse(e.data).status));
es.addEventListener("RESEARCH_UPDATED",   e => setStatus(JSON.parse(e.data).status));
es.addEventListener("RESEARCH_DELETED",   () => leavePage());

es.onerror = () => { /* EventSource auto-reconnects; refetch the paper to reconcile */ };
```

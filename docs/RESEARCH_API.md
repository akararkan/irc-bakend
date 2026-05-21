# Research Package — Full API Documentation

This is the complete reference for everything under `ak.dev.irc.app.research`
— the academic-publication module (papers, datasets, citations, peer
engagement).

## Recent additions

| Section | What changed |
|---------|--------------|
| [§16 — Saves](#16-saves-bookmarks) | `/me/saved` and `/me/saved/collection` now populate `savedAt` on each row. |
| [§26 — `ResearchSummaryResponse` shape](#26-dtos) | Added nullable `savedAt` field (bookmark time). |
| [§21 — Notifications + Activity](#21-notifications) | Research mutations now record activity rows (`RESEARCH_PUBLISHED` and `RESEARCH_SAVED` are new). |

It covers:

- [Domain model overview](#1-domain-model-overview)
- [IRC identifier & DOI](#2-irc-identifier--doi)
- [Research CRUD](#3-research-crud)
- [Lifecycle (publish / unpublish / archive / retract)](#4-lifecycle)
- [Video promo](#5-video-promo)
- [Cover image](#6-cover-image)
- [Media files (gallery)](#7-media-files)
- [Sources / citations on the paper](#8-sources--citations)
- [Contributors (co-authors, advisors, ...)](#9-contributors)
- [Reads (by id, slug, share-token)](#10-reads)
- [Feeds (public, following, by-researcher)](#11-feeds)
- [Researcher dashboard](#12-researcher-dashboard)
- [Search & tags](#13-search--tags)
- [Reactions (paper + comment)](#14-reactions)
- [Comments & replies](#15-comments--replies)
- [Saves (bookmarks) & collections](#16-saves-bookmarks)
- [Views](#17-views)
- [Downloads](#18-downloads)
- [Share link](#19-share-link)
- [Citations](#20-citations)
- [Notifications (kinds emitted by Research)](#21-notifications)
- [Realtime (SSE)](#22-realtime-sse)
- [Enums](#23-enums)
- [JPA entities](#24-jpa-entities)
- [Cassandra mirror tables](#25-cassandra-mirror-tables)
- [DTOs](#26-dtos)

All endpoints live under `/api/v1/researches/...`. Most mutations require one
of `SCHOLAR`, `RESEARCHER`, `ADMIN`, `SUPER_ADMIN`. The authenticated user is
extracted from the JWT principal — any body-supplied user/researcher ids are
ignored.

---

## 1. Domain model overview

The research module is an academic-publication engine. The hierarchy is:

```
Research
 ├── Contributors        (co-authors, advisors, translators — RESEARCHER/SCHOLAR users)
 ├── Tags                (lowercase, unique per research, used for discovery)
 ├── Media files         (PDF, figures, datasets, video, audio, ...)
 ├── Sources             (URL / DOI / ISBN / uploaded file / manual citation)
 ├── Comments            (nested, depth-1 by convention; mutable, hideable)
 │    └── CommentReaction (single LIKE)
 ├── Reactions           (single LIKE on the paper itself)
 ├── Saves               (per (research, user), with collection_name)
 ├── Views               (per (research, user), ever — replaces Redis 1h dedupe)
 └── Downloads           (append-only ledger; can be anonymous)
```

Key design rules:

- **Single reaction type** — `ReactionType` is `LIKE` only.
- **Soft delete** for `Research` and `ResearchComment` via `deletedAt`.
- **Optimistic locking** — `Research.version` is `@Version` so concurrent
  edits don't lose updates.
- **Slug + IRC ID + DOI + shareToken** — each paper has *four* identifiers
  (see [§2](#2-irc-identifier--doi)).
- **Denormalised counters** on `Research` (`viewCount`, `downloadCount`,
  `reactionCount`, `commentCount`, `saveCount`, `shareCount`,
  `citationCount`) and on `ResearchComment` (`likeCount`, `replyCount`).
  Atomic updates via repository JPQL — entity setter + save was racy.
- **`CounterCache`** mirrors the denormalised counts in Redis so the next
  read is hot.
- **Per-research realtime SSE channel** plus cross-instance Redis pub/sub.
- **`@mention`** scanning fires on the comment body.
- **Block-aware** in both directions — feeds drop researches by blocked
  authors, and reaction / comment paths refuse to cross a block edge.
- **RabbitMQ event publishing inside `TransactionSynchronization.afterCommit`**
  so a rollback never leaks a notification.

---

## 2. IRC identifier & DOI

Each research paper has four identifiers — all immutable once assigned:

| Identifier | Format | Purpose |
|------------|--------|---------|
| `id` | UUID | Internal primary key |
| `slug` | `kebab-case-title-{shortHash}` | Pretty SEO URL |
| `ircSequenceNumber` + `ircId` | `IRC-{YEAR}-{6-digit}` (e.g. `IRC-2026-000042`) | Official paper identifier issued by IRC. Drawn from DB sequence `research_irc_seq` on creation. |
| `doi` | `10.{prefix}/irc.{year}.{sequence}` | Auto-generated on publish unless the researcher manually supplied one. |
| `shareToken` | random 32-char | Powers the short link `/r/{shareToken}` |

The IRC identifier is the canonical citation identifier on the platform —
it's shown on every card and resolves to the paper detail page.

---

## 3. Research CRUD

### Create — single multipart call

`POST /api/v1/researches`  ·  `multipart/form-data`

Roles: `SCHOLAR`, `RESEARCHER`, `ADMIN`, `SUPER_ADMIN`.

Multipart parts:

| Part | Required | Description |
|------|----------|-------------|
| `data`    | yes | JSON body — `CreateResearchRequest` |
| `files[]` | no  | One or more binary files (positions match `mediaFiles[]` metadata by index) |

`CreateResearchRequest`:

```json
{
  "title":        "...",        // required, ≤500
  "description":  "...",        // required, ≤50000
  "abstractText": "...",        // required, ≤5000
  "keywords":     "...",        // ≤2000
  "citation":     "...",        // ≤5000
  "doi":          "...",        // ≤255 (optional override)
  "visibility":   "PUBLIC",     // defaults to PUBLIC
  "scheduledPublishAt": null,   // ISO datetime — null means publish-on-demand
  "commentsEnabled":  true,
  "downloadsEnabled": true,
  "tags":         ["islamic-finance", "fiqh"],   // required, 1..30
  "sources":      [SourceRequest, ...],          // inline sources
  "mediaFiles":   [MediaUploadMetadata, ...],    // matched to files[] by index
  "contributors": [ContributorRequest, ...]      // optional co-authors
}
```

Side effects on create:

- New row in `researches` with `ircSequenceNumber` from the sequence and
  `ircId = "IRC-{YEAR}-{6-digit}"`.
- Tags lower-cased and de-duplicated → `research_tags` rows.
- Inline `sources` and `contributors` persisted.
- For each `files[i]` part: uploaded to R2 (Cloudflare), `ResearchMedia`
  row inserted with `mediaFiles[i]` metadata (caption / altText / order)
  if provided.
- Slug generated from title.
- Status starts `DRAFT` — paper is not searchable until published.

Curl example:

```bash
curl -X POST https://api.irc.example.com/api/v1/researches \
  -H "Authorization: Bearer <token>" \
  -F 'data={"title":"...","tags":["ai"],...};type=application/json' \
  -F 'files[]=@paper.pdf;type=application/pdf' \
  -F 'files[]=@figure1.png;type=image/png'
```

### Update

`PATCH /api/v1/researches/{id}`  ·  JSON `UpdateResearchRequest`.

Researcher-only (or admin). Every field is optional — `null` means "leave
untouched". A non-null `contributors` list **replaces** the entire
contributor list (PATCH semantics relative to the field — full overwrite
for that one collection).

### Delete

`DELETE /api/v1/researches/{id}` — soft delete (sets `deletedAt`).

---

## 4. Lifecycle

All researcher-only.

| Method / Path | Effect |
|---------------|--------|
| `POST /{id}/publish`   | Status `DRAFT|ARCHIVED → PUBLISHED`. Sets `publishedAt = now`. Generates DOI if absent. Publishes `ResearchPublishedEvent` on RabbitMQ; broadcasts `RESEARCH_PUBLISHED`. Indexes in Elasticsearch (`irc-research`). |
| `POST /{id}/unpublish` | `PUBLISHED → DRAFT`. Removes from public feeds. |
| `POST /{id}/archive`   | `PUBLISHED → ARCHIVED`. Stays addressable by id but drops out of feeds. |
| `POST /{id}/retract`   | `PUBLISHED → RETRACTED`. Stays visible with a retraction notice — not deleted. |

### Scheduled auto-publish

`ResearchService.processScheduledPublications()` is invoked by a scheduler
that publishes any `DRAFT` whose `scheduledPublishAt` has passed.

---

## 5. Video promo

A short researcher self-explanation video shown on the paper hero.

| Method / Path | Purpose |
|---------------|---------|
| `POST /{id}/video-promo`  (multipart) | Upload. Parts: `video` (required, mp4/webm/quicktime), `thumbnail` (optional). Duration is extracted server-side via `mp4parser` — client does NOT send `durationSeconds`. If extraction fails the field is stored as `null` and the upload still succeeds. |
| `DELETE /{id}/video-promo`            | Remove. Deletes the R2 object. |

---

## 6. Cover image

| Method / Path | Purpose |
|---------------|---------|
| `POST /{id}/cover-image`  (multipart, part `image`) | Upload / replace. |
| `DELETE /{id}/cover-image`                           | Remove. |

---

## 7. Media files

Post-creation media additions / edits to the paper's gallery. The
`ResearchMedia` table backs this; `MediaType` is inferred from MIME:
`IMAGE`, `VIDEO`, `AUDIO`, `DOCUMENT`, `SPREADSHEET`, `DATASET`, `CODE`,
`ARCHIVE`, `OTHER`.

Base: `/api/v1/researches/{id}/media`

| Method | Path | Purpose |
|--------|------|---------|
| `POST` (multipart) | `` | Add one file. Form fields: `file` (required), `caption`, `altText`, `displayOrder`. Returns `MediaResponse`. |
| `PATCH` | `/{mediaId}` | Update metadata (caption, altText, displayOrder, durationSeconds, widthPx, heightPx). |
| `DELETE` | `/{mediaId}` | Remove file + delete the R2 object. |

---

## 8. Sources / citations on the paper

Sources are stored on `research_sources`. Two ways to add them:

1. **Inline at create / update** — `sources: [SourceRequest...]` in
   `CreateResearchRequest` / `UpdateResearchRequest`.
2. **Edit existing source** — `PATCH /{id}/sources/{sourceId}` with
   `UpdateSourceRequest`.
3. **Upload a file-backed source** — `POST /{id}/sources/{sourceId}/file`
   (multipart `file`) attaches an uploaded file to an existing source row.

`SourceType`: `URL`, `DOI`, `ISBN`, `MEDIA_FILE`, `MANUAL`.

`SourceRequest`:

```json
{
  "sourceType": "DOI",                    // required
  "title": "Smith et al. 2024",           // required, ≤500
  "citationText": "...full citation...",
  "url": "https://doi.org/10.xxxx/xxxxx",
  "doi": "10.xxxx/xxxxx",
  "isbn": "978-...",
  "displayOrder": 0
}
```

---

## 9. Contributors

Named participants other than the corresponding researcher — co-authors,
advisors, reviewers, translators, editors, acknowledged contributors.

`ContributorRole`: `CO_AUTHOR` (default), `ADVISOR`, `REVIEWER`,
`TRANSLATOR`, `EDITOR`, `CONTRIBUTOR`.

Constraints:

- Only the corresponding (owning) researcher may add / remove contributors.
- The target user must have role `RESEARCHER` or `SCHOLAR`.
- Unique on `(research_id, user_id)` — `uk_rcontrib_research_user`.
  Re-adding the same user → `409 CONFLICT`.

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/{id}/contributors` | Add a single contributor. Body: `ContributorRequest`. |
| `PUT`  | `/{id}/contributors` | REPLACE the entire contributor list. Empty list = clear. |
| `PATCH`| `/{id}/contributors/{contributorId}` | Update role / order / note. Body: `UpdateContributorRequest`. |
| `DELETE`| `/{id}/contributors/{contributorId}` | Remove by `contributor_id` (not user-id). |
| `GET`  | `/{id}/contributors` | Public — list contributors (ordered by `displayOrder` ASC). |

`ContributorRequest`:

```json
{ "userId": "<uuid>",
  "role": "CO_AUTHOR",
  "displayOrder": 0,
  "contributionNote": "Statistical analysis" }
```

Side effects on add:

- Publishes a `RESEARCH_CONTRIBUTOR_ADDED` notification to the contributor
  (one-shot, email-eligible).

---

## 10. Reads

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/{id}` | Detail by UUID. |
| `GET` | `/slug/{slug}` | Detail by URL slug. |
| `GET` | `/share/{shareToken}` | Detail by the short share-token. |
| `GET` | `/{id}/contributors` | Contributor list (see [§9](#9-contributors)). |

All three detail endpoints return the same `ResearchResponse` and populate
the viewer-relative fields (`currentUserReacted`, `currentUserSaved`,
`currentUserReactionType`) when authenticated.

Block-aware — viewers in a block edge with the researcher get `404`.

---

## 11. Feeds

`ResearchSummaryResponse` is the lightweight projection used in cards.

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/feed?page=&size=&sort=publishedAt,desc` | Public feed of `PUBLISHED` papers. |
| `GET` | `/feed/following?page=&size=` (auth) | Papers from researchers the user follows. Excludes blocked authors in both directions. |
| `GET` | `/researcher/{researcherId}?page=&size=` | All published papers by one researcher. |

---

## 12. Researcher dashboard

Roles: `SCHOLAR`, `RESEARCHER`, `ADMIN`, `SUPER_ADMIN`.

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/me/drafts?page=&size=` | The viewer's own drafts. |
| `GET` | `/me/all?page=&size=`     | All researches (any status) owned by the viewer. |

---

## 13. Search & tags

### Elasticsearch full-text search

`GET /api/v1/researches/search?q=zakat&page=0&size=20`

BM25-ranked. Response: `{ query, page, size, results: [<UUIDs>] }`. Index
`irc-research` (`ResearchSearchDocument`), updated async on
publish/update/delete.

### Tag filter

`GET /api/v1/researches/search/tags?tags=ai&tags=ethics&page=&size=`

Returns `Page<ResearchSummaryResponse>` of papers carrying any of the
requested tags.

### Trending tags

`GET /api/v1/researches/tags/trending?limit=20`

Returns the most-used tag names across published researches.

---

## 14. Reactions

**Single reaction type — `LIKE`.** Mirrors the post and Q&A packages
("academic not entertainment"). The `ReactRequest` defaults
`reactionType=LIKE` even if omitted.

### On a paper

| Method | Path | Purpose |
|--------|------|---------|
| `POST`   | `/{researchId}/reactions` | React. Body (optional): `{reactionType:"LIKE"}`. Returns `201`. Idempotent at the DB layer; broadcasts the authoritative counters. |
| `DELETE` | `/{researchId}/reactions` | Remove reaction. Returns updated `ResearchResponse` (`currentUserReacted=false`, fresh `reactionCount`). Idempotent. |
| `GET`    | `/{researchId}/reactions/breakdown` | `Map<ReactionType, Long>` — kept for forward-compat although only `LIKE` is currently populated. |

Side effects on react:

- Atomic JPQL `update reaction_count = reaction_count + 1`.
- `CounterCache` mirror update.
- Publishes `ResearchReactedEvent` (`RESEARCH_REACTED`) → notification
  consumer dispatches `PUBLICATION_LIKED` to the researcher.
- Broadcasts `REACTION_ADDED` / `REACTION_REMOVED` with fresh
  `reactionCount`.
- Cassandra mirror: `ResearchReactionByResearchEntity` and
  `ResearchReactionByUserEntity`.

### On a comment

`ResearchCommentReaction` is mapped to the legacy `research_comment_likes`
table; the `reaction_type` column was added by `ddl-auto=update`. Existing
rows with `NULL` reaction_type are treated as `LIKE` for back-compat.

| Method | Path | Purpose |
|--------|------|---------|
| `POST`   | `/{researchId}/comments/{commentId}/reactions` | React. Returns `201`. Block-aware against the comment author. |
| `DELETE` | `/{researchId}/comments/{commentId}/reactions` | Remove reaction. Returns updated `CommentResponse` (with `myReaction=null` and decremented `likeCount`). Idempotent — if no row exists, broadcasts the authoritative count anyway so stale UIs can reconcile. |

Side effects on comment react:

- Atomic `like_count` update on the comment row.
- Publishes `ResearchCommentReactedEvent` (`RESEARCH_COMMENT_REACTED`) →
  notification consumer dispatches `PUBLICATION_COMMENT_REACTED`.
- Broadcasts `COMMENT_REACTION_ADDED` / `_CHANGED` / `_REMOVED` with fresh
  `commentReactionCount` (and the deprecated `commentLikeCount` alias for
  unmigrated clients).
- Cassandra mirror: `ResearchCommentLikeEntity`.

### Deprecated comment endpoints

`likeComment` / `unlikeComment` on the service are `@Deprecated` shims to
`reactToComment(LIKE)` / `removeCommentReaction` — kept idempotent for
back-compat with older clients.

---

## 15. Comments & replies

Nested replies — `parent_id` links a reply to its parent. Depth-1 by
convention (the UI enforces it; the service does NOT auto-hoist, unlike
QnA). Soft-delete via `deletedAt`. Moderator-hide via `isHidden` +
`hiddenBy` + `hiddenAt`.

`research.commentsEnabled` is honoured by `addComment` — disabled paper
rejects new comments.

### Endpoints (base: `/api/v1/researches/{researchId}`)

| Method | Path | Purpose |
|--------|------|---------|
| `GET`    | `/comments?page=&size=` | List comments. Each row includes its `replies` list and the viewer's `myReaction`. |
| `POST`   | `/comments` | Add comment. Body: `AddCommentRequest` (content + optional media URL + optional voice URL fields). Returns `CommentResponse`. |
| `POST`   | `/comments/upload` (multipart) | One-shot create with inline media + voice. Parts: `data` (JSON `AddCommentRequest`), `media` (image/video), `voice` (audio). |
| `PATCH`  | `/comments/{commentId}` | Edit. Author-only. Body: `EditCommentRequest`. |
| `DELETE` | `/comments/{commentId}` | Soft-delete. Author / paper-owner / admin. |
| `POST`   | `/comments/{commentId}/hide` | Mark hidden by moderator/paper-owner — comment stays in DB but is not shown to regular users. |
| `POST`   | `/comments/{commentId}/unhide` | Reverse hide. |

`AddCommentRequest` (record):

```json
{
  "content": "...",          // ≤5000
  "parentId": null,          // set for replies
  "mediaUrl": "...",
  "mediaS3Key": "...",
  "mediaType": "IMAGE",      // IMAGE | VIDEO
  "mediaThumbnailUrl": "...",
  "mediaThumbnailS3Key": "...",
  "voiceUrl": "...",
  "voiceS3Key": "...",
  "voiceDurationSeconds": 30,
  "voiceTranscript": "...",
  "waveformData": "..."
}
```

### Side effects on comment create

- `research.commentCount++` (atomic).
- For replies: `parent.replyCount++` (atomic).
- Publishes `ResearchCommentedEvent` (`RESEARCH_COMMENTED`) → consumer
  dispatches `PUBLICATION_COMMENTED` to the researcher.
- `@mention` scan over `content` with `MentionSource.RESEARCH_COMMENT`.
- Broadcasts `COMMENT_CREATED` / `REPLY_CREATED` with the body snippet,
  fresh `commentCount`, and inline `mediaUrl` / `mediaType` /
  `mediaThumbnailUrl`.

---

## 16. Saves (bookmarks)

Mirrors `/posts/{id}/saves` and the QnA save endpoints — same collections
pattern.

`ResearchSave` has composite PK `(research_id, user_id)` so duplicate saves
are silently a no-op at the DB layer but still return the updated
payload (so the front-end can call this blindly).

Base: `/api/v1/researches/{researchId}` and `/api/v1/researches/me/saved`.

| Method | Path | Purpose |
|--------|------|---------|
| `POST`   | `/{researchId}/save?collection=Fiqh` | Save. Returns updated `ResearchResponse`. Idempotent. |
| `DELETE` | `/{researchId}/save` | Remove the viewer's bookmark. Idempotent. |
| `GET`    | `/me/saved?page=&size=` | Viewer's saved researches. Each row carries `savedAt`. |
| `GET`    | `/me/saved/collection?name=Fiqh&page=&size=` | Filter by collection name. Each row carries `savedAt`. |
| `GET`    | `/me/saved/collections` | Distinct collection names the viewer uses. |
| `PATCH`  | `/me/saved/collections?oldName=&newName=` | Rename a collection. |

### Saved-list response shape

Each row in `/me/saved` and `/me/saved/collection` is a
`ResearchSummaryResponse` with the save-context field populated:

| Field | Type | Meaning |
|-------|------|---------|
| `savedAt` | `LocalDateTime` | When the viewer bookmarked the research (= the `ResearchSave` row's `createdAt`). Distinct from the paper's `publishedAt`. Null on every other endpoint. |

The `currentUserSaved` flag is always `true` on these endpoints.

### Side effects on save (toggle ON only)

| Storage | Effect |
|---------|--------|
| `research_saves`            | New JPA row (composite PK `(research_id, user_id)`) |
| `research.saveCount`        | `+ 1` (atomic JPQL `adjustSaveCount`) |
| Cassandra mirrors           | `ResearchSaveByUserEntity` + `ResearchSaveLookupEntity` (eventual) |
| Realtime                    | Broadcasts `SAVE_COUNT_UPDATED` with fresh `saveCount` |
| Activity feed               | `RESEARCH_SAVED` row inserted for the viewer |

### Side effects on unsave

| Storage | Effect |
|---------|--------|
| `research_saves`            | Row deleted |
| `research.saveCount`        | `- 1` |
| Realtime                    | `SAVE_COUNT_UPDATED` broadcast |
| Activity feed               | **NOT** recorded (toggle-ON only) |

---

## 17. Views

Persistent unique-viewer ledger — one row per `(research, user)` pair
forever. Replaces the previous Redis 1h dedupe.

`POST /api/v1/researches/{researchId}/view`

- Authenticated viewers: dedupe key = `userId`.
- Anonymous viewers: dedupe key = client IP. The controller honours
  `X-Forwarded-For` (first hop), then `X-Real-IP`, then `RemoteAddr`.

Side effects on a fresh view:

- `research.viewCount++` (atomic, in its own `REQUIRES_NEW` transaction).
- Broadcasts `VIEW_COUNT_UPDATED` with fresh `viewCount`.
- JPA table: `research_views` (`ResearchView`).
- Cassandra mirror: `ResearchViewEntity` (`research_views_by_research`).

---

## 18. Downloads

`research.downloadsEnabled` toggles availability. Anonymous downloads are
allowed; `user_id` is nullable on the ledger.

`POST /api/v1/researches/{researchId}/download?mediaId=<uuid>` →
returns the (signed) download URL.

- `mediaId` omitted → the bundled paper download path.
- `mediaId` present → the specific `ResearchMedia` row's signed URL.

Side effects:

- Append-only row in `research_downloads`.
- `research.downloadCount++` (atomic).
- Broadcasts `DOWNLOAD_COUNT_UPDATED` with fresh `downloadCount`.
- Publishes `ResearchDownloadedEvent` (`RESEARCH_DOWNLOADED`).
- Cassandra mirror: `ResearchDownloadEntity`.

---

## 19. Share link

Mirrors `PostController.copyShareLink` — separate "preview" (no counter
bump) and "record" (atomic bump + return).

| Method | Path | Purpose |
|--------|------|---------|
| `GET`  | `/{id}/share-link` | Returns the share URL without bumping the counter. |
| `POST` | `/{id}/share`      | Atomically `shareCount++` and returns the share URL. Broadcasts `SHARE_COUNT_UPDATED`. |

`ShareLinkInfo` response:

```json
{
  "backendUrl":  "https://api.../r/<shareToken>",
  "frontendUrl": "https://app.../researches/<slug>",
  "token":       "<shareToken>",
  "shareCount":  <long>
}
```

---

## 20. Citations

External citation tracker for the DOI resolver / 3rd-party services.

`POST /api/v1/researches/{id}/cite`

Increments `citationCount`. Broadcasts `CITATION_COUNT_UPDATED`. Triggers a
`PUBLICATION_CITED` notification to the researcher (non-aggregated —
citations are individually significant).

---

## 21. Notifications

The research module emits domain events via `ResearchEventPublisher`
(RabbitMQ on `IRC_EXCHANGE` with kind-specific routing keys). The
`NotificationEventConsumer` listens, applies recipient resolution +
suppression, and dispatches the actual notification through the standard
pipeline.

### `NotificationKind` values fired by Research

| Kind | Trigger | Recipient | Aggregable | Email |
|------|---------|-----------|------------|-------|
| `PUBLICATION_LIKED`           | `publishReacted` (someone liked your paper) | Researcher | yes | no |
| `PUBLICATION_COMMENTED`       | `publishCommented` (someone commented on your paper) | Researcher | yes | yes |
| `PUBLICATION_COMMENT_REACTED` | `publishCommentReacted` (someone liked your comment) | Comment author | yes | no |
| `PUBLICATION_CITED`           | `POST /{id}/cite` — DOI resolver records an external citation | Researcher | no | yes |
| `RESEARCH_CONTRIBUTOR_ADDED`  | Owner added you as a contributor | Added contributor | no | yes |

Group-key patterns for the aggregable kinds:

```
PUBLICATION_LIKED:{researchId}
PUBLICATION_COMMENTED:{researchId}
PUBLICATION_COMMENT_REACTED:{commentId}
```

`PUBLICATION_CITED` and `RESEARCH_CONTRIBUTOR_ADDED` are non-aggregable —
every event deserves its own inbox row.

### RabbitMQ routing keys (`RabbitMQConstants`)

```
RESEARCH_PUBLISHED            // publication lifecycle (audit)
RESEARCH_REACTED              → PUBLICATION_LIKED
RESEARCH_COMMENTED            → PUBLICATION_COMMENTED
RESEARCH_COMMENT_REACTED      → PUBLICATION_COMMENT_REACTED
RESEARCH_DOWNLOADED           // analytics fan-out (no notification)
```

The publisher runs inside a `TransactionSynchronization.afterCommit` so the
notification only fires once the DB transaction is durable.

### Inbox / SSE endpoints

The user-facing notification API is shared across all modules and lives at
`/api/v1/notifications` (see the [Post API doc](./POST_API.md#14-notifications)
for the full reference of those endpoints — listing, mark-read, delete,
SSE stream).

---

## 22. Realtime (SSE)

### Stream endpoint

`GET /api/v1/researches/{researchId}/stream`  ·  `Content-Type: text/event-stream`

Per-instance topic-based SSE manager (`ResearchRealtimeService`).
Cross-instance fan-out via Redis pub/sub on `irc:research:{researchId}`
(`ResearchRealtimePublisher` / `ResearchRealtimeSubscriber`).

Conventions:

- `connected` event fires on subscribe.
- `heartbeat` event every ~25s — keeps proxies alive.
- Reconnect time hint = 3s.
- The actor's own subscription is filtered out server-side so the
  originating tab doesn't render its own event twice.
- Stale emitters are removed silently on IO failure.

### `ResearchRealtimeEventType` — full catalog

```
REACTION_ADDED, REACTION_CHANGED, REACTION_REMOVED,

COMMENT_CREATED, COMMENT_EDITED, COMMENT_DELETED, REPLY_CREATED,

COMMENT_REACTION_ADDED, COMMENT_REACTION_CHANGED, COMMENT_REACTION_REMOVED,

VIEW_COUNT_UPDATED, DOWNLOAD_COUNT_UPDATED,
SHARE_COUNT_UPDATED, SAVE_COUNT_UPDATED,
CITATION_COUNT_UPDATED, REACTION_COUNT_UPDATED, COMMENT_COUNT_UPDATED,

RESEARCH_UPDATED, RESEARCH_DELETED, RESEARCH_PUBLISHED
```

### `ResearchRealtimeEvent` payload

All fields nullable, `@JsonInclude(NON_NULL)`.

```
eventType                 ResearchRealtimeEventType
researchId                UUID
actorId, actorUsername, actorAvatarUrl
commentId, parentCommentId
reactionType              // "LIKE"
previousReactionType      // forward-compat for REACTION_CHANGED
body                      // COMMENT_CREATED / EDITED / REPLY_CREATED
mediaUrl, mediaType, mediaThumbnailUrl
                           // inline comment media on COMMENT_* / REPLY_*

// fresh denormalised counters after the event
reactionCount, commentCount, shareCount, saveCount,
viewCount, downloadCount, citationCount, commentReplyCount,
commentReactionCount,
commentLikeCount    // @deprecated wire alias for unmigrated clients

timestamp                 // LocalDateTime, default now
```

---

## 23. Enums

| Enum | Values | Notes |
|------|--------|-------|
| `ResearchStatus` | `DRAFT`, `PUBLISHED`, `ARCHIVED`, `RETRACTED` | New researches → `DRAFT`. Lifecycle endpoints transition. |
| `ResearchVisibility` | `PUBLIC`, `FOLLOWERS_ONLY`, `PRIVATE` | Defaults to `PUBLIC`. |
| `ReactionType` | `LIKE` | **Single-reaction-type project rule.** |
| `MediaType` | `IMAGE`, `VIDEO`, `AUDIO`, `DOCUMENT`, `SPREADSHEET`, `DATASET`, `CODE`, `ARCHIVE`, `OTHER` | Inferred from MIME on upload. |
| `SourceType` | `URL`, `DOI`, `ISBN`, `MEDIA_FILE`, `MANUAL` | |
| `ContributorRole` | `CO_AUTHOR` (default), `ADVISOR`, `REVIEWER`, `TRANSLATOR`, `EDITOR`, `CONTRIBUTOR` | |

---

## 24. JPA entities

### `Research`

Table: `researches`. Indexes on `researcher_id`, `status`, `published_at`,
`slug`, `deleted_at`, `irc_id`, `share_token`.

Key columns:

| Column | Notes |
|--------|-------|
| `id` (UUID PK) | |
| `researcher_id` | FK → `users` — the corresponding (owning) author |
| `title`, `slug`, `description`, `abstract_text` | required |
| `irc_sequence_number`, `irc_id` | unique, assigned on create |
| `doi`, `citation` | manually overrideable; DOI auto-generated on publish |
| `video_promo_*` | url, s3 key, duration, thumbnail |
| `cover_image_*` | url, s3 key |
| `view_count`, `download_count`, `reaction_count`, `comment_count`, `save_count`, `share_count`, `citation_count` | denormalised, atomic JPQL updates |
| `status` (enum), `visibility` (enum) | |
| `scheduled_publish_at`, `published_at`, `deleted_at` | |
| `share_token` | unique, 32 chars |
| `comments_enabled`, `downloads_enabled` | toggles |
| `keywords` | TEXT |
| `version` | `@Version` — optimistic locking |
| `@OneToMany mediaFiles, sources, contributors, tags, comments, reactions, saves, downloads` | cascade ALL + orphanRemoval |

### `ResearchComment`

Table: `research_comments`. Indexes on `research_id`, `user_id`,
`parent_id`, `deleted_at`.

Carries inline media URL + s3 keys (image / video) and a `like_count` /
`reply_count`. Soft delete via `deletedAt`. Moderator hide via
`isHidden` + `hiddenAt` + `hidden_by_user_id`.

### `ResearchReaction`

Table: `research_reactions`. Composite PK `(research_id, user_id)` via
`ResearchReactionId`. `reaction_type` column always `LIKE` today.

### `ResearchCommentReaction`

Table: `research_comment_likes` (legacy name). Composite PK `(comment_id,
user_id)` via `ResearchCommentReactionId`. `reaction_type` column was
added by `ddl-auto=update`; legacy rows with `NULL` → treated as `LIKE`.

### `ResearchSave`

Table: `research_saves`. Composite PK `(research_id, user_id)` via
`ResearchSaveId`. `collection_name` defaults to `"Default"`.

### `ResearchView`

Table: `research_views`. Composite PK `(research_id, user_id)`. Replaces
the prior Redis 1h dedupe — each user counts once forever.

### `ResearchDownload`

Table: `research_downloads`. Append-only ledger. `user_id` is nullable
(anonymous downloads). `media_id` is nullable (whole-bundle download).

### `ResearchMedia` / `ResearchSource` / `ResearchTag` / `ResearchContributor`

See the corresponding sections above for column layout.

---

## 25. Cassandra mirror tables

| Table | Role |
|-------|------|
| `research_reactions_by_research` | "Did user U react to research R?" point lookup |
| `research_reactions_by_user`     | "What did user U recently react to?" (DESC `created_at`) |
| `research_comment_likes_by_comment` | Mirror of comment likes per comment |
| `research_saves_by_user`         | A user's saved researches (DESC `created_at`) |
| `research_saves_lookup`          | "Has user U saved research R?" point lookup |
| `research_views_by_research`     | Unique-viewer set per research |
| `research_downloads_by_research` | Append-only download ledger (DESC `created_at`) |

Source-of-truth is still Postgres; these mirrors are read-optimised
scale-out replicas.

---

## 26. DTOs

### `ResearchResponse`

```java
record ResearchResponse(
    UUID id,
    String slug,
    String ircId,                  // IRC-{YEAR}-{6-digit}

    // author
    UUID researcherId,
    String researcherFullName,
    String researcherUsername,
    String researcherProfileImage,

    // core
    String title, String description, String abstractText,
    String keywords, String citation,
    String doi,                    // 10.{prefix}/irc.{year}.{seq}

    // video promo
    String videoPromoUrl,
    Integer videoPromoDurationSeconds,
    String videoPromoThumbnailUrl,

    // cover
    String coverImageUrl,

    // lifecycle
    ResearchStatus status,
    ResearchVisibility visibility,
    LocalDateTime scheduledPublishAt,
    LocalDateTime publishedAt,

    // counters
    Long viewCount, Long downloadCount, Long reactionCount,
    Long commentCount, Long saveCount, Long shareCount, Long citationCount,

    // toggles
    boolean commentsEnabled, boolean downloadsEnabled,

    // share
    String shareToken,
    String shareUrl,               // https://.../r/{shareToken}

    // children
    List<String> tags,
    List<MediaResponse> mediaFiles,
    List<SourceResponse> sources,
    List<ContributorResponse> contributors,

    // viewer-relative
    boolean currentUserReacted,
    String currentUserReactionType,
    boolean currentUserSaved,

    LocalDateTime createdAt, LocalDateTime updatedAt,
    String timeAgo, String formattedDate
) {}
```

### `ResearchSummaryResponse`

Light projection used in feeds & search cards. Contains the same
viewer-relative `currentUserReacted` / `currentUserSaved` flags, the IRC
identifier, counters, and the share URL — but no children lists beyond
`tags`.

```java
record ResearchSummaryResponse(
    UUID id, String slug, String ircId,
    String title, String abstractText,
    String coverImageUrl, String videoPromoThumbnailUrl,
    UUID researcherId, String researcherFullName,
    String researcherUsername, String researcherProfileImage,
    ResearchStatus status, LocalDateTime publishedAt,
    Long viewCount, Long reactionCount, Long commentCount,
    Long downloadCount, Long saveCount, Long shareCount, Long citationCount,
    List<String> tags,
    String shareUrl,
    boolean currentUserReacted,
    boolean currentUserSaved,
    /** When the viewer bookmarked this research. Populated only by
     *  saved-list endpoints ({@code GET /me/saved},
     *  {@code /me/saved/collection}). Null on every other endpoint.
     *  Distinct from {@code publishedAt} (the paper's own publish time). */
    LocalDateTime savedAt
) {}
```

### `CommentResponse`

```java
record CommentResponse(
    UUID id, UUID researchId,
    UUID userId,
    String userFullName, String userUsername, String userProfileImage,
    String content,
    String mediaUrl, String mediaType, String mediaThumbnailUrl,
    Long likeCount,                 // total reactions across all types (kept named for wire-compat)
    Long replyCount,
    ReactionType myReaction,        // null if not reacted
    boolean isEdited, LocalDateTime editedAt,
    boolean isHidden, LocalDateTime hiddenAt,
    UUID parentId,
    List<CommentResponse> replies,
    LocalDateTime createdAt,
    String timeAgo, String formattedDate
) {}
```

### `ContributorResponse`

```java
record ContributorResponse(
    UUID id,
    UUID userId,
    String fullName, String username, String profileImage,
    Role userRole, AccountType accountType,
    ContributorRole role,
    Integer displayOrder,
    String contributionNote,
    LocalDateTime addedAt
) {}
```

### `MediaResponse`

```java
record MediaResponse(
    UUID id,
    String fileUrl, String originalFileName, String mimeType,
    MediaType mediaType, Long fileSize,
    Integer displayOrder, String caption, String altText,
    Integer durationSeconds, String thumbnailUrl,
    Integer widthPx, Integer heightPx
) {}
```

### `SourceResponse`

```java
record SourceResponse(
    UUID id,
    SourceType sourceType,
    String title, String citationText,
    String url, String doi, String isbn,
    String fileUrl, String originalFileName, String mimeType, Long fileSize,
    Integer displayOrder
) {}
```

### Request DTOs

| DTO | Used by |
|-----|---------|
| `CreateResearchRequest` | `POST /researches` — required: `title`, `description`, `abstractText`, ≥1 tag |
| `UpdateResearchRequest` | `PATCH /{id}` — every field optional; non-null `contributors` REPLACES the list |
| `MediaUploadMetadata`   | `mediaFiles[i]` inside `CreateResearchRequest` — matched to `files[i]` by index |
| `UpdateMediaRequest`    | `PATCH /{id}/media/{mediaId}` — caption, altText, displayOrder, durationSeconds, dimensions |
| `SourceRequest`         | inline source rows; required: `sourceType`, `title` |
| `UpdateSourceRequest`   | `PATCH /{id}/sources/{sourceId}` |
| `ContributorRequest`    | `POST /{id}/contributors`, list in create/update; required: `userId` |
| `UpdateContributorRequest` | `PATCH /{id}/contributors/{contributorId}` |
| `AddCommentRequest`     | `POST /comments` and `/comments/upload` `data` part |
| `EditCommentRequest`    | `PATCH /comments/{commentId}` |
| `ReactRequest`          | `POST /reactions` and `/comments/{id}/reactions` — defaults `reactionType=LIKE` |

---

## 27. Activity feed integration

Every research mutation writes a row into the per-user activity history.
Full reference in [USER_ACTIVITY_API.md](./USER_ACTIVITY_API.md).

| Research action | Activity type | Notes |
|-----------------|---------------|-------|
| `POST /{id}/publish` | `RESEARCH_PUBLISHED` | `researchId` carried. Fires once per paper (not on re-publish). |
| `POST /{id}/save` (toggle ON) | `RESEARCH_SAVED` | Unsaves NOT recorded. |
| `POST /{id}/reactions` (toggle ON) | `RESEARCH_REACTION` | |
| `POST /{id}/comments` | `RESEARCH_COMMENT` | Both `researchId` + `researchCommentId` carried. |
| `POST /{id}/comments/{commentId}/reactions` (toggle ON) | `RESEARCH_COMMENT_REACTION` | |
| `@mention` in comment body (incoming) | `USER_MENTIONED` | One row per recipient. |

Note: research **paper bodies** (title / abstract / description) are
**not** scanned for mentions on publish — academic text doesn't expect
`@mentions`. Only the **comment bodies** trigger the mention path.

All `record*` calls are `@Async` + try/catch — recording an activity
never breaks the originating write.

---

## See also

- [POST_API.md](./POST_API.md) — Post / Stories / Reels APIs
- [QNA_API.md](./QNA_API.md) — Q&A APIs
- [USER_API.md](./USER_API.md) — User identity, profile, social graph, notifications
- [USER_ACTIVITY_API.md](./USER_ACTIVITY_API.md) — Per-user activity feed
- [POST_ERRORS.md](./POST_ERRORS.md) — Complete error & exception reference
- [BACKEND_ENHANCEMENTS.md](./BACKEND_ENHANCEMENTS.md) — Roadmap

---

## Cross-cutting Research rules

- **Single reaction type (`LIKE`)** mirrors the post / Q&A packages.
- **Soft delete** for researches and comments; rows stay in DB for audit.
- **Atomic JPQL counter updates** for every `*_count` column. Entity
  setter + save was racy under concurrent traffic.
- **Optimistic locking** via `Research.version` (`@Version`).
- **Async indexing** to Elasticsearch on publish/update/delete — never
  blocks the request.
- **R2 storage** for every binary (paper PDFs, figures, datasets, video
  promo, cover image, source files). Upload + delete are best-effort with
  the URL stored alongside the S3 key for re-signing.
- **`@mention` scanning** runs on comment bodies (research bodies don't
  scan — academic text doesn't expect `@mentions`).
- **Contributor users must hold `RESEARCHER` or `SCHOLAR`** — researchers
  are the only roles eligible to be listed as authors.
- **Block-aware** in both directions — feeds drop researches by blocked
  authors; reactions / comments / contributors refuse to cross a block edge.
- **RabbitMQ events fire `afterCommit`** so a rolled-back transaction never
  produces a notification.
- **All side effects wrapped in try/catch** — recording an activity row
  or firing a notification never breaks the originating write.

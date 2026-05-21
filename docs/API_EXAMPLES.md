# API Examples — JSON Request / Response Catalog

Concrete JSON request and response samples for **every endpoint** across
the Post, Q&A, and Research APIs. UUIDs in samples are illustrative;
substitute your own.

Common headers (omitted from each sample for brevity):

```
Authorization: Bearer <jwt>
Content-Type:  application/json
Accept:        application/json
```

Anonymous-safe endpoints work without the `Authorization` header — see
each domain doc for the full anonymous-safe list.

## Table of contents

- [Posts](#posts)
  - [Create / Read / Update / Delete](#post-crud)
  - [Feeds & Search](#post-feeds--search)
  - [Reactions](#post-reactions)
  - [Comments & Replies](#post-comments--replies)
  - [Saves](#post-saves)
  - [Shares](#post-shares)
  - [Views](#post-views)
  - [Media (carousel)](#post-media-carousel)
  - [Hashtags & Mentions](#hashtags--mentions)
  - [Sounds](#sounds)
  - [Stories](#stories)
  - [Story Polls](#story-polls)
  - [Close Friends](#close-friends)
  - [Highlights](#highlights)
- [Q&A](#qa)
- [Research](#research)
- [User Activity](#user-activity)
- [Notifications](#notifications)

---

# Posts

## Post CRUD

### `POST /api/v1/posts` (JSON create)

**Request:**

```http
POST /api/v1/posts
Authorization: Bearer <jwt>
Content-Type: application/json
```

```json
{
  "postType":     "EMBEDDED",
  "visibility":   "PUBLIC",
  "textContent":  "Reading at the library today 📚 #fiqh @ahmed",
  "audioTrackUrl":   null,
  "audioTrackName":  null,
  "locationName":    "Erbil Central Library",
  "locationLat":     36.1911,
  "locationLng":     44.0094,
  "sharedPostId":    null,
  "shareLink":       null,
  "mediaUrls":  ["https://cdn.example.com/posts/2f.jpg"],
  "mediaTypes": ["IMAGE"],
  "soundId":    null
}
```

**Response `200`:**

```json
{
  "id": "f66aebce-d659-45b8-8479-75195f5d6d4b",
  "authorId": "41ee2a6b-2cd9-417b-861c-d1293c623690",
  "author": {
    "id": "41ee2a6b-2cd9-417b-861c-d1293c623690",
    "username": "akar.arkanf19",
    "fullName": "akar arkan",
    "profileImage": "https://cdn.example.com/avatars/41ee.jpg"
  },
  "postType": "EMBEDDED",
  "status": "PUBLISHED",
  "visibility": "PUBLIC",
  "textContent": "Reading at the library today 📚 #fiqh @ahmed",
  "audioTrackUrl": null,
  "audioTrackName": null,
  "locationName": "Erbil Central Library",
  "locationLat": 36.1911,
  "locationLng": 44.0094,
  "sharedPostId": null,
  "shareLink": null,
  "mediaUrls":  ["https://cdn.example.com/posts/2f.jpg"],
  "mediaTypes": ["IMAGE"],
  "reactionCount": 0, "commentCount": 0, "viewCount": 0,
  "saveCount": 0, "shareCount": 0,
  "likedByMe": false, "savedByMe": false,
  "createdAt": "2026-05-21T14:30:00Z",
  "updatedAt": "2026-05-21T14:30:00Z",
  "savedAt": null,
  "savedCollectionName": null
}
```

### `POST /api/v1/posts` (multipart create)

**Request:**

```bash
curl -X POST https://api.irc.example.com/api/v1/posts \
  -H "Authorization: Bearer <jwt>" \
  -F 'postType=REEL' \
  -F 'visibility=PUBLIC' \
  -F 'textContent=Quick recap from todays lecture' \
  -F 'files[]=@reel.mp4;type=video/mp4'
```

**Response `200`:** same shape as JSON create (above).

**R2 failure response `502`:**

```json
{ "error": "upload_failed", "message": "Connection refused (R2 endpoint)" }
```

**DB-after-R2 failure response `500`:**

```json
{
  "error": "post_create_failed",
  "message": "Cassandra write timeout",
  "rolledBackFiles": 1
}
```

### `GET /api/v1/posts/{id}`

**Response `200`:** `PostResponse` — same shape as the create response.

### `PATCH /api/v1/posts/{id}` (edit, author-only)

**Request:**

```json
{
  "textContent": "Edited body — typo fix",
  "visibility":  "FOLLOWERS_ONLY",
  "mediaUrls":   null,
  "mediaTypes":  null
}
```

`null` fields are left untouched.

**Response `200`:** updated `PostResponse` with fresh `updatedAt`.

**Errors:**

- `401` (bare) — not authenticated
- `403` (bare) — not the author
- `404` (bare) — post not found

### `DELETE /api/v1/posts/{id}`

**Response:** `204 No Content`.

---

## Post Feeds & Search

### `GET /api/v1/posts/by-author/{authorId}?pageSize=20&cursor=`

**Response `200`:** `List<FeedItemResponse>`

```json
[
  {
    "id": "f66aebce-...",
    "authorId": "41ee2a6b-...",
    "author": { "id": "41ee2a6b-...", "username": "akar.arkanf19", "fullName": "akar arkan", "profileImage": "..." },
    "postType": "EMBEDDED",
    "textPreview": "Reading at the library...",
    "mediaUrl": "https://cdn.example.com/posts/2f.jpg",
    "reactionCount": 12, "commentCount": 3, "viewCount": 345,
    "saveCount": 7, "shareCount": 1,
    "likedByMe": false, "savedByMe": false,
    "createdAt": "2026-05-21T14:30:00Z"
  }
]
```

### `GET /api/v1/posts/feed?pageSize=20&cursor=`

**Response `200`:** `List<FeedItemResponse>` — same shape; uses the
authenticated viewer's home timeline.

### `GET /api/v1/posts/reels?day=2026-05-21&pageSize=20`

**Response `200`:** `List<FeedItemResponse>` — `postType` always `"REEL"`.

### `GET /api/v1/posts/search?q=zakat&page=0&size=20`

**Response `200`:**

```json
{
  "query": "zakat",
  "page": 0,
  "size": 20,
  "results": [
    "f66aebce-d659-45b8-8479-75195f5d6d4b",
    "a1b2c3d4-..."
  ]
}
```

### `GET /api/v1/posts/suggestions?userId={uuid}&limit=20`

**Response `200`:**

```json
[
  {
    "userId": "41ee2a6b-...",
    "score": 4,
    "candidateId": "9c1f...",
    "reason": "4 mutual follows",
    "computedAt": "2026-05-20T03:15:00Z"
  }
]
```

### `POST /api/v1/posts/suggestions/recompute?userId={uuid}`

**Response:** `202 Accepted` (empty body).

---

## Post Reactions

### `POST /api/v1/posts/{postId}/reactions` (toggle)

**Request:** no body.

**Response `200`:**

```json
{
  "postId": "f66aebce-...",
  "userId": "41ee2a6b-...",
  "liked":  true
}
```

(A second call flips `liked` to `false`.)

### `DELETE /api/v1/posts/{postId}/reactions` (explicit unlike)

**Response `200`:**

```json
{ "postId": "f66aebce-...", "liked": false }
```

### `GET /api/v1/posts/{postId}/reactions/me`

**Response `200`** (anonymous-safe):

```json
{ "postId": "f66aebce-...", "userId": "41ee2a6b-...", "liked": true }
```

For anonymous viewers: `{ "postId": "...", "liked": false }` (no `userId`).

### `GET /api/v1/posts/users/{userId}/reactions?pageSize=20`

**Response `200`:**

```json
[
  { "userId": "41ee2a6b-...", "createdAt": "2026-05-21T14:30:00Z",
    "postId": "f66aebce-..." }
]
```

### `POST /api/v1/posts/{postId}/comments/{commentId}/reactions` (toggle comment like)

**Response `200`:**

```json
{ "commentId": "c0...", "userId": "41ee2a6b-...", "liked": true }
```

### `DELETE /api/v1/posts/{postId}/comments/{commentId}/reactions`

**Response `200`:** `{ "commentId": "...", "liked": false }`

---

## Post Comments & Replies

### `POST /api/v1/posts/{postId}/comments` (top-level)

**Request:**

```json
{
  "text":       "Great post — thank you!",
  "mediaUrl":   null,
  "mediaType":  null
}
```

**Response `200`:**

```json
{
  "id": "c0a1b2c3-...",
  "postId": "f66aebce-...",
  "authorId": "41ee2a6b-...",
  "author": { "id": "41ee2a6b-...", "username": "akar.arkanf19",
              "fullName": "akar arkan", "profileImage": "..." },
  "textContent": "Great post — thank you!",
  "mediaUrl": null, "mediaType": null,
  "reactionCount": 0, "replyCount": 0,
  "likedByMe": false,
  "deleted": false, "edited": false,
  "createdAt": "2026-05-21T14:32:00Z"
}
```

### `GET /api/v1/posts/{postId}/comments?pageSize=20&cursor=`

**Response `200`:** `List<CommentResponse>` (chronological ascending).

### `POST /api/v1/posts/comments/{commentId}/replies`

**Request:**

```json
{ "text": "Agreed, +1", "mediaUrl": null }
```

**Response `200`:**

```json
{
  "id": "r0a1b2c3-...",
  "parentId": "c0a1b2c3-...",
  "postId": "f66aebce-...",
  "authorId": "41ee2a6b-...",
  "author": { "id": "...", "username": "...", "fullName": "...", "profileImage": "..." },
  "textContent": "Agreed, +1",
  "mediaUrl": null,
  "reactionCount": 0, "likedByMe": false,
  "deleted": false, "edited": false,
  "createdAt": "2026-05-21T14:33:00Z"
}
```

> **Depth-1 rule:** if `{commentId}` is itself a reply, the server hoists
> the new reply to be a sibling of the original (under the top-level
> comment). The frontend can't produce depth-2 trees even by accident.

### `GET /api/v1/posts/comments/{commentId}/replies?pageSize=20`

**Response `200`:** `List<ReplyResponse>` (chronological ascending).

### `PATCH /api/v1/posts/comments/{commentId}` (edit)

**Request:** `{ "text": "Edited text" }`
**Response:** `204 No Content`.

### `DELETE /api/v1/posts/comments/{commentId}` (soft-delete)

**Response:** `204 No Content`. Text is nulled, `deleted=true`.

---

## Post Saves

### `POST /api/v1/posts/{postId}/saves?collection=Quran` (toggle)

**Response `200`:**

```json
{ "postId": "f66aebce-...", "userId": "41ee2a6b-...", "saved": true }
```

### `DELETE /api/v1/posts/{postId}/saves`

**Response `200`:** `{ "postId": "...", "saved": false }`

### `GET /api/v1/posts/{postId}/saves/me`

**Response `200`:** `{ "postId": "...", "userId": "...", "saved": true }`
(anon: `{ "postId": "...", "saved": false }`).

### `GET /api/v1/posts/users/{userId}/saves?pageSize=20&cursor=`

**Response `200`:** `List<PostResponse>` (fully hydrated). Each row carries
`savedAt` + `savedCollectionName`:

```json
[
  {
    "id": "f66aebce-...",
    "authorId": "...",
    "author": { ... },
    "postType": "EMBEDDED",
    "status": "PUBLISHED",
    "visibility": "PUBLIC",
    "textContent": "...",
    "mediaUrls": ["..."], "mediaTypes": ["IMAGE"],
    "reactionCount": 12, "commentCount": 3, "viewCount": 345,
    "saveCount": 7, "shareCount": 1,
    "likedByMe": false, "savedByMe": true,
    "createdAt": "2026-05-19T10:00:00Z",
    "updatedAt": "2026-05-19T10:00:00Z",
    "savedAt": "2026-05-21T14:35:00Z",
    "savedCollectionName": "Quran"
  }
]
```

---

## Post Shares

### `POST /api/v1/posts/{postId}/shares`

**Request (optional):** `{ "caption": "Excellent read" }`

**Response `200`:**

```json
{
  "postId": "f66aebce-...",
  "createdAt": "2026-05-21T14:36:00Z",
  "shareId": "s0a1b2-...",
  "sharerId": "41ee2a6b-...",
  "caption": "Excellent read"
}
```

### `GET /api/v1/posts/{postId}/shares?pageSize=20`

**Response `200`:** `List<ShareByPostEntity>` (newest first).

---

## Post Views

### `POST /api/v1/posts/{postId}/views`

**Response `200`:**

```json
{
  "postId": "f66aebce-...",
  "userId": "41ee2a6b-...",
  "counted": true,
  "viewCount": 346
}
```

(Repeat within the 7-day Redis-dedupe window: `"counted": false`. Anon:
no `userId` field; `counted` always `false`.)

---

## Post Media (carousel)

### `POST /api/v1/posts/{postId}/media`

**Request:**

```json
{
  "sortOrder": 0,
  "mediaType": "IMAGE",
  "url": "https://cdn.../big.jpg",
  "thumbnailUrl": null,
  "s3Key": "posts/media/abc.jpg",
  "durationSeconds": null,
  "fileSizeBytes": 482301,
  "mimeType": "image/jpeg",
  "altText": "Picture of the library entrance"
}
```

**Response `200`:**

```json
{
  "postId": "f66aebce-...",
  "sortOrder": 0,
  "mediaId": "m0a1...",
  "mediaType": "IMAGE",
  "url": "https://cdn.../big.jpg",
  "thumbnailUrl": null,
  "s3Key": "posts/media/abc.jpg",
  "durationSeconds": null,
  "fileSizeBytes": 482301,
  "mimeType": "image/jpeg",
  "altText": "Picture of the library entrance"
}
```

### `GET /api/v1/posts/{postId}/media`

**Response `200`:** `List<MediaByPostEntity>` ordered by `sortOrder ASC`.

### `DELETE /api/v1/posts/{postId}/media/{mediaId}?sortOrder=0`

**Response:** `204 No Content`.

### `PUT /api/v1/posts/{postId}/media` (replace-all / reorder)

**Request:** full ordered list of `MediaByPostEntity` rows.
**Response `200`:** the new list.

---

## Hashtags & Mentions

### `GET /api/v1/hashtags/{tag}/posts?pageSize=20&cursor=`

**Response `200`:**

```json
[
  {
    "hashtag": "fiqh",
    "createdAt": "2026-05-21T14:30:00Z",
    "postId": "f66aebce-...",
    "authorId": "...",
    "textPreview": "Reading at the library...",
    "mediaUrl": "..."
  }
]
```

### `GET /api/v1/hashtags/{tag}/usage`

**Response `200`:** `{ "hashtag": "fiqh", "postCount": 1248 }`

### `GET /api/v1/users/{userId}/mentions?pageSize=20`

**Response `200`:**

```json
[
  {
    "mentionedUserId": "41ee2a6b-...",
    "createdAt": "2026-05-21T14:30:00Z",
    "postId": "f66aebce-...",
    "authorId": "...",
    "textPreview": "@akar.arkanf19 check this out"
  }
]
```

---

## Sounds

### `POST /api/v1/sounds` (upload)

**Request:**

```json
{
  "title": "Adhan – Mecca",
  "artistName": "Sheikh Ali",
  "audioUrl": "https://cdn.../adhan.mp3",
  "coverArtUrl": "https://cdn.../adhan-cover.jpg",
  "durationSeconds": 215,
  "category": "NASHEED",
  "uploaderId": "41ee2a6b-...",
  "autoApprove": false
}
```

**Response `200`:**

```json
{
  "id": "snd-...",
  "title": "Adhan – Mecca",
  "artistName": "Sheikh Ali",
  "audioUrl": "https://cdn.../adhan.mp3",
  "coverArtUrl": "https://cdn.../adhan-cover.jpg",
  "durationSeconds": 215,
  "category": "NASHEED",
  "status": "PENDING_REVIEW",
  "uploaderId": "41ee2a6b-...",
  "createdAt": "2026-05-21T14:40:00Z",
  "updatedAt": "2026-05-21T14:40:00Z"
}
```

### `GET /api/v1/sounds/{id}`

**Response `200`:** `SoundEntity` (same shape as upload response).

### `POST /api/v1/sounds/{id}/approve`

**Response:** `204 No Content`.

### `GET /api/v1/sounds/by-category/NASHEED?pageSize=20&cursor=`

**Response `200`:**

```json
[
  {
    "category": "NASHEED",
    "createdAt": "2026-05-21T14:40:00Z",
    "soundId": "snd-...",
    "title": "Adhan – Mecca",
    "artistName": "Sheikh Ali",
    "audioUrl": "...",
    "coverArtUrl": "...",
    "durationSeconds": 215
  }
]
```

### `GET /api/v1/sounds/{id}/posts?pageSize=20`

**Response `200`:**

```json
[
  {
    "soundId": "snd-...",
    "createdAt": "2026-05-21T14:42:00Z",
    "postId": "f66aebce-...",
    "authorId": "..."
  }
]
```

### `GET /api/v1/sounds/{id}/usage`

**Response `200`:** `{ "soundId": "snd-...", "useCount": 42 }`

---

## Stories

### `POST /api/v1/stories` (JSON)

**Request:**

```json
{
  "storyType":    "IMAGE",
  "visibility":   "FOLLOWERS_ONLY",
  "mediaUrl":     "https://cdn.../story.jpg",
  "thumbnailUrl": "https://cdn.../story-thumb.jpg",
  "textContent":  "Morning notes from Cairo"
}
```

**Response `200`:**

```json
{
  "authorId": "41ee2a6b-...",
  "createdAt": "2026-05-21T14:45:00Z",
  "storyId": "stor-...",
  "storyType": "IMAGE",
  "visibility": "FOLLOWERS_ONLY",
  "mediaUrl": "...",
  "thumbnailUrl": "...",
  "textContent": "Morning notes from Cairo",
  "expiresAt": "2026-05-22T14:45:00Z"
}
```

### `POST /api/v1/stories` (multipart)

```bash
curl -X POST https://api.irc.example.com/api/v1/stories \
  -H "Authorization: Bearer <jwt>" \
  -F 'storyType=IMAGE' \
  -F 'visibility=PUBLIC' \
  -F 'media=@story.jpg' \
  -F 'thumbnail=@story-thumb.jpg'
```

**Response `200`:** same shape as JSON create.

### `GET /api/v1/stories/by-author/{authorId}`

**Response `200`:** `List<StoryByAuthorEntity>` filtered by viewer visibility.

### `DELETE /api/v1/stories/{storyId}`

**Response:** `204 No Content`. Author-only.

### `POST /api/v1/stories/{storyId}/views`

**Response:** `202 Accepted`.

### `GET /api/v1/stories/{storyId}/views?pageSize=50`

**Response `200`:**

```json
[
  {
    "storyId": "stor-...",
    "viewedAt": "2026-05-21T15:00:00Z",
    "viewerId": "9c1f-..."
  }
]
```

---

## Story Polls

### `POST /api/v1/stories/{storyId}/poll`

**Request:**

```json
{
  "question": "Which madhhab do you primarily study?",
  "optionA":  "Hanafi",
  "optionB":  "Shafi'i"
}
```

**Response `200`:**

```json
{
  "storyId": "stor-...",
  "pollId":  "poll-...",
  "question": "Which madhhab do you primarily study?",
  "optionA": "Hanafi", "optionB": "Shafi'i",
  "authorId": "41ee2a6b-...",
  "createdAt": "2026-05-21T14:46:00Z"
}
```

### `POST /api/v1/polls/{pollId}/vote?choice=A`

**Response `200`:**

```json
{ "choice": "A", "voteA": 12, "voteB": 7 }
```

### `GET /api/v1/polls/{pollId}/vote/me`

**Response `200`:**

```json
{ "pollId": "poll-...", "voterId": "41ee2a6b-...", "choice": "A",
  "votedAt": "2026-05-21T14:46:30Z" }
```

(Anon / non-voter: `{ "pollId": "...", "voterId": "...", "choice": null }`.)

### `GET /api/v1/polls/{pollId}/results`

**Response `200`:** `{ "choice": null, "voteA": 12, "voteB": 7 }`

### `GET /api/v1/polls/{pollId}/voters/A?pageSize=50`

**Response `200`:**

```json
[
  {
    "pollId": "poll-...",
    "choice": "A",
    "votedAt": "2026-05-21T14:46:30Z",
    "voterId": "41ee2a6b-..."
  }
]
```

---

## Close Friends

### `GET /api/v1/close-friends`

**Response `200`:**

```json
[
  {
    "ownerId":  "41ee2a6b-...",
    "friendId": "9c1f-...",
    "addedAt":  "2026-05-20T10:00:00Z"
  }
]
```

### `POST /api/v1/close-friends?friendId=9c1f-...`

**Response:** `204 No Content`.

### `DELETE /api/v1/close-friends?friendId=9c1f-...`

**Response:** `204 No Content`.

### `GET /api/v1/close-friends/is-member?candidateId=9c1f-...`

**Response `200`:** `true` or `false`.

---

## Highlights

### `POST /api/v1/highlights`

**Request:**

```json
{
  "authorId": "41ee2a6b-...",
  "title":    "Lecture clips",
  "coverUrl": "https://cdn.../cover.jpg",
  "displayOrder": 0
}
```

**Response `200`:**

```json
{
  "authorId": "41ee2a6b-...",
  "displayOrder": 0,
  "highlightId": "hl-...",
  "title": "Lecture clips",
  "coverUrl": "...",
  "createdAt": "2026-05-21T14:48:00Z"
}
```

### `GET /api/v1/highlights/by-author/{authorId}`

**Response `200`:** `List<HighlightByAuthorEntity>` ordered by `displayOrder ASC`.

### `POST /api/v1/highlights/{highlightId}/stories/{storyId}?requesterId=41ee2a6b-...`

**Response `200`:**

```json
{
  "highlightId": "hl-...",
  "createdAt":   "2026-05-21T14:00:00Z",
  "storyId":     "stor-...",
  "authorId":    "41ee2a6b-...",
  "storyType":   "IMAGE",
  "mediaUrl":    "...",
  "thumbnailUrl": "...",
  "textContent": "Morning notes from Cairo"
}
```

(`404` if the source story is already expired.)

### `GET /api/v1/highlights/{highlightId}/stories`

**Response `200`:** `List<StoryInHighlightEntity>`.

### `DELETE /api/v1/highlights/{highlightId}/stories/{storyId}?createdAt=2026-05-21T14:00:00Z`

**Response:** `204 No Content`.

---

# Q&A

## Question CRUD

### `POST /api/v1/questions`

**Request:**

```json
{
  "title": "What's the ruling on prayer while travelling?",
  "body":  "Detailed background: I'm asking because ...",
  "answersLocked": false,
  "maxAnswers": null
}
```

**Response `201`:**

```json
{
  "id": "q0a1b2-...",
  "authorId": "41ee2a6b-...",
  "authorUsername": "akar.arkanf19",
  "authorFullName": "akar arkan",
  "authorProfileImage": "...",
  "title": "What's the ruling on prayer while travelling?",
  "body":  "Detailed background: I'm asking because ...",
  "status": "OPEN",
  "answerCount": 0, "viewCount": 0, "saveCount": 0,
  "answersLocked": false, "maxAnswers": null,
  "isSaved": false,
  "createdAt": "2026-05-21T14:50:00Z",
  "updatedAt": "2026-05-21T14:50:00Z",
  "timeAgo": "just now",
  "formattedDate": "May 21, 2026 — 14:50",
  "savedAt": null
}
```

### `GET /api/v1/questions/{questionId}`

**Response `200`:** `QuestionResponse` (same shape). View counter bumps automatically.

### `GET /api/v1/questions?page=0&size=20`

**Response `200`:** standard Spring `Page<QuestionResponse>`.

### `GET /api/v1/questions/feed/cursor?cursor=2026-05-20T18:00:00Z&limit=20`

**Response `200`:**

```json
{
  "items":      [QuestionResponse, ...],
  "nextCursor": "2026-05-19T22:15:00Z",
  "hasMore":    true
}
```

### `PATCH /api/v1/questions/{questionId}`

**Request:**

```json
{
  "title": "Updated title",
  "body":  null,
  "answersLocked": true,
  "maxAnswers": 10
}
```

**Response `200`:** updated `QuestionResponse`.

### `DELETE /api/v1/questions/{questionId}`

**Response:** `204 No Content` (soft delete).

### `GET /api/v1/questions/search?q=zakat&page=0&size=20`

**Response `200`:**

```json
{
  "query": "zakat",
  "page":  0,
  "size":  20,
  "results": ["q0a1b2-...", "q0c3d4-..."]
}
```

---

## Answer controls

### `POST /api/v1/questions/{questionId}/lock-answers`

**Response `200`:** `QuestionResponse` with `answersLocked: true`.

### `DELETE /api/v1/questions/{questionId}/lock-answers`

**Response `200`:** `answersLocked: false`.

### `PATCH /api/v1/questions/{questionId}/answer-limit?maxAnswers=10`

**Response `200`:** `QuestionResponse` with `maxAnswers: 10`.

---

## Answers

### `POST /api/v1/questions/{questionId}/answers`

**Request:**

```json
{
  "body": "Answer body — at least one full reference goes here.",
  "parentAnswerId": null,
  "mediaUrl":          null,
  "mediaType":         null,
  "mediaThumbnailUrl": null,
  "voiceUrl":          null,
  "voiceDurationSeconds": null,
  "links": "https://...",
  "sources": [
    {
      "sourceType": "HADITH",
      "title": "Sahih Bukhari, Hadith 1395",
      "citationText": "Narrated by Abu Hurairah ...",
      "url": null,
      "doi": null,
      "isbn": null
    }
  ]
}
```

**Response `201`:** `QuestionAnswerResponse`:

```json
{
  "id": "ans-...",
  "questionId": "q0a1b2-...",
  "authorId": "41ee2a6b-...",
  "authorUsername": "akar.arkanf19",
  "authorFullName": "akar arkan",
  "authorProfileImage": "...",
  "body": "Answer body — at least one full reference goes here.",
  "parentAnswerId": null,
  "replyCount": 0,
  "mediaUrl": null, "mediaType": null, "mediaThumbnailUrl": null,
  "voiceUrl": null, "voiceDurationSeconds": null,
  "links": "https://...",
  "attachments": [],
  "sources": [
    {
      "id": "src-...",
      "answerId": "ans-...",
      "sourceType": "HADITH",
      "title": "Sahih Bukhari, Hadith 1395",
      "citationText": "Narrated by Abu Hurairah ...",
      "url": null, "doi": null, "isbn": null,
      "fileUrl": null, "originalFileName": null,
      "displayOrder": 0,
      "createdAt": "2026-05-21T14:55:00Z"
    }
  ],
  "accepted": false,
  "isBestAnswer": false,
  "bestAnswerVoteCount": 0,
  "votedByMe": false,
  "edited": false, "editedAt": null,
  "deleted": false, "deletedAt": null,
  "feedbackCount": 0,
  "reactionCount": 0,
  "myReaction": null,
  "createdAt": "2026-05-21T14:55:00Z",
  "updatedAt": "2026-05-21T14:55:00Z",
  "timeAgo": "just now",
  "formattedDate": "May 21, 2026 — 14:55"
}
```

### `POST /api/v1/questions/{questionId}/answers/upload` (multipart)

```bash
curl -X POST ... \
  -F 'data={"body":"...","sources":[]};type=application/json' \
  -F 'media=@photo.jpg' \
  -F 'voice=@note.mp3'
```

**Response `201`:** same shape.

### `GET /api/v1/questions/{questionId}/answers?page=&size=`

**Response `200`:** `Page<QuestionAnswerResponse>`.

### `POST /api/v1/questions/{questionId}/answers/{answerId}/reanswers`

(Same body as `/answers`.) Server auto-sets `parentAnswerId` from the path.

### `PATCH /api/v1/questions/{questionId}/answers/{answerId}`

**Request:** `{ "body": "Edited body" }`
**Response `200`:** updated `QuestionAnswerResponse` with `edited: true`.

### `DELETE /api/v1/questions/{questionId}/answers/{answerId}`

**Response:** `204 No Content`.

---

## Answer reactions

### `POST /api/v1/questions/{questionId}/answers/{answerId}/react`

**Request (optional):** `{ "reactionType": "LIKE" }`
**Response `200`:** updated `QuestionAnswerResponse` with
`reactionCount + 1`, `myReaction: "LIKE"`.

### `DELETE /api/v1/questions/{questionId}/answers/{answerId}/react`

**Response `200`:** updated answer with `myReaction: null`.

---

## Accept / unaccept

### `POST /api/v1/questions/{questionId}/answers/{answerId}/accept`

**Response `200`:** answer with `accepted: true`.

### `DELETE /api/v1/questions/{questionId}/answers/{answerId}/accept`

**Response `200`:** answer with `accepted: false`.

---

## Best-answer voting

### `POST /api/v1/questions/{questionId}/answers/{answerId}/best`

Scholars + admins only. **Response `200`:**

```json
{
  "id": "ans-...",
  "bestAnswerVoteCount": 1,
  "isBestAnswer": true,
  "votedByMe": true,
  ...
}
```

### `DELETE /api/v1/questions/{questionId}/answers/{answerId}/best`

**Response `200`:** `votedByMe: false`, `bestAnswerVoteCount: 0`.

---

## Feedback

### `POST /api/v1/questions/{questionId}/answers/{answerId}/feedback`

**Request:**

```json
{ "feedbackType": "HELPFUL", "body": "Clear citation, well-structured." }
```

**Response `201`:**

```json
{
  "id": "fb-...",
  "answerId": "ans-...",
  "authorId": "41ee2a6b-...",
  "authorUsername": "...", "authorFullName": "...", "authorProfileImage": "...",
  "feedbackType": "HELPFUL",
  "body": "Clear citation, well-structured.",
  "createdAt": "2026-05-21T14:58:00Z",
  "updatedAt": "2026-05-21T14:58:00Z"
}
```

### `GET /api/v1/questions/{questionId}/answers/{answerId}/feedback`

**Response `200`:** `List<AnswerFeedbackResponse>`.

### `PATCH /api/v1/questions/{questionId}/answers/{answerId}/feedback/{feedbackId}`

(Same body as create.) **Response `200`:** updated feedback.

### `DELETE /api/v1/questions/{questionId}/answers/{answerId}/feedback/{feedbackId}`

**Response:** `204 No Content`.

---

## Attachments

### `POST /api/v1/questions/{questionId}/answers/{answerId}/attachments` (multipart)

```bash
curl -X POST ... \
  -F 'file=@paper.pdf' \
  -F 'caption=Reference doc' \
  -F 'displayOrder=0'
```

**Response `201`:**

```json
{
  "id": "att-...",
  "answerId": "ans-...",
  "fileUrl": "https://cdn.../paper.pdf",
  "originalFileName": "paper.pdf",
  "mimeType": "application/pdf",
  "mediaType": "DOCUMENT",
  "fileSize": 482301,
  "displayOrder": 0,
  "caption": "Reference doc",
  "durationSeconds": null,
  "thumbnailUrl": null,
  "createdAt": "2026-05-21T15:00:00Z"
}
```

### `GET /api/v1/questions/{questionId}/answers/{answerId}/attachments`

**Response `200`:** `List<AnswerAttachmentResponse>`.

### `PATCH /api/v1/questions/{questionId}/answers/{answerId}/attachments/{attachmentId}`

**Request:** `{ "caption": "Updated", "displayOrder": 1 }`
**Response `200`:** updated attachment.

### `DELETE /api/v1/questions/{questionId}/answers/{answerId}/attachments/{attachmentId}`

**Response:** `204 No Content`.

---

## Sources / references (post-create)

### `POST /api/v1/questions/{questionId}/answers/{answerId}/sources`

**Request:** same shape as `sources[]` in `CreateAnswerRequest`.
**Response `201`:** `AnswerSourceResponse`.

### `PATCH /api/v1/questions/{questionId}/answers/{answerId}/sources/{sourceId}`

**Request:** partial fields. **Response `200`:** updated source.

### `GET /api/v1/questions/{questionId}/answers/{answerId}/sources`

**Response `200`:** `List<AnswerSourceResponse>` ordered by `displayOrder`.

### `DELETE /api/v1/questions/{questionId}/answers/{answerId}/sources/{sourceId}`

**Response:** `204 No Content`.

---

## Q&A Saves

### `POST /api/v1/questions/{questionId}/save?collection=Hadith`

**Response `201`:** updated `QuestionResponse` with `isSaved: true`,
`saveCount + 1`.

### `DELETE /api/v1/questions/{questionId}/save`

**Response `200`:** `isSaved: false`, decremented `saveCount`.

### `GET /api/v1/questions/me/saved?page=&size=`

**Response `200`:** `Page<QuestionResponse>` where each row carries
`isSaved: true` and `savedAt: <bookmark-time>`:

```json
{
  "content": [
    {
      "id": "q0a1b2-...",
      "title": "...", "body": "...",
      "isSaved": true,
      "savedAt": "2026-05-21T15:05:00Z",
      ...
    }
  ],
  "totalElements": 7, "totalPages": 1, "size": 20, "number": 0
}
```

### `GET /api/v1/questions/me/saved/collection?name=Hadith&page=&size=`

**Response `200`:** same shape, filtered by collection.

### `GET /api/v1/questions/me/saved/collections`

**Response `200`:** `["Hadith", "Quran", "Default"]`

### `PATCH /api/v1/questions/me/saved/collections?oldName=Hadith&newName=Hadiths`

**Response:** `204 No Content`.

---

## Q&A Share

### `GET /api/v1/questions/{questionId}/share-link`

**Response `200`:**

```json
{
  "backendUrl":  "https://api.example.com/q/q0a1b2-...",
  "frontendUrl": "https://app.example.com/questions/q0a1b2-...",
  "token":       "q0a1b2-...",
  "shareCount":  3
}
```

### `POST /api/v1/questions/{questionId}/share`

**Response `200`:** same `ShareLinkInfo` shape; `shareCount` is now `4`.

---

# Research

## Research CRUD

### `POST /api/v1/researches` (multipart)

```bash
curl -X POST https://api.irc.example.com/api/v1/researches \
  -H "Authorization: Bearer <jwt>" \
  -F 'data={
        "title":"On the linguistic structure of Quranic verses",
        "description":"...",
        "abstractText":"...",
        "keywords":"linguistics, Quran, fiqh",
        "citation":null,
        "doi":null,
        "visibility":"PUBLIC",
        "scheduledPublishAt":null,
        "commentsEnabled":true,
        "downloadsEnabled":true,
        "tags":["linguistics","Quran"],
        "sources":[
          {"sourceType":"BOOK","title":"Lisaan al-Arab",
           "citationText":"Ibn Manzur, Beirut, 1990",
           "url":null,"doi":null,"isbn":"978-XXX",
           "displayOrder":0}
        ],
        "mediaFiles":[{"caption":"Figure 1","altText":"chart","displayOrder":0}],
        "contributors":[]
      };type=application/json' \
  -F 'files[]=@paper.pdf;type=application/pdf' \
  -F 'files[]=@fig1.png;type=image/png'
```

**Response `201`:** `ResearchResponse`:

```json
{
  "id": "r0a1b2-...",
  "slug": "on-the-linguistic-structure-of-quranic-verses-a1b2",
  "ircId": "IRC-2026-000042",
  "researcherId": "41ee2a6b-...",
  "researcherFullName": "akar arkan",
  "researcherUsername": "akar.arkanf19",
  "researcherProfileImage": "...",
  "title": "On the linguistic structure of Quranic verses",
  "description": "...",
  "abstractText": "...",
  "keywords": "linguistics, Quran, fiqh",
  "citation": null,
  "doi": null,
  "videoPromoUrl": null,
  "videoPromoDurationSeconds": null,
  "videoPromoThumbnailUrl": null,
  "coverImageUrl": null,
  "status": "DRAFT",
  "visibility": "PUBLIC",
  "scheduledPublishAt": null,
  "publishedAt": null,
  "viewCount": 0, "downloadCount": 0,
  "reactionCount": 0, "commentCount": 0,
  "saveCount": 0, "shareCount": 0, "citationCount": 0,
  "commentsEnabled": true, "downloadsEnabled": true,
  "shareToken": "shr-...",
  "shareUrl":   "https://app.example.com/r/shr-...",
  "tags": ["linguistics","Quran"],
  "mediaFiles": [ MediaResponse ],
  "sources":    [ SourceResponse ],
  "contributors": [],
  "currentUserReacted": false,
  "currentUserReactionType": null,
  "currentUserSaved": false,
  "createdAt": "2026-05-21T15:10:00Z",
  "updatedAt": "2026-05-21T15:10:00Z",
  "timeAgo": "just now",
  "formattedDate": "May 21, 2026 — 15:10"
}
```

### `PATCH /api/v1/researches/{id}`

**Request:** any subset of `UpdateResearchRequest` fields:

```json
{
  "title": "On Quranic Linguistic Structure (revised)",
  "abstractText": null,
  "tags": ["linguistics","Quran","fiqh"],
  "contributors": null
}
```

**Response `200`:** updated `ResearchResponse`.

### `DELETE /api/v1/researches/{id}`

**Response:** `204 No Content` (soft-delete).

---

## Lifecycle

### `POST /api/v1/researches/{id}/publish`

**Response `200`:** `ResearchResponse` with `status: "PUBLISHED"`,
`publishedAt: now`, generated `doi`.

### `POST /api/v1/researches/{id}/unpublish` / `archive` / `retract`

**Response `200`:** updated status.

---

## Video promo

### `POST /api/v1/researches/{id}/video-promo` (multipart)

```bash
curl -X POST ... \
  -F 'video=@promo.mp4' \
  -F 'thumbnail=@poster.jpg'
```

**Response `200`:** `ResearchResponse` with `videoPromoUrl` and
`videoPromoDurationSeconds` populated.

### `DELETE /api/v1/researches/{id}/video-promo`

**Response `200`:** `ResearchResponse` with video-promo fields nulled.

---

## Cover image

### `POST /api/v1/researches/{id}/cover-image` (multipart, part `image`)

**Response `200`:** `ResearchResponse` with `coverImageUrl` populated.

### `DELETE /api/v1/researches/{id}/cover-image`

**Response `200`:** `coverImageUrl: null`.

---

## Media files

### `POST /api/v1/researches/{id}/media` (multipart)

```bash
-F 'file=@fig2.png' -F 'caption=Figure 2' -F 'altText=...' -F 'displayOrder=1'
```

**Response `201`:** `MediaResponse` (see DTOs).

### `PATCH /api/v1/researches/{id}/media/{mediaId}`

**Request:** `{ "caption": "...", "altText": "...", "displayOrder": 2 }`
**Response `200`:** updated `MediaResponse`.

### `DELETE /api/v1/researches/{id}/media/{mediaId}`

**Response:** `204 No Content`.

---

## Sources

### `PATCH /api/v1/researches/{id}/sources/{sourceId}`

**Request:** any subset of `UpdateSourceRequest`.
**Response `200`:** updated `SourceResponse`.

### `POST /api/v1/researches/{id}/sources/{sourceId}/file` (multipart)

`-F 'file=@source.pdf'` — **Response `200`:** updated `SourceResponse`
with `fileUrl` populated.

---

## Contributors

### `POST /api/v1/researches/{id}/contributors`

**Request:**

```json
{
  "userId":           "9c1f-...",
  "role":             "CO_AUTHOR",
  "displayOrder":     0,
  "contributionNote": "Statistical analysis"
}
```

**Response `201`:** `ContributorResponse`:

```json
{
  "id": "ctr-...",
  "userId": "9c1f-...",
  "fullName": "Ahmad Rahman",
  "username": "ahmad",
  "profileImage": "...",
  "userRole": "RESEARCHER",
  "accountType": "VERIFIED_RESEARCHER",
  "role": "CO_AUTHOR",
  "displayOrder": 0,
  "contributionNote": "Statistical analysis",
  "addedAt": "2026-05-21T15:15:00Z"
}
```

### `PUT /api/v1/researches/{id}/contributors`

**Request:** full list `[ContributorRequest, ...]`. Empty list clears.
**Response `200`:** `List<ContributorResponse>`.

### `PATCH /api/v1/researches/{id}/contributors/{contributorId}`

**Request:** `{ "role": "ADVISOR", "displayOrder": 1, "contributionNote": "..." }`
**Response `200`:** updated `ContributorResponse`.

### `DELETE /api/v1/researches/{id}/contributors/{contributorId}`

**Response:** `204 No Content`.

### `GET /api/v1/researches/{id}/contributors`

**Response `200`:** `List<ContributorResponse>`.

---

## Reads

### `GET /api/v1/researches/{id}` / `GET /slug/{slug}` / `GET /share/{shareToken}`

**Response `200`:** `ResearchResponse` (full).

---

## Feeds

### `GET /api/v1/researches/feed?page=0&size=20&sort=publishedAt,desc`

**Response `200`:** `Page<ResearchSummaryResponse>`:

```json
{
  "content": [
    {
      "id": "r0a1b2-...",
      "slug": "on-...",
      "ircId": "IRC-2026-000042",
      "title": "...",
      "abstractText": "...",
      "coverImageUrl": "...",
      "videoPromoThumbnailUrl": null,
      "researcherId": "...", "researcherFullName": "...",
      "researcherUsername": "...", "researcherProfileImage": "...",
      "status": "PUBLISHED",
      "publishedAt": "2026-05-21T15:11:00Z",
      "viewCount": 0, "reactionCount": 0, "commentCount": 0,
      "downloadCount": 0, "saveCount": 0, "shareCount": 0, "citationCount": 0,
      "tags": ["linguistics","Quran"],
      "shareUrl": "https://app.example.com/r/shr-...",
      "currentUserReacted": false,
      "currentUserSaved": false,
      "savedAt": null
    }
  ],
  "totalElements": 1, "totalPages": 1, "size": 20, "number": 0
}
```

### `GET /api/v1/researches/feed/following?page=&size=` (auth required)

Same shape, filtered to followees + block-aware.

### `GET /api/v1/researches/researcher/{researcherId}?page=&size=`

Same shape.

---

## Researcher dashboard

### `GET /api/v1/researches/me/drafts?page=&size=`

`Page<ResearchSummaryResponse>` filtered to `status='DRAFT'` owned by the viewer.

### `GET /api/v1/researches/me/all?page=&size=`

All statuses owned by the viewer.

---

## Search & tags

### `GET /api/v1/researches/search?q=quran&page=0&size=20`

**Response `200`:** `{ "query":"quran", "page":0, "size":20, "results":[uuids] }`

### `GET /api/v1/researches/search/tags?tags=linguistics&tags=ethics&page=&size=`

**Response `200`:** `Page<ResearchSummaryResponse>`.

### `GET /api/v1/researches/tags/trending?limit=20`

**Response `200`:** `["linguistics","fiqh","quran",...]`

---

## Reactions

### `POST /api/v1/researches/{researchId}/reactions`

**Request (optional):** `{ "reactionType": "LIKE" }`
**Response:** `201 Created` (empty body).

### `DELETE /api/v1/researches/{researchId}/reactions`

**Response `200`:** updated `ResearchResponse` with `currentUserReacted: false`.

### `GET /api/v1/researches/{researchId}/reactions/breakdown`

**Response `200`:** `{ "LIKE": 124 }`

### Comment reactions

`POST /comments/{commentId}/reactions` → `201` (empty).
`DELETE /comments/{commentId}/reactions` → `200 CommentResponse` (with
`myReaction: null` and decremented `likeCount`).

---

## Comments & replies

### `POST /api/v1/researches/{researchId}/comments`

**Request:**

```json
{
  "content":    "Insightful discussion of phrasing patterns.",
  "parentId":   null,
  "mediaUrl":   null,
  "mediaType":  null,
  "voiceUrl":   null
}
```

**Response `201`:**

```json
{
  "id": "cmt-...",
  "researchId": "r0a1b2-...",
  "userId": "41ee2a6b-...",
  "userFullName": "akar arkan",
  "userUsername": "akar.arkanf19",
  "userProfileImage": "...",
  "content": "Insightful discussion of phrasing patterns.",
  "mediaUrl": null, "mediaType": null, "mediaThumbnailUrl": null,
  "likeCount": 0,
  "replyCount": 0,
  "myReaction": null,
  "isEdited": false, "editedAt": null,
  "isHidden": false, "hiddenAt": null,
  "parentId": null,
  "replies": [],
  "createdAt": "2026-05-21T15:20:00Z",
  "timeAgo": "just now",
  "formattedDate": "May 21, 2026 — 15:20"
}
```

### `POST /api/v1/researches/{researchId}/comments/upload` (multipart)

`data` JSON part + `media` / `voice` parts. Response: same shape as JSON create.

### `PATCH /api/v1/researches/{researchId}/comments/{commentId}`

**Request:** `{ "content": "Edited content" }`
**Response `200`:** updated `CommentResponse` with `isEdited: true`.

### `DELETE /api/v1/researches/{researchId}/comments/{commentId}`

**Response:** `204 No Content`.

### `POST /api/v1/researches/{researchId}/comments/{commentId}/hide` / `/unhide`

**Response:** `204 No Content`.

### `GET /api/v1/researches/{researchId}/comments?page=&size=`

**Response `200`:** `Page<CommentResponse>` (each row carries its
`replies` list inline).

---

## Saves

### `POST /api/v1/researches/{researchId}/save?collection=Fiqh`

**Response `201`:** `ResearchResponse` with `currentUserSaved: true`.

### `DELETE /api/v1/researches/{researchId}/save`

**Response `200`:** `ResearchResponse` with `currentUserSaved: false`.

### `GET /api/v1/researches/me/saved?page=&size=`

**Response `200`:** `Page<ResearchSummaryResponse>` where each row carries
`currentUserSaved: true` and `savedAt: <bookmark-time>`:

```json
{
  "content": [
    {
      "id": "r0a1b2-...",
      "title": "...",
      "currentUserSaved": true,
      "savedAt": "2026-05-21T15:25:00Z",
      ...
    }
  ],
  ...
}
```

### `GET /api/v1/researches/me/saved/collection?name=Fiqh&page=&size=`

Same shape, filtered.

### `GET /api/v1/researches/me/saved/collections`

**Response `200`:** `["Fiqh","Default"]`.

### `PATCH /api/v1/researches/me/saved/collections?oldName=Fiqh&newName=Fiqh-2026`

**Response:** `204 No Content`.

---

## Views

### `POST /api/v1/researches/{researchId}/view`

**Response `200`:** empty body.

---

## Downloads

### `POST /api/v1/researches/{researchId}/download?mediaId={uuid?}`

**Response `200`:** signed download URL (`text/plain`).

---

## Share link

### `GET /api/v1/researches/{id}/share-link`

**Response `200`:**

```json
{
  "backendUrl":  "https://api.example.com/r/shr-...",
  "frontendUrl": "https://app.example.com/r/shr-...",
  "token":       "shr-...",
  "shareCount":  12
}
```

### `POST /api/v1/researches/{id}/share`

**Response `200`:** same shape; `shareCount` is now `13`.

---

## Citations

### `POST /api/v1/researches/{id}/cite`

**Response `200`:** empty body. `citationCount + 1`.

---

# User Activity

### `GET /api/v1/users/me/activity?types=POST_SAVED,RESEARCH_SAVED&from=2026-05-01T00:00:00Z&to=2026-05-31T23:59:59Z&page=0&size=20`

**Response `200`:** standard `Page<UserActivityResponse>`:

```json
{
  "content": [
    {
      "id": "act-...",
      "activityType": "POST_SAVED",
      "label": "Saved a post",
      "subtitle": "Bookmarked for later",
      "post": { "id": "f66aebce-..." },
      "createdAt": "2026-05-21T14:35:00Z",
      "timeAgo": "5 minutes ago",
      "formattedDate": "May 21, 2026 — 14:35"
    },
    {
      "id": "act-...",
      "activityType": "RESEARCH_SAVED",
      "label": "Saved a research paper",
      "subtitle": "Bookmarked for later",
      "research": { "id": "r0a1b2-..." },
      "createdAt": "2026-05-21T15:25:00Z",
      "timeAgo": "just now",
      "formattedDate": "May 21, 2026 — 15:25"
    }
  ],
  "totalElements": 2, "totalPages": 1, "size": 20, "number": 0
}
```

### `DELETE /api/v1/users/me/activity/{activityId}`

**Response:** `204 No Content`.

### `DELETE /api/v1/users/me/activity?type=POST_REACTION`

**Response `200`:** `{ "deleted": 42 }`.

### `GET /api/v1/users/me/activity/stream?token=<jwt>` (SSE)

**Stream event:**

```
event: activity
data: {"activityId":"act-...","activityType":"POST_SAVED","userId":"41ee2a6b-...","postId":"f66aebce-...","timestamp":"2026-05-21T14:35:00","activity":{...full UserActivityResponse...}}
```

---

# Notifications

### `GET /api/v1/notifications?category=POSTS&page=0&size=20`

**Response `200`:** `Page<NotificationResponse>`:

```json
{
  "content": [
    {
      "id": "n-...",
      "type": "POST_REACTED",
      "category": "POSTS",
      "title": "Someone liked your post",
      "body":  "@ahmad liked your post",
      "actorId": "9c1f-...",
      "actorUsername": "ahmad",
      "actorFullName": "Ahmad Rahman",
      "actorProfileImage": "...",
      "aggregateCount": 1,
      "lastActorId": null,
      "lastActorUsername": null,
      "resourceId": "f66aebce-...",
      "resourceType": "Post",
      "deepLink": "/posts/f66aebce-...",
      "isRead": false,
      "readAt": null,
      "createdAt": "2026-05-21T14:35:00Z"
    }
  ],
  ...
}
```

### `GET /api/v1/notifications/unread/count?category=POSTS`

**Response `200`:** `{ "count": 7 }`.

### `PATCH /api/v1/notifications/{id}/read`

**Response `200`:** empty body.

### `PATCH /api/v1/notifications/read` (bulk)

**Request:** `{ "ids": ["n-1","n-2"] }`
**Response `200`:** `{ "updated": 2 }`.

### `PATCH /api/v1/notifications/read-all`

**Response `200`:** empty body.

### `DELETE /api/v1/notifications/{id}`

**Response:** `204 No Content`.

### `DELETE /api/v1/notifications/read`

**Response `200`:** `{ "deleted": 14 }`.

### `GET /api/v1/notifications/stream?token=<jwt>` (SSE)

```
event: connected
data: {"timestamp":"2026-05-21T15:30:00"}

event: notification
data: {"id":"n-...","type":"POST_REACTED","body":"@ahmad liked your post",...}

event: unread-count
data: {"count":8}

event: heartbeat
data: {"timestamp":"2026-05-21T15:30:25"}
```

---

## Error response (every endpoint)

Every 4xx / 5xx (except the multipart-create custom bodies) returns:

```json
{
  "timestamp": "2026-05-21T14:30:00",
  "status":    400,
  "error":     "Bad Request",
  "message":   "Parameter 'postId' must be of type 'UUID'. Received: 'undefined'",
  "path":      "/api/v1/posts/undefined",
  "errorCode": "TYPE_MISMATCH",
  "details": {
    "parameter":     "postId",
    "expectedType":  "UUID",
    "receivedValue": "undefined",
    "hint":          "frontend_path_param_unhydrated"
  },
  "traceId": "a1b2c3d4-..."
}
```

See [POST_ERRORS.md](./POST_ERRORS.md) for the full error catalog —
every `errorCode`, the trigger conditions, and the body shape.

---

## See also

- [POST_API.md](./POST_API.md) — Post / Stories / Reels APIs (deep reference)
- [QNA_API.md](./QNA_API.md) — Q&A APIs (deep reference)
- [RESEARCH_API.md](./RESEARCH_API.md) — Research APIs (deep reference)
- [USER_API.md](./USER_API.md) — User identity, profile, social graph, notifications
- [USER_ACTIVITY_API.md](./USER_ACTIVITY_API.md) — Per-user activity feed
- [POST_ERRORS.md](./POST_ERRORS.md) — Complete error reference
- [BACKEND_ENHANCEMENTS.md](./BACKEND_ENHANCEMENTS.md) — Roadmap

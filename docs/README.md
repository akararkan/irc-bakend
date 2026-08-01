# IRC Platform — API Documentation

Complete API reference, organized as **one directory per topic** with focused
files per sub-topic. Every endpoint is documented with request/response
shapes, error codes, and side effects, verified against the source.

## Conventions

- **Base URL**: all paths are relative to the server root (e.g. `/api/v1/...`).
- **Auth**: `Authorization: Bearer <accessToken>` unless a page says otherwise.
  SSE streams also accept `?token=<accessToken>` because `EventSource` can't
  set headers. See [user/security-model.md](user/security-model.md).
- **Errors**: every error uses one JSON envelope with a machine-readable
  `errorCode` — catalog in [errors/error-handling.md](errors/error-handling.md).
- **Paging**: read endpoints clamp page sizes to **max 100**. Cassandra-backed
  feeds use `cursor` (keyset) paging; Postgres lists use `page`/`size`.
- **Realtime**: SSE + Redis pub/sub, no WebSockets. Model and endpoint table
  in [realtime/overview.md](realtime/overview.md).

## Topics

### Posts — `post/`
| File | Covers |
|---|---|
| [posts.md](post/posts.md) | Create (JSON + multipart), read, update, delete (async cascade) |
| [feed.md](post/feed.md) | Home timeline, profile feed, friend suggestions |
| [reels.md](post/reels.md) | Global / following / for-you / by-author reel feeds |
| [engagement.md](post/engagement.md) | Reactions, comments & replies, saves, shares, views |
| [media.md](post/media.md) | Post media CRUD (author-only mutations) |
| [realtime.md](post/realtime.md) | Per-post SSE stream + event catalog |

### Stories — `story/`
| File | Covers |
|---|---|
| [stories.md](story/stories.md) | Create, visibility, TTL lifetimes, views, author-only viewer log |
| [polls.md](story/polls.md) | 2-option polls, voting, live tallies |
| [highlights.md](story/highlights.md) | Permanent story archives (owner-scoped) |
| [close-friends.md](story/close-friends.md) | Both close-friends endpoint families |
| [realtime.md](story/realtime.md) | Story-tray SSE + per-story SSE |

### Users & Auth — `user/`
| File | Covers |
|---|---|
| [auth.md](user/auth.md) | Register, login, refresh rotation, logout, change-password |
| [users.md](user/users.md) | Identity, lookups, stats, links, contacts, account deletion |
| [profile.md](user/profile.md) | UserProfile, avatar/cover, specializations, roles & badges |
| [social.md](user/social.md) | Follow, block, restrict, social status, who-to-follow |
| [search.md](user/search.md) | Ranked full-text user search |
| [security-model.md](user/security-model.md) | Authorization model, SSE token auth, permit-all switch |

### Notifications — `notifications/`
| File | Covers |
|---|---|
| [notifications.md](notifications/notifications.md) | Inbox, unread counts, mark-read, delete, aggregation model |
| [realtime.md](notifications/realtime.md) | Notification SSE stream + payload contract |
| [email-preferences.md](notifications/email-preferences.md) | Email toggles, test send, unsubscribe-all |

### Settings — `settings/`
| File | Covers |
|------|--------|
| [README.md](settings/README.md) | Module overview, ownership model [B]/[C]/[B+C], section → package map |
| [architecture.md](settings/architecture.md) | Storage tiers, the two enforcement layers, caching, audit, step-up |
| [api-reference.md](settings/api-reference.md) | Every settings/security/media endpoint |
| [data-model.md](settings/data-model.md) | Every new table + additive columns on `users`/`refresh_tokens` |
| [config.md](settings/config.md) | New `application.yaml` keys + env vars (prod secrets) |
| [privacy.md](settings/privacy.md) | §5/§13 Visibility Resolver, lists, mute, hidden keywords, blocks |
| [auth-sessions.md](settings/auth-sessions.md) | §2/§4/§12 phone+OTP, 2FA/TOTP, recovery, sessions, login history, step-up |
| [notifications.md](settings/notifications.md) | §8 preference matrix, DND, push tokens |
| [messaging-media.md](settings/messaging-media.md) | §9/§15/§20 media pipeline (1080 cap), storage usage |
| [data-export-deletion.md](settings/data-export-deletion.md) | §16 export + account-deletion state machine |
| [safety-center.md](settings/safety-center.md) | §18 reports, strikes, derived security score |
| [discovery-contacts.md](settings/discovery-contacts.md) | §3/§6/§14 contacts, discoverability, QR, consent |
| [presence.md](settings/presence.md) | §7 three-way presence policy + reciprocity |
| [core-settings.md](settings/core-settings.md) | §10/§11/§22 cosmetic JSONB, cache, audit |
| [about-policy.md](settings/about-policy.md) | §19 app-config + policy acceptance |
| [community.md](settings/community.md) | §17 communities reuse existing channel roles |
| [implementation-notes.md](settings/implementation-notes.md) | Real vs stand-in, divergences, follow-up seams |

### Research — `research/`
| File | Covers |
|---|---|
| [research.md](research/research.md) | Lifecycle: draft → publish/schedule → archive/retract |
| [media-sources-contributors.md](research/media-sources-contributors.md) | Files, promo video, cover, sources, contributors |
| [social.md](research/social.md) | Reactions, comments, saves, views, downloads |
| [feeds-discovery.md](research/feeds-discovery.md) | Feeds, tag search, trending tags, saved collections, cite/share |
| [realtime.md](research/realtime.md) | Per-research SSE stream + event catalog |

### Q&A — `qna/`
| File | Covers |
|---|---|
| [questions.md](qna/questions.md) | Question CRUD, feeds, lock, answer limits |
| [answers.md](qna/answers.md) | Answers, reanswers (flat depth-1), author-accept |
| [engagement.md](qna/engagement.md) | Answer reactions, attachments, sources, saves, shares |
| [realtime.md](qna/realtime.md) | Per-question SSE stream + event catalog |

### Chat & Messaging — `chat/`
| File | Covers |
|---|---|
| [api-reference.md](chat/api-reference.md) | Conventions + the full chat error-code catalog (start here) |
| [conversations.md](chat/conversations.md) | Inbox, create DM/group, get/update/delete (incl. "delete for me"), read/mute/pin/archive |
| [messages.md](chat/messages.md) | Send (idempotent), read (cursor + gap sync), edit/delete/forward, reactions, pinned, delivered |
| [groups.md](chat/groups.md) | Members, roles, restrict, leave, transfer ownership, invite links, join |
| [message-requests.md](chat/message-requests.md) | Message Requests inbox + the stranger-contact flow |
| [channels/overview.md](chat/channels/overview.md) | Telegram-style broadcast **channels** — lifecycle, profile, settings (index into the `channels/` subdir) |
| [channels/admins.md](chat/channels/admins.md) | Granular admin rights, invite links, join requests, transfer/kick/restrict |
| [channels/posts.md](chat/channels/posts.md) | Channel posts — tags, signatures, views/forwards, message types, polls, gallery |
| [channels/discussion.md](chat/channels/discussion.md) | Linked discussion group & comments, drafts, slow mode |
| ~~[channels/stories.md](chat/channels/stories.md)~~ | **⛔ Removed** — channel stories & highlights were deleted (tombstone). |
| [channels/inbox.md](chat/channels/inbox.md) | Subscriber/member list, the channel in your inbox, notifications, delete |
| [channels/stats.md](chat/channels/stats.md) | Channel statistics + realtime SSE stream & event catalog |
| [calls.md](chat/calls.md) | Voice/video calls — ring/answer/decline/end + WebRTC SDP/ICE relay |
| [live-streaming.md](chat/live-streaming.md) | Live streaming — go live, viewer registry, live chat |
| [realtime.md](chat/realtime.md) | The single per-user SSE stream + event catalog, typing, presence, unread badge |
| [settings.md](chat/settings.md) | Chat privacy — read receipts, last-seen, typing (symmetric-gate model) |
| [search.md](chat/search.md) | In-conversation and cross-conversation message search |

### Search — `search/`
| File | Covers |
|---|---|
| [README.md](search/README.md) | **The search hub** — architecture, engine split, complexity summary table |
| [global-search.md](search/global-search.md) | `GET /api/v1/search` over 8 entity types (posts, reels, questions, answers, research, users, channels, sounds), ranking model, cursor paging, privacy filters |
| [users.md](search/users.md) | People search (global + Postgres FTS + mention suggest) |
| [channels.md](search/channels.md) | ES-ranked channel discovery + by-handle lookup |
| [qna.md](search/qna.md) | Question & **answer-level** search, accepted-answer boost |
| [sounds.md](search/sounds.md) | Sound-library search for the reels/stories picker |
| [knowledge.md](search/knowledge.md) | Topic / madhhab taxonomy lookup |
| [indexing-and-reindex.md](search/indexing-and-reindex.md) | The 8 ES indices, async indexing pipeline, 7 admin reindex endpoints |
| [algorithms-and-complexity.md](search/algorithms-and-complexity.md) | BM25, function_score, fuzzy/prefix, `search_after`, FTS, trigram — with per-operation time complexity |
| [coverage.md](search/coverage.md) | Entity-by-entity coverage matrix + deliberate exclusions |

### Platform services — `platform/`
| File | Covers |
|---|---|
| [tags.md](platform/tags.md) | Trending tags, tag content feeds, autocomplete |
| ~~[search.md](platform/search.md)~~ | **Moved** → the [`search/`](search/README.md) directory (pointer stub kept) |
| [mentions.md](platform/mentions.md) | @mention suggest/click/parse pipeline |
| [media-proxy.md](platform/media-proxy.md) | R2/S3 streaming proxy with Range support |
| [activity.md](platform/activity.md) | Activity history, reel watch history, activity SSE |
| [audit.md](platform/audit.md) | Admin audit log + global audit SSE |

### Knowledge taxonomy — `knowledge/`
| File | Covers |
|---|---|
| [taxonomy.md](knowledge/taxonomy.md) | Topics & madhhabs — trilingual vocabularies, public lookup endpoints (`GET /topics`, `GET /madhhabs`), profile specializations + madhhab selection consumers |

### Errors — `errors/`
| File | Covers |
|---|---|
| [error-handling.md](errors/error-handling.md) | The `ApiErrorResponse` envelope + full error-code catalog |
| [exception-design.md](errors/exception-design.md) | Contributor guide: which exception to throw, adding codes |

### Realtime & messaging — `realtime/`
| File | Covers |
|---|---|
| [overview.md](realtime/overview.md) | **The master realtime reference** — all 9 SSE streams, event names per stream, the screen-by-screen frontend guide, delta model, media plane (WebRTC/WHIP/WHEP), Redis channels |
| [messaging.md](realtime/messaging.md) | RabbitMQ exchanges, queues, routing keys, retry/DLQ policy |

## Legacy

`_legacy/` holds the pre-restructure flat files (kept for history). They are
**stale** — the per-topic files above are the source of truth.

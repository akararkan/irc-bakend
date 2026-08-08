# Pages — moderation & safety

Part of the [admin dashboard frontend guide](README.md).
Legend: **SU** = step-up required (§[auth-and-roles.md](auth-and-roles.md)) ·
roles in the *Who* column are the `hasRole`/`hasAnyRole` grants as coded ·
list endpoints paginate per [conventions.md](conventions.md).
Wire-level request/response JSON: [../api/](../api/README.md).

Section docs: [../trust-safety/](../trust-safety/README.md).

---

### 4.3 Moderation queue & bulk — base `/api/v1/admin/moderation`

`AdminModerationController` — ADMIN, MODERATOR. Doc: [content-moderation.md](../trust-safety/content-moderation.md).

| Method + path | Key params | SU |
|---------------|-----------|----|
| `GET /queue` | `source` (`reports`\|`media`\|`keywords`), `targetType`, `page`, `pageSize` | — |
| `POST /queue/keywords/{hitId}/resolve` | — | — |
| `POST /bulk` | `{action, targets:[{type,id}] (≤100), reason}` — actions incl. TAKEDOWN/RESTORE, per-target results | **SU** |

Render the merged queue with a per-row **source badge**; bulk returns
per-target `{outcome, error}` — show a result list, never a bare toast.

### 4.3a Automated moderation — base `/api/v1/admin/moderation/{review,settings,model}`

`AdminAutoModerationController` (ADMIN, MODERATOR) · `AdminModerationSettingsController`
(ADMIN, MODERATOR; kill switch ADMIN) · `AdminModerationModelController` (ADMIN; dataset writes
MODERATOR). Full reference: [../moderation/api.md](../../moderation/api.md).

| Method + path | Key params | SU |
|---------------|-----------|----|
| `GET /review` | `status`, `entityType`, `slaBreached`, `sort` (`risk`\|`oldest`), `page`, `pageSize` | — |
| `GET /review/{caseId}` | full text per field + per-label scores + the bands that applied | — |
| `POST /review/{caseId}/decide` | `{action: APPROVE\|REJECT, reason, teachModel}` | — |
| `POST /review/bulk` | `{action, caseIds (≤100), reason, teachModel}` | **SU** |
| `POST /review/{caseId}/rescore` | re-runs the classifier on a stored case | — |
| `GET /review/metrics` | `windowHours`; also ANALYST | — |
| `GET /settings` · `GET /settings/thresholds` | resolved effective policy + model health | — |
| `PUT /settings/thresholds` · `PUT /settings/hold-durations` | partial patches | **SU** |
| `POST /settings/dry-run` | replays proposed bands against stored scores; writes nothing | — |
| `PUT/DELETE /settings/raw{,/{key}}` · `POST /settings/reset` | ADMIN only | **SU** |
| `GET/POST/DELETE /model/training-examples{,/word,/{id}}` | dataset browser + "teach it" forms | — |
| `POST /model/training-examples/import` | multipart CSV (`kind=sentences\|words`, `dryRun`, `allowPartial`) — bulk teach + optional blocklist push | **SU** |
| `GET/POST/DELETE /model/golden-cases{,/{id}}` | regression suite | — |
| `GET /model/versions` | also ANALYST | — |
| `POST /model/retrain` · `/versions/{id}/promote` · `/rollback` | | **SU** |
| `POST /model/score-probe` | `{text}` → live per-label scores | — |

Two UI notes that matter:

- **Do not merge this queue into §4.3's.** They ask different questions — that one
  triages reports about live content, this one releases or buries content nobody
  but its author has seen. Different row shape, different mental model, different
  consequence of a mis-click.
- **Wire `POST /settings/dry-run` into the threshold editor before the save
  button.** It replays proposed bands against real stored scores and returns
  exactly which past decisions would flip. Shipping a threshold slider without it
  means every tune is a guess with a production blast radius.

Render each case's per-label scores against the returned `thresholds.bands` — a
0.42 `insult` inside a `0.30/0.80` band reads completely differently from a 0.42
`threat` inside `0.15/0.50`, and the band is the only thing that makes the number
meaningful.

### 4.4 Content moderation — base `/api/v1/admin/content`

`AdminContentController` — ADMIN, MODERATOR.

| Method + path | Key params | SU |
|---------------|-----------|----|
| `GET /posts` · `GET /posts/{postId}` | filters + cursor | — |
| `POST /posts/{postId}/remove` | `{reason, reportId?}` | **SU** |
| `POST /posts/{postId}/restore` | — | — |
| `DELETE /comments/{commentId}` · `/stories/{storyId}` | `{reason}` | **SU** |
| `DELETE /highlights/{highlightId}/stories/{storyId}` | — | — |
| `GET /blocklist` · `PATCH /blocklist/{id}` · `DELETE /blocklist/{id}` · `POST /blocklist/test` | — | — |
| `POST /blocklist` | keyword body — BLOCK severity gates publishing platform-wide | **SU** |

Post remove is reversible (`restore` writes PUBLISHED back); comment/story
deletes are **hard deletes** — danger-zone confirm (§6).

### 4.5 Safety & reports — base `/api/v1/admin/safety`

`AdminSafetyController` — ADMIN, MODERATOR (report reads also SUPPORT).
Doc: [safety-reports.md](../trust-safety/safety-reports.md).

| Method + path | Key params | SU | Who |
|---------------|-----------|----|-----|
| `GET /reports` · `GET /reports/{id}` | `state, targetType, reason, targetId, from, to` + pageable | — | + SUPPORT |
| `POST /reports/{id}/triage` · `/dismiss` | `{note}` | — | ADMIN, MODERATOR |
| `POST /reports/{id}/action` | `{resolution, note}` — `WARNING_ISSUED`/`CONTENT_REMOVED`/`ACCOUNT_SUSPENDED`/`NO_ACTION` | **SU** | ADMIN, MODERATOR |
| `POST /appeals/{reportId}/uphold` · `/reverse` | `{note}` | **SU** | ADMIN, MODERATOR |
| `POST /users/{userId}/strikes` · `DELETE /strikes/{strikeId}` | `{reportId, reason}` | **SU** | ADMIN, MODERATOR |
| `GET /strikes` | `userId, active` + pageable | — | — |
| `GET /users/{userId}/record` · `/users/{userId}/consent` | — | — | — |
| `GET /stats/blocks` · `GET /analytics` | `from, to` | — | — |

Strikes decay after 90 days — show the expiry. Report detail includes frozen
evidence + same-target siblings; render the `note` trail.

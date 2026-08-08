# Pages — research, Q&A, tags, sounds, media & knowledge

Part of the [admin dashboard frontend guide](README.md).
Legend: **SU** = step-up required (§[auth-and-roles.md](auth-and-roles.md)) ·
roles in the *Who* column are the `hasRole`/`hasAnyRole` grants as coded ·
list endpoints paginate per [conventions.md](conventions.md).
Wire-level request/response JSON: [../api/](../api/README.md).

Section docs: [../content/](../content/README.md).

---

### 4.6 Research — base `/api/v1/admin/research`

`AdminResearchController` — ADMIN, MODERATOR. Doc: [research-qna.md](../content/research-qna.md).

| Method + path | Key params | SU |
|---------------|-----------|----|
| `GET /api/v1/admin/research` · `/top` · `/flags` · `/{id}` · `/{id}/downloads` | `status, q, authorId` + pageable | — |
| `POST /{id}/unpublish` · `/{id}/retract` | `{reason}` | **SU** |
| `DELETE /{id}` | `{reason}` | **SU** — hard delete, danger zone |
| `POST /{id}/flags` · `POST /flags/{flagId}/resolve` | flag body / note | — |

### 4.7 QnA — base `/api/v1/admin/qna`

`AdminQnaController` — ADMIN, MODERATOR.

| Method + path | SU |
|---------------|----|
| `GET /questions` (`status, q, authorId` + pageable) | — |
| `POST /questions/{id}/close` · `/reopen` · `/archive` | — |
| `DELETE /questions/{id}` · `DELETE /answers/{answerId}` (body `{reason}`) | **SU** |

### 4.8 Tags & trending — bases `/api/v1/admin/tags`, `/api/v1/admin/trending`

`TagAdminController` + `AdminTrendingController` — ADMIN only.

| Method + path | SU | Notes |
|---------------|----|-------|
| `POST /api/v1/admin/tags/backfill-posts` | — | Full token-range scan; **trending counter bumps are NOT idempotent — never re-run casually**. Danger-zone confirm (§6). |
| `POST /api/v1/admin/tags/{tag}/hide` · `DELETE …/hide` | — | `scope` param |
| `POST /api/v1/admin/tags/merge` | — | — |
| `GET /api/v1/admin/trending/overrides` · `DELETE …/overrides/{id}` | — | — |
| `POST /api/v1/admin/trending/overrides` | **SU** | Editorializes a public surface — say so in the confirm |
| `POST /api/v1/admin/trending/rebuild` | — | — |

### 4.10 Sounds — base `/api/v1/admin/sounds`

`AdminSoundController` — ADMIN, MODERATOR. Doc: [sound-library.md](../content/sound-library.md).

| Method + path | Key params | SU |
|---------------|-----------|----|
| `GET /api/v1/admin/sounds` | `status` (default PENDING_REVIEW), cursor + pageSize | — |
| `GET /status-counts` · `/uploaders/{userId}` · `/{id}` · `/trending` | — | — |
| `POST /{id}/approve` · `/reject` · `/archive` · `/restore` | `{reason}` on reject | — |
| `POST /{id}/takedown` | `{reason}` (rights/DMCA) | **SU** |
| `POST /{id}/category` · `PATCH /{id}` (metadata) · `POST /{id}/trending-exclude` · `POST /import` | — | — |
| `DELETE /{id}` | — | **SU** — hard delete, danger zone |

The legacy stray `POST /api/v1/sounds/{id}/approve` is deprecated (successor
`Link` header) — the dashboard must call only the `/admin/sounds` routes. Same
for the channel-verify stray `PUT /api/v1/channels/{id}/verified`.

### 4.11 Media & storage — bases `/api/v1/admin/media`, `/api/v1/admin/storage`

`AdminMediaController`, `AdminStorageController` — ADMIN only. Doc:
[media-storage.md](../content/media-storage.md).

| Method + path | Key params | SU |
|---------------|-----------|----|
| `GET /api/v1/admin/media` · `/{assetId}` · `/status-summary` · `/ops` | `status, type, ownerId` + pageable | — |
| `POST /{assetId}/reprocess` | — | — |
| `DELETE /{assetId}` | `{reason}` | **SU** — deletes all R2 renditions, danger zone |
| `POST /purge-raw/run` | `dryRun` (**default `true`**) | — |
| `POST /reconcile` | `dryRun` (**default `true`**) — S3 LIST diff; `dryRun=false` deletes orphans | **SU** |
| `GET /quotas` · `PUT /quotas/{role}` | per-role daily upload quotas | **SU** on PUT |
| `GET /api/v1/admin/storage/usage` | `top` (default 20) | — |

Reconcile/purge-raw responses echo `dryRun` and carry explanatory `note`
fields — render them, and default the UI toggle to dry-run.

### 4.19 Knowledge vocabulary — base `/api/v1/admin/knowledge`

`AdminKnowledgeController` — **ADMIN only**. Doc: [knowledge-vocabulary.md](../content/knowledge-vocabulary.md).

| Method + path | SU |
|---------------|----|
| `GET /topics` · `GET /madhhabs` (trilingual labels + usage counts) | — |
| `POST /topics` · `POST /madhhabs` (`{nameEn, nameAr, nameCkb}`) | **SU** |
| `PATCH /topics/{id}` · `PATCH /madhhabs/{id}` | **SU** |
| `POST /topics/{id}/retire` · `POST /madhhabs/{id}/retire` (soft-retire — never hard delete) | **SU** |
| `POST /cache/evict` | — |

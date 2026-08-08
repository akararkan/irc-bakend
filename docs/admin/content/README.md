# Content — the catalogs

The libraries and pipelines that hold what users create, as opposed to the
queues that judge it (those are [`../trust-safety/`](../trust-safety/README.md)).

| Doc | What it answers |
|---|---|
| [research-qna.md](research-qna.md) | Research pipeline and DOI, download analytics, Q&A oversight, tags & trending admin |
| [sound-library.md](sound-library.md) | The reusable audio catalog — **admin-curated only**, category curation, full state machine, official/platform sounds, trending oversight, rights/DMCA takedown, index health |
| [media-storage.md](media-storage.md) | The media pipeline status board, failed-media queues, dedup and tiers, storage usage, R2 lifecycle, per-role upload quotas |
| [knowledge-vocabulary.md](knowledge-vocabulary.md) | The curated Topics & Madhhabs taxonomy (trilingual) and the curation console |

## Recent policy change worth knowing

**Sounds are admin-curated only** (2026-08-08). There is no end-user upload
path any more — the library grows through `POST /api/v1/admin/sounds` and the
bulk `/import`, both landing straight on `APPROVED`. The legacy
`POST /api/v1/sounds` route survives as a deprecated `ADMIN`/`MODERATOR`-only
alias. Details in [sound-library.md](sound-library.md).

API reference: [`../api/sounds-media.md`](../api/sounds-media.md) ·
[`../api/research-qna-tags.md`](../api/research-qna-tags.md).

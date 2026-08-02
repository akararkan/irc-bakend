# Knowledge Vocabulary — Admin Dashboard Section 16

The platform's **controlled reference vocabulary** — the two curated lookup lists
that power the profile "specialization" and "madhhab" pickers: **Topics** (fields of
knowledge) and **Madhhabs** (schools of Islamic jurisprudence). Both are trilingual
(English / Arabic / Central Kurdish `ckb`). This is a small, high-trust taxonomy that
sits at the identity core of a scholarship platform — and today it can **only** be
changed by a database migration (a code deploy). This section designs the admin
**vocabulary-curation console** that lets a trusted admin manage it directly.

Tag legend and ground rules: [README.md](README.md). Underlying mechanics:
`app/knowledge` (`KnowledgeVocabularyService`, `Topic`, `Madhhab`,
`KnowledgeController`). Related: [user-administration.md](user-administration.md)
(the `madhhab`/`specializations` profile fields that reference this),
[research-qna.md](research-qna.md) (the *tag/keyword* trending subsystem — a
different, usage-driven vocabulary), [../user/users.md](../user/users.md).

Status legend: **[EXISTS]** = real today · **[PARTIAL]** = data exists, surface
missing · **[PLANNED]** = proposed here.

---

## 1. Purpose & scope

| In scope | Out of scope (see) |
|----------|--------------------|
| Curate **Topics** and **Madhhabs** (add / edit trilingual labels / retire) | Free-form hashtags & trending tags → [research-qna.md](research-qna.md), [search-feed-trending.md](search-feed-trending.md) |
| Usage/impact analysis before an edit (how many profiles reference a row) | The Islamic-community **seed data** itself (migrations) → repo `resources/db` |
| Cache-invalidation control on edit | Sound categories → [sound-library.md](sound-library.md) |

> **Two different "vocabularies," don't confuse them.** This section is the
> **fixed, curated** taxonomy (Topics/Madhhabs — a few dozen rows, migration-managed,
> identity-critical). The **tag/keyword/trending** subsystem (research-qna) is
> **open, usage-driven** (users coin hashtags, popularity ranks them). Different
> stores, different governance. This doc owns only the curated one.

---

## 2. Ground truth — how the vocabulary works today

Two JPA tables, both trivially shaped, both **read-only from the app's perspective**:

| Table | Entity | Columns |
|-------|--------|---------|
| `topics` | `knowledge/entity/Topic` | `id`, `name_en`, `name_ar`, `name_ckb` |
| `madhhabs` | `knowledge/entity/Madhhab` (`@BatchSize(50)`) | `id`, `name_en`, `name_ar`, `name_ckb` |

- **No write path exists.** `TopicRepository` / `MadhhabRepository` are plain
  `JpaRepository`s with **no custom methods and no `save`/`delete` callers anywhere**.
  The only writes to these tables are **DB migrations**. **[EXISTS — read-only]**
- **Reads are cached in Redis:** `KnowledgeVocabularyService` —
  `@Cacheable("knowledge-topics")` / `@Cacheable("knowledge-madhhabs")`. The service
  Javadoc notes both vocabularies "change only via migrations." **[EXISTS]**
- **Public endpoints** (no auth): `GET /api/v1/topics?q=` and `GET /api/v1/madhhabs?q=`
  (`KnowledgeController`) — optional `q` does an in-memory `contains` match across
  en/ar/ckb over the ≈dozens of rows (deliberately not Elasticsearch). Blank `q`
  returns the full list. **[EXISTS]**
- **Where it's consumed:** `UserProfileServiceImpl` calls `findById` to **validate**
  a profile's `madhhab` and topic `specializations` selections (a user can only pick a
  real row). So editing this vocabulary directly changes what identities users can
  express. **[EXISTS]**
- **No `@Scheduled`, no events, no `@PreAuthorize`, no admin/moderation surface** of
  any kind in the module.

---

## 3. The gap & why it deserves an admin surface

Adding a school of thought, a field of knowledge, or fixing an Arabic label today
requires **writing a migration and deploying** — a developer task on a domain
(Islamic scholarship taxonomy) where the *right* editor is a knowledgeable admin, not
an engineer. Because the tables are tiny and the reads are cached, a safe admin CRUD
is low-risk to build and high-value: it moves curation from the deploy pipeline to a
governed dashboard action, with audit and cache-invalidation handled for the admin.

---

## 4. Dashboard views / widgets **[PLANNED]**

| Widget | Content |
|--------|---------|
| **Vocabulary browser** | two lists (Topics, Madhhabs); each row shows all three labels (en/ar/ckb) + a **usage count** (how many `user_profiles` reference it) |
| **Editor** | inline edit of the three labels; add-row form; "retire" (soft) rather than hard-delete when a row is in use |
| **Impact preview** | before edit/retire, show affected profiles — "142 profiles list this madhhab" — so a rename never silently orphans identities |
| **Cache state** | last cache-eviction timestamp; a "reads are cached" note so an admin understands why an edit needs the evict (§5) |

---

## 5. Admin actions **[PLANNED]**

All under `/api/v1/admin/**` (double-gated). This is high-trust curation — step-up on
writes and a full audit row each.

| # | Action | Endpoint | Danger | Step-up | Audit action |
|---|--------|----------|--------|---------|--------------|
| K1 | List with usage counts | `GET /api/v1/admin/knowledge/{topics\|madhhabs}` | read | no | interceptor |
| K2 | Add a row | `POST /api/v1/admin/knowledge/{topics\|madhhabs}` `{nameEn,nameAr,nameCkb}` | medium | **yes** | `ADMIN_VOCAB_ADD` |
| K3 | Edit labels | `PATCH .../{id}` | medium | **yes** | `ADMIN_VOCAB_EDIT` |
| K4 | Retire (soft) | `POST .../{id}/retire` | **high** (identity-affecting) | **yes** | `ADMIN_VOCAB_RETIRE` |
| K5 | Evict caches | `POST /api/v1/admin/knowledge/cache/evict` | low | no | `ADMIN_VOCAB_CACHE_EVICT` |

**Implementation notes for the build:**
- Every write **must evict** the matching `@Cacheable` region (`knowledge-topics` /
  `knowledge-madhhabs`) or the public `GET /topics|/madhhabs` serves stale data — this
  is the one non-obvious correctness requirement. K2–K4 should evict automatically;
  K5 is the manual escape hatch.
- **Prefer retire over delete.** A hard delete of an in-use row would fail the
  `findById` validation on every profile that references it. Add an `active` flag
  (or an `archived_at`) and filter it out of the pickers while keeping existing
  references valid — mirror the "don't orphan" discipline used elsewhere on the platform.
- Uniqueness/normalization: trim + case-fold `name_en` on add to avoid duplicate
  "Hanafi"/"hanafi" rows.

---

## 6. Permissions & safety notes

- **Identity-critical, low-volume, high-trust.** These rows define how scholars
  describe themselves; an errant edit is visible platform-wide instantly (cached
  reads aside). Writes are step-up-gated and audited; retire (not delete) is the
  default removal.
- **Migrations remain the source of truth for seeds.** Admin edits and the
  migration-managed seed set can drift; the build should keep migrations as the
  bootstrap and treat admin edits as deltas layered on top (document the reconciliation
  policy so a future migration doesn't clobber admin additions).
- **Public read stays public.** `GET /topics|/madhhabs` is intentionally unauthenticated
  (pickers load pre-login); the admin surface adds *write*, it does not restrict the
  existing read.

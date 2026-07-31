# Knowledge Taxonomy Lookup — Topics & Madhhabs

Read-only lookup/search over the two reference vocabularies behind profile
specializations and madhhab selection. **New endpoints** — these tables
previously had no API at all. This page covers the search mechanism; the
full topic reference (consumer endpoints, DTOs, content management) is
[../knowledge/taxonomy.md](../knowledge/taxonomy.md).

```
GET /api/v1/topics?q={text}
GET /api/v1/madhhabs?q={text}
```

**Auth:** none — public reads (same policy as `/tags/*`, and usable during
onboarding before a token exists).

| Param | Notes |
|---|---|
| `q` | Optional. Blank/omitted → the **full vocabulary** (the common picker case). Non-blank → case-insensitive contains-match across **all three language columns** |

**Response `200`:**

```json
[
  { "id": 1, "nameEn": "Fiqh",   "nameAr": "الفقه",   "nameCkb": "فیقھ" },
  { "id": 2, "nameEn": "Tafsir", "nameAr": "التفسير", "nameCkb": "تەفسیر" }
]
```

A query matches if it appears in `nameEn`, `nameAr` **or** `nameCkb` — so
`فقه`, `fiqh` and a Kurdish spelling all resolve the same row. Unicode is
matched as-is (no transliteration), consistent with the
[tag subsystem's rule](../platform/tags.md).

## Why this is deliberately NOT Elasticsearch

These are tiny, fixed vocabularies (dozens of rows, effectively immutable).
The implementation is one bounded table read + an in-memory filter:

- **Complexity:** O(rows × 3 columns) with rows ≈ constant → effectively
  **O(1)**. An ES round-trip would be slower than the whole operation.
- **No index to keep in sync**, no reindex endpoint, no eventual
  consistency — the picker always reflects the table.
- Works identically for Arabic/Kurdish input with zero analyzer
  configuration.

The right tool for a 30-row table is a `List.filter`, not a search cluster.
If these vocabularies ever grow into user-generated taxonomies, promote
them into the tag subsystem — not into their own ES index.

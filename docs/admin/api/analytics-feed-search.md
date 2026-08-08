# Admin API Reference — Analytics, Feed Tuning & Search Ops

Complete request/response reference for the five controllers behind the analytics, feed-tuning
and search-operations sections of the admin dashboard:

| Controller | Base path | Section |
|---|---|---|
| `app/admin/analytics/AdminAnalyticsController` | `/api/v1/admin/analytics` | KPIs, series, funnel/retention, rollups, raw events, anomalies, export |
| `app/admin/feed/AdminFeedController` | `/api/v1/admin/feed` | Ranking weights, per-user explain, runtime config + shadow preview, affinity |
| `app/admin/feed/AdminSuggestionsController` | `/api/v1/admin/suggestions` | PYMK knob registry + per-user explain |
| `app/admin/search/AdminSearchOpsController` | `/api/v1/admin/search` | Index health/drift, query analytics, async reindex-all |
| `app/common/search/controller/SearchAdminController` | `/api/v1/admin/search` | The 7 synchronous per-index reindex hooks |

Concepts and data-model background: [analytics-kpis.md](../platform/analytics-kpis.md),
[search-feed-trending.md](../platform/search-feed-trending.md). Dashboard wiring:
[frontend-dashboard-guide.md](../frontend/README.md).

---

## Shared conventions

- **Auth** — `Authorization: Bearer <JWT>` on every call. Roles come from `@PreAuthorize`
  (class-level default, overridable per method). `ANALYST` holds read-only grants on the
  observability endpoints; every mutation is `ADMIN`.
- **Step-up** — endpoints marked `@RequiresStepUp` additionally need a fresh step-up marker
  (`stepup:{userId}` in Redis, armed via `POST /api/v1/security/step-up`). Missing marker →
  `403` with `errorCode: "STEP_UP_REQUIRED"`.
- **Errors** — every error uses the canonical `ApiErrorResponse` envelope
  (`timestamp`, `status`, `error`, `message`, `path`, `errorCode`, `details?`, `fieldErrors?`,
  `traceId`). See [frontend-error-handling.md](../../errors/frontend-error-handling.md).
  Per-endpoint **Errors** lists below name only the domain `errorCode`s; `401` (no/expired
  token) and `403` (role / step-up) apply everywhere.
- **`null` is never on the wire** — global `spring.jackson.default-property-inclusion: non_null`.
  Any `null` bean field **or `null` map value** is omitted from the JSON. E.g. `stored` in
  `GET /admin/feed/config` disappears entirely when no override row exists, `canonicalCount`
  is absent for Cassandra-canonical indices in `GET /admin/search/health`, and `authorId` is
  absent on `CHANNEL_POST` explain rows.
- **Lombok getter-name mangling** — JPA entities serialized directly (`AnalyticsAlertConfig`,
  `FeedRankingConfig`) expose fields whose names start with a single lowercase letter +
  uppercase (`zWarn`, `wLike`, …) as **all-lowercase** JSON keys (`"zwarn"`, `"wlike"`), because
  Jackson mangles `getZWarn()`/`getWLike()`. Request-body *records* (`AlertConfigRequest`,
  `FeedConfigPatch`, …) and the `Knobs` record keep exact camelCase component names
  (`"zWarn"`, `"wLike"`). Requests are camelCase; entity responses are lowercase for those keys.
- **Dates** — `LocalDateTime` serializes as `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` (UTC);
  `Instant` as ISO-8601 with `Z`. Day strings are `YYYY-MM-DD`, cohort months `YYYY-MM`.
- **Limits** — every `limit`/`size` param is clamped to `[1, 100]` (`Pages.clamp`); series
  `window` params clamp to `[1, 365]` days.
- **Audit** — reads of behavioral/private data and every mutation write an `ADMIN_*` audit row
  via `AdminAuditor` (noted per endpoint).

---

## Overview & tiles

### GET /api/v1/admin/analytics/overview
Platform-wide KPI tiles, all computed live from the canonical stores.

**Access**: `ADMIN` or `ANALYST` (class-level).

**Request body**: None.

**Response** — `200`, keys in this order:

```json
{
  "totalUsers": 18234,
  "signupsToday": 41,
  "publishedResearch": 950,
  "totalResearch": 1310,
  "questions": 4620,
  "conversationsByType": { "DIRECT": 8123, "GROUP": 640, "CHANNEL": 212 },
  "liveNow": 3,
  "storageBytes": 182736450816,
  "giftCoinsAllTime": 54120,
  "openReports": 17,
  "appealBacklog": 2,
  "dauToday": 1290,
  "mau30d": 9412,
  "onlineNow": 342
}
```

Notes: `openReports` = reports in `SUBMITTED`+`TRIAGED`; `appealBacklog` = `APPEALED`;
`dauToday`/`mau30d` come from the collector (`analytics_dau_by_day`); `mau30d` is `-1` when
Cassandra is unreachable (tile should render "unavailable", not 0); `onlineNow` is the live
presence count.

**Errors**: none beyond the shared 401/403.

---

## Content & engagement series

### GET /api/v1/admin/analytics/content
Content-production per-day series over a trailing window.

**Access**: `ADMIN` or `ANALYST`.

**Params**

| Param | Type | Default | Constraints |
|---|---|---|---|
| `window` | int | `30` | clamped to 1–365 (days) |

**Request body**: None.

**Response** — `200`:

```json
{
  "windowDays": 3,
  "signupsPerDay":   { "2026-08-05": 38, "2026-08-06": 44, "2026-08-07": 12 },
  "researchPerDay":  { "2026-08-05": 6, "2026-08-07": 2 },
  "questionsPerDay": { "2026-08-06": 31, "2026-08-07": 9 },
  "reelsPerDay":     { "2026-08-05": 84, "2026-08-06": 91, "2026-08-07": 27 },
  "postCreatesPerDay": { "2026-08-05": 210, "2026-08-06": 195, "2026-08-07": 63 },
  "note": "posts/stories have no historical date bucket — postCreatesPerDay is collector-sourced and starts at collector deployment."
}
```

Notes: `signupsPerDay`/`researchPerDay`/`questionsPerDay` are SQL group-bys — days with zero
rows are **absent**, not zero-filled. `reelsPerDay` covers every day of the window; a day is
`-1` when its Cassandra partition read failed. `postCreatesPerDay` is the zero-filled
collector series `activity.POST_CREATED`.

**Errors**: none.

### GET /api/v1/admin/analytics/engagement
Collector-backed engagement series (never reads the private per-user activity store).

**Access**: `ADMIN` or `ANALYST`.

**Params**: `window` — int, default `30`, clamped 1–365.

**Request body**: None.

**Response** — `200`, all series zero-filled per day:

```json
{
  "windowDays": 3,
  "dauPerDay":         { "2026-08-05": 1210, "2026-08-06": 1275, "2026-08-07": 640 },
  "loginsPerDay":      { "2026-08-05": 1802, "2026-08-06": 1911, "2026-08-07": 872 },
  "failedLoginsPerDay":{ "2026-08-05": 41, "2026-08-06": 39, "2026-08-07": 12 },
  "activityPerDay":    { "2026-08-05": 15230, "2026-08-06": 16110, "2026-08-07": 5920 },
  "reactionsPerDay":   { "2026-08-05": 4210, "2026-08-06": 4460, "2026-08-07": 1720 },
  "commentsPerDay":    { "2026-08-05": 980, "2026-08-06": 1015, "2026-08-07": 391 },
  "reelWatchesPerDay": { "2026-08-05": 6100, "2026-08-06": 6480, "2026-08-07": 2205 },
  "searchesPerDay":    { "2026-08-05": 830, "2026-08-06": 872, "2026-08-07": 310 },
  "source": "analytics_metric_daily + analytics_dau_by_day — the parallel fan-in collector; the private per-user activity store is never scanned (analytics-kpis.md §12 privacy contract). Series begin at collector deployment."
}
```

**Errors**: none.

### GET /api/v1/admin/analytics/trending
Admin wrapper over the trending-tag snapshot (`trending_tags`, rebuilt by `TrendingTagJob`).

**Access**: `ADMIN` or `ANALYST`.

**Params**

| Param | Type | Default | Constraints |
|---|---|---|---|
| `scope` | string | `ALL` | `ALL` \| `QUESTION` \| `RESEARCH` \| `POST` \| `REEL`; anything else silently falls back to `ALL` |
| `limit` | int | `20` | clamped 1–100 |

**Request body**: None.

**Response** — `200`, array ordered by rank ASC (field order per row not guaranteed —
`Map.of` construction):

```json
[
  { "rank": 1, "tag": "machinelearning", "usageCount": 412, "scope": "ALL" },
  { "rank": 2, "tag": "genetics",        "usageCount": 388, "scope": "ALL" }
]
```

**Errors**: none (bad scope falls back, never 400).

---

## Generic series

### GET /api/v1/admin/analytics/series
Any metric by name — rollup rows (durable) win over live counters per day.

**Access**: `ADMIN` or `ANALYST`.

**Params**

| Param | Type | Default | Constraints |
|---|---|---|---|
| `metric` | string | — required | 1–80 chars matching `[A-Za-z0-9._-]+` |
| `window` | int | `30` | clamped 1–365 |

Useful metric names: `login.success`, `login.failed`, `activity.total`,
`activity.POST_CREATED`, `activity.POST_REACTION`, `activity.POST_COMMENT`,
`activity.REEL_WATCH`, `activity.GLOBAL_SEARCH`, `signups`, `research.created`,
`questions.created`, `reports.submitted`, plus `events.{event_type}` rollups.

**Request body**: None.

**Response** — `200`:

```json
{
  "metric": "login.success",
  "windowDays": 3,
  "series": { "2026-08-05": 1802, "2026-08-06": 1911, "2026-08-07": 872 },
  "source": "analytics_metric_rollup (durable, rollup-job) merged over analytics_metric_daily (live counters) — rollup wins per day."
}
```

**Errors**
- `400 INVALID_METRIC` — "Metric must be 1-80 chars of letters, digits, dot, dash or underscore."

---

## Funnel & retention

### GET /api/v1/admin/analytics/funnel
Activation funnel for one signup-month cohort: signed-up → first-seen → profile-completed →
first-follow → first-content (milestones from `user_first_events`, set-once).

**Access**: `ADMIN` or `ANALYST`.

**Params**

| Param | Type | Default | Constraints |
|---|---|---|---|
| `cohort` | string | current month | `YYYY-MM` |

**Request body**: None.

**Response** — `200` (cohort scan capped at 50 000 ids; empty cohort → all milestone counts 0):

```json
{
  "cohort": "2026-07",
  "signedUp": 1240,
  "firstSeen": 1102,
  "profileCompleted": 640,
  "firstFollow": 588,
  "firstContent": 305,
  "note": "Milestones are set-once from user_first_events; users who signed up before the funnel tracker deployed only accrue milestones going forward."
}
```

**Errors**
- `400 INVALID_COHORT` — "cohort must be YYYY-MM."

### GET /api/v1/admin/analytics/retention
Weekly signup-cohort retention grid, computed by `WeeklyCohortJob` (Mondays 03:10 UTC).

**Access**: `ADMIN` or `ANALYST`.

**Params**

| Param | Type | Default | Constraints |
|---|---|---|---|
| `weeks` | int | `12` | clamped 1–26 (trailing complete cohort weeks, current week excluded) |

**Request body**: None.

**Response** — `200`. One row per computed cell; `cohortWeek` is the ISO Monday of the signup
week, `weekOffset` 0 = signup week; `retentionPct` = `activeCount / cohortSize` rounded to 2
decimals. Row key order is not guaranteed (`Map.of`).

```json
{
  "weeks": 12,
  "grid": [
    { "cohortWeek": "2026-07-27", "weekOffset": 0, "cohortSize": 310, "activeCount": 268, "retentionPct": 86.45 },
    { "cohortWeek": "2026-07-27", "weekOffset": 1, "cohortSize": 310, "activeCount": 141, "retentionPct": 45.48 },
    { "cohortWeek": "2026-07-20", "weekOffset": 2, "cohortSize": 295, "activeCount": 97,  "retentionPct": 32.88 }
  ],
  "computedBy": "WeeklyCohortJob (Mondays 03:10 UTC) over analytics_dau_by_day; POST /rollup/{date}/run does not refresh this grid."
}
```

**Errors**: none.

---

## Rollup & backfill

### POST /api/v1/admin/analytics/rollup/{date}/run
Re-run the idempotent daily rollup for one date (same body the 02:40 UTC job runs — raw-event
type counts, durable copies of the live counters, PG-derived dailies). Safe to repeat.

**Access**: `ADMIN` only. Audit: `ADMIN_ANALYTICS_ROLLUP_RUN`.

**Params**: path `date` — `YYYY-MM-DD`.

**Request body**: None.

**Response** — `200`:

```json
{ "day": "2026-08-05", "metricRowsWritten": 23 }
```

**Errors**
- `400 INVALID_DATE` — "Date must be YYYY-MM-DD."

### POST /api/v1/admin/analytics/backfill
Re-derive rollups for an inclusive date range, ≤ 90 days per call.

**Access**: `ADMIN` only. Audit: `ADMIN_ANALYTICS_BACKFILL`.

**Params**

| Param | Type | Constraints |
|---|---|---|
| `from` | string | required, `YYYY-MM-DD` |
| `to` | string | required, `YYYY-MM-DD`, `>= from`, span ≤ 90 days |

**Request body**: None (query params only).

**Response** — `200` (synchronous — runs day by day before returning):

```json
{ "from": "2026-07-01", "to": "2026-07-31", "daysProcessed": 31, "metricRowsWritten": 713 }
```

**Errors**
- `400 INVALID_DATE` — either bound unparseable.
- `400 INVALID_RANGE` — "'to' must not be before 'from'."
- `400 RANGE_TOO_LARGE` — "Backfill is capped at 90 days per call — split larger ranges."

---

## Raw events sample

### GET /api/v1/admin/analytics/events/sample
Peek at raw `analytics_events` rows. Actor ids are visible here — this is the one analytics
read that is step-up-gated and audited as raw-data access (analytics-kpis.md §12).

**Access**: `ADMIN` only + `@RequiresStepUp`. Audit: `ADMIN_ANALYTICS_RAW_ACCESS` (recorded
even before the read).

**Params**

| Param | Type | Default | Constraints |
|---|---|---|---|
| `day` | string | today (UTC) | `YYYY-MM-DD` |
| `type` | string | — | optional exact `event_type` filter |
| `limit` | int | `50` | clamped 1–100 |

**Request body**: None.

**Response** — `200`. `rows` are raw Cassandra columns (snake_case keys), gathered across the
day's 16 shard partitions (newest-first per shard, not globally time-ordered); `counts` is the
full per-type tally for the day.

```json
{
  "day": "2026-08-07",
  "rows": [
    {
      "event_time": "2026-08-07T09:14:52.113Z",
      "event_type": "GLOBAL_SEARCH",
      "actor_id": "7f2f9d1e-3a44-4b1c-9a5e-2c9a1f0d6b21",
      "target_type": "QUERY",
      "target_id": "crispr",
      "props": { "scope": "ALL", "hits": "14" }
    }
  ],
  "counts": { "GLOBAL_SEARCH": 830, "POST_CREATED": 210, "REEL_WATCH": 6100 }
}
```

**Errors**
- `400 INVALID_DATE` — bad `day`.
- `403 STEP_UP_REQUIRED` — step-up marker missing/expired.

---

## Anomaly config & firings

### GET /api/v1/admin/analytics/alerts-config
All per-metric anomaly thresholds. Metrics without a row use the built-in defaults
(`zWarn 2.5`, `zAlert 3.5`, `minVolume 50`, `enabled true`) over the default watchlist
(`login.success`, `activity.total`, `signups`, `reports.submitted`, `research.created`,
`questions.created`).

**Access**: `ADMIN` or `ANALYST`.

**Request body**: None.

**Response** — `200`. Note the mangled entity keys `zwarn`/`zalert` (see conventions):

```json
[
  { "metric": "login.success", "zwarn": 2.5, "zalert": 3.5, "minVolume": 50, "enabled": true },
  { "metric": "signups",       "zwarn": 2.0, "zalert": 3.0, "minVolume": 10, "enabled": true }
]
```

**Errors**: none.

### PUT /api/v1/admin/analytics/alerts/{metric}
Upsert one metric's anomaly thresholds. Partial: omitted (null) fields keep the current value
(or the built-in default on first create). Adding a row for a new metric also puts it on the
anomaly-scan watchlist.

**Access**: `ADMIN` only. Audit: `ADMIN_ANALYTICS_ALERT_CONFIG`.

**Params**: path `metric` — 1–80 chars matching `[A-Za-z0-9._-]+`.

**Request body** (record `AlertConfigRequest` — exact camelCase keys):

```json
{ "zWarn": 2.0, "zAlert": 4.0, "minVolume": 25, "enabled": true }
```

**Response** — `200`, the saved row (entity — lowercase-mangled keys):

```json
{ "metric": "activity.total", "zwarn": 2.0, "zalert": 4.0, "minVolume": 25, "enabled": true }
```

**Errors**
- `400 INVALID_METRIC` — bad metric name.
- `400 INVALID_THRESHOLDS` — "Thresholds must be positive (minVolume may be 0)." — any provided
  `zWarn`/`zAlert` ≤ 0 or `minVolume` < 0.
- `400 INVALID_THRESHOLDS` — "zAlert must be >= zWarn." — merged result orders the z's wrong.

### GET /api/v1/admin/analytics/anomalies
Anomaly firings from the nightly z-score scan (02:55 UTC), newest first. Severity `WARN`
(z ≥ zWarn) or `ALERT` (z ≥ zAlert, or a flatline-at-0 on a metric whose 28-day mean clears
`minVolume` — the pipeline-dead detector). Each firing also raised an `ADMIN_ANOMALY`
notification.

**Access**: `ADMIN` or `ANALYST`.

**Params**

| Param | Type | Default | Constraints |
|---|---|---|---|
| `page` | int | `0` | ≥ 0 |
| `size` | int | `50` | clamped 1–100 |

**Request body**: None.

**Response** — `200`, a standard Spring Data `Page<MetricAlert>`:

```json
{
  "content": [
    {
      "id": "0c1f7a34-8f4e-4f6d-9a3b-6a1e2d3c4b5a",
      "day": "2026-08-06",
      "metric": "login.success",
      "z": 4.2,
      "value": 640,
      "mean": 1856.4,
      "sd": 289.6,
      "severity": "ALERT",
      "createdAt": "2026-08-07T02:55:11.204Z"
    }
  ],
  "pageable": {
    "pageNumber": 0, "pageSize": 50,
    "sort": { "empty": true, "sorted": false, "unsorted": true },
    "offset": 0, "paged": true, "unpaged": false
  },
  "last": true, "totalElements": 1, "totalPages": 1,
  "size": 50, "number": 0,
  "sort": { "empty": true, "sorted": false, "unsorted": true },
  "first": true, "numberOfElements": 1, "empty": false
}
```

**Errors**: none.

---

## Export

### GET /api/v1/admin/analytics/export
CSV export of one dataset over a trailing window.

**Access**: `ADMIN` or `ANALYST`. Audit: `ADMIN_ANALYTICS_EXPORT`.

**Params**

| Param | Type | Default | Constraints |
|---|---|---|---|
| `dataset` | string | — required | `engagement` \| `signups` \| `content` (case-insensitive) |
| `window` | int | `30` | clamped 1–365 |

**Request body**: None.

**Response** — `200`, `Content-Type: text/csv`,
`Content-Disposition: attachment; filename="engagement-30d.csv"`.

`dataset=engagement` (days from the DAU series — always the full window):

```csv
day,dau,logins,activity
2026-07-09,1180,1720,14980
2026-07-10,1214,1798,15310
```

`dataset=signups` (only days that had signups):

```csv
day,signups
2026-07-09,38
2026-07-10,44
```

`dataset=content` (union of days with research or question rows):

```csv
day,research,questions
2026-07-09,6,28
2026-07-10,4,31
```

**Errors**
- `400 INVALID_DATASET` — "Unknown dataset. Allowed: engagement, signups, content."

---

## Feed weights, explain & affinity

### GET /api/v1/admin/feed/weights
Read-only registry of the compile-time ranking constants (`FeedRankingService.knobRegistry()`).

**Access**: `ADMIN` or `ANALYST` (class-level).

**Request body**: None.

**Response** — `200`:

```json
{
  "formula": "score = (1 + E + A) · F · B",
  "W_LIKE": 3.0, "W_COMMENT": 4.0, "W_SHARE": 5.0, "W_SAVE": 4.0, "W_VIEW": 0.5,
  "W_AFFINITY": 2.0,
  "HALF_LIFE_POST_H": 24.0, "HALF_LIFE_REEL_H": 48.0, "HALF_LIFE_CHANNEL_H": 18.0,
  "HALF_LIFE_RESEARCH_H": 72.0, "HALF_LIFE_QUESTION_H": 48.0,
  "BOOST_MUTUAL": 1.25, "BOOST_SELF": 1.05, "DAMP_CHANNEL": 0.9, "DAMP_EXPLORE": 0.85,
  "MAX_AUTHOR_RUN": 2,
  "tuning": "these are the compile-time DEFAULTS; a runtime override row (feed_ranking_config via FeedTuningService, staged by rolloutPercent) can supersede them — see GET /api/v1/admin/feed/config"
}
```

**Errors**: none.

### GET /api/v1/admin/feed/explain/{userId}
Scored-candidate breakdown of one user's next ranked page — exactly what the live pipeline
would serve them now, with per-item provenance and final score.

**Access**: `ADMIN` or `ANALYST`. Audit: `ADMIN_FEED_EXPLAIN` (behavioral data).

**Params**

| Param | Type | Default | Constraints |
|---|---|---|---|
| `userId` | UUID (path) | — | the viewer to explain |
| `limit` | int | `20` | clamped 1–100 |

**Request body**: None.

**Response** — `200`. `entityType` ∈ `POST | RESEARCH | QUESTION | CHANNEL_POST`; `source` ∈
`FOLLOWING | SELF | CHANNEL | EXPLORE`; `authorId`/`postType` are omitted where null (e.g.
`CHANNEL_POST` rows have no author). `nextCursor` is omitted when the timeline is exhausted.

```json
{
  "userId": "7f2f9d1e-3a44-4b1c-9a5e-2c9a1f0d6b21",
  "items": [
    {
      "id": "b0a6f5c1-2d3e-4f5a-8b9c-0d1e2f3a4b5c",
      "authorId": "9e8d7c6b-5a49-4838-a2b1-c0d9e8f7a6b5",
      "entityType": "POST",
      "postType": "POST",
      "source": "FOLLOWING",
      "rankScore": 14.8231,
      "createdAt": "2026-08-07T06:02:11.512Z"
    },
    {
      "id": "c1b7a6d2-3e4f-5a6b-9c0d-1e2f3a4b5c6d",
      "entityType": "CHANNEL_POST",
      "source": "CHANNEL",
      "rankScore": 6.1042,
      "createdAt": "2026-08-07T05:41:03.207Z"
    }
  ],
  "liveRailSize": 1,
  "nextCursor": "2026-08-07T05:41:03.207Z"
}
```

**Errors**: none beyond 401/403 (unknown user simply yields an empty page).

### GET /api/v1/admin/feed/affinity/{userId}
Viewer→author engagement counters (`user_author_affinity`) feeding the ranking A-term and the
PYMK `INTERACTIONS` source. Behavioral data — audited.

**Access**: `ADMIN` or `ANALYST`. Audit: `ADMIN_FEED_AFFINITY_VIEW`.

**Params**: path `userId` (UUID); `limit` — int, default `50`, clamped 1–100.

**Request body**: None.

**Response** — `200` (row key order not guaranteed — `Map.of`):

```json
[
  { "authorId": "9e8d7c6b-5a49-4838-a2b1-c0d9e8f7a6b5", "interactions": 142 },
  { "authorId": "1a2b3c4d-5e6f-4a8b-9c0d-e1f2a3b4c5d6", "interactions": 87 }
]
```

**Errors**: none.

---

## Feed runtime config & preview

### GET /api/v1/admin/feed/config
The stored override row, the knob set that applies to in-rollout users, and the compile-time
defaults.

**Access**: `ADMIN` or `ANALYST`.

**Request body**: None.

**Response** — `200`. `stored` is the raw `feed_ranking_config` entity (lowercase-mangled
`w*` keys, null knob columns omitted); when no row exists the `stored` key is absent and
`rollout` shows the fallback. `effectiveWhenInRollout`/`defaults` are `Knobs` records
(camelCase, always complete).

```json
{
  "stored": {
    "id": "default",
    "enabled": true,
    "rolloutPercent": 25,
    "wlike": 3.5,
    "wsave": 4.5,
    "maxAuthorRun": 3,
    "updatedAt": "2026-08-06T18:12:40.031Z",
    "updatedBy": "5d4c3b2a-1f0e-4d9c-8b7a-6e5f4d3c2b1a"
  },
  "effectiveWhenInRollout": {
    "wLike": 3.5, "wComment": 4.0, "wShare": 5.0, "wSave": 4.5, "wView": 0.5,
    "wAffinity": 2.0,
    "halfLifePost": 24.0, "halfLifeReel": 48.0, "halfLifeChannel": 18.0,
    "halfLifeResearch": 72.0, "halfLifeQuestion": 48.0,
    "boostMutual": 1.25, "boostSelf": 1.05, "dampChannel": 0.9, "dampExplore": 0.85,
    "maxAuthorRun": 3
  },
  "defaults": {
    "wLike": 3.0, "wComment": 4.0, "wShare": 5.0, "wSave": 4.0, "wView": 0.5,
    "wAffinity": 2.0,
    "halfLifePost": 24.0, "halfLifeReel": 48.0, "halfLifeChannel": 18.0,
    "halfLifeResearch": 72.0, "halfLifeQuestion": 48.0,
    "boostMutual": 1.25, "boostSelf": 1.05, "dampChannel": 0.9, "dampExplore": 0.85,
    "maxAuthorRun": 2
  },
  "rollout": {
    "enabled": true,
    "rolloutPercent": 25,
    "bucketing": "Math.floorMod(userId.hashCode(),100) < rolloutPercent — sticky per user"
  }
}
```

No stored row → `rollout` is
`{ "enabled": false, "rolloutPercent": 0, "bucketing": "no override row — everyone on defaults" }`.

**Errors**: none.

### PATCH /api/v1/admin/feed/config
Partial update of the singleton override row — only named knobs are pinned (null columns keep
inheriting the compile-time default). Live within ≤ 30 s (the per-instance knob cache; the
handling instance invalidates immediately).

**Access**: `ADMIN` only + `@RequiresStepUp`. Audit: `FEED_CONFIG_CHANGED` (before → after).

**Request body** (record `FeedConfigPatch` — every field optional, camelCase):

```json
{
  "enabled": true,
  "rolloutPercent": 25,
  "wLike": 3.5,
  "wSave": 4.5,
  "maxAuthorRun": 3
}
```

All fields: `enabled`, `rolloutPercent`, `wLike`, `wComment`, `wShare`, `wSave`, `wView`,
`wAffinity`, `halfLifePost`, `halfLifeReel`, `halfLifeChannel`, `halfLifeResearch`,
`halfLifeQuestion`, `boostMutual`, `boostSelf`, `dampChannel`, `dampExplore`, `maxAuthorRun`.

**Response** — `200`, the saved entity (lowercase-mangled `w*` keys; unpinned knob columns
omitted; `updatedBy` = acting admin):

```json
{
  "id": "default",
  "enabled": true,
  "rolloutPercent": 25,
  "wlike": 3.5,
  "wsave": 4.5,
  "maxAuthorRun": 3,
  "updatedAt": "2026-08-07T09:30:12.114Z",
  "updatedBy": "5d4c3b2a-1f0e-4d9c-8b7a-6e5f4d3c2b1a"
}
```

**Errors**
- `400 INVALID_ROLLOUT` — "rolloutPercent must be 0-100."
- `400 INVALID_KNOB` — "maxAuthorRun must be 1-10."
- `400 INVALID_KNOB` — "Knob values must be finite, non-negative and ≤ 1000." (any double knob
  NaN/∞/negative/> 1000)
- `403 STEP_UP_REQUIRED`.

### POST /api/v1/admin/feed/preview
Shadow-score a real user's next page under proposed knobs **without persisting anything**:
override ranking with per-item deltas against the baseline ranking.

**Access**: `ADMIN` only (no step-up — nothing is written).

**Request body** (record `FeedPreviewRequest`):

```json
{
  "userId": "7f2f9d1e-3a44-4b1c-9a5e-2c9a1f0d6b21",
  "limit": 10,
  "overrides": { "wShare": 8.0, "dampExplore": 0.5 }
}
```

- `userId` — required.
- `limit` — optional, default `20`, clamped 1–100.
- `overrides` — optional `FeedConfigPatch`, same validation as PATCH. **Materialized over the
  compile-time defaults**, not over the stored row — omitted knobs preview at their `DEFAULTS`
  value. Omit `overrides` entirely to preview baseline vs baseline.

**Response** — `200`. `baselineKnobs` = the knob set this user gets live right now (stored
override if enabled and in-rollout, else defaults); `preview` is the override-ranked page:
`baselineRank` is the item's position in the baseline page (omitted when the item only
appears under the overrides), `rankDelta` = `baselineRank − rank` (positive = moved up).

```json
{
  "userId": "7f2f9d1e-3a44-4b1c-9a5e-2c9a1f0d6b21",
  "baselineKnobs": {
    "wLike": 3.0, "wComment": 4.0, "wShare": 5.0, "wSave": 4.0, "wView": 0.5,
    "wAffinity": 2.0,
    "halfLifePost": 24.0, "halfLifeReel": 48.0, "halfLifeChannel": 18.0,
    "halfLifeResearch": 72.0, "halfLifeQuestion": 48.0,
    "boostMutual": 1.25, "boostSelf": 1.05, "dampChannel": 0.9, "dampExplore": 0.85,
    "maxAuthorRun": 2
  },
  "proposedKnobs": {
    "wLike": 3.0, "wComment": 4.0, "wShare": 8.0, "wSave": 4.0, "wView": 0.5,
    "wAffinity": 2.0,
    "halfLifePost": 24.0, "halfLifeReel": 48.0, "halfLifeChannel": 18.0,
    "halfLifeResearch": 72.0, "halfLifeQuestion": 48.0,
    "boostMutual": 1.25, "boostSelf": 1.05, "dampChannel": 0.9, "dampExplore": 0.5,
    "maxAuthorRun": 2
  },
  "preview": [
    {
      "rank": 0,
      "id": "b0a6f5c1-2d3e-4f5a-8b9c-0d1e2f3a4b5c",
      "authorId": "9e8d7c6b-5a49-4838-a2b1-c0d9e8f7a6b5",
      "entityType": "POST",
      "source": "FOLLOWING",
      "score": 21.4402,
      "baselineRank": 2,
      "rankDelta": 2
    },
    {
      "rank": 1,
      "id": "d2c8b7e3-4f5a-6b7c-0d1e-2f3a4b5c6d7e",
      "authorId": "1a2b3c4d-5e6f-4a8b-9c0d-e1f2a3b4c5d6",
      "entityType": "POST",
      "source": "EXPLORE",
      "score": 9.1130
    }
  ],
  "note": "Shadow-scored twice over the live candidate set — nothing was persisted; the two fetches may see slightly different candidates if content landed in between."
}
```

**Errors**
- `400 MISSING_USER` — "userId is required."
- `400 INVALID_ROLLOUT` / `400 INVALID_KNOB` — invalid `overrides` (same rules as PATCH).

---

## Suggestions (PYMK)

### GET /api/v1/admin/suggestions/knobs
Read-only registry of the friend-suggestion engine's sources and weight constants
(`FriendSuggestionService.knobRegistry()`). Recompile-only — no runtime tuning surface exists.

**Access**: `ADMIN` or `ANALYST` (class-level).

**Request body**: None.

**Response** — `200`:

```json
{
  "sources": ["GRAPH", "CONTACTS", "MESSAGING", "GROUPS", "INTERACTIONS", "AFFILIATION"],
  "W_MUTUAL": 3.0, "MUTUAL_CAP": 15,
  "W_CONTACT": 12.0, "W_CONTACT_BIDIR": 6.0,
  "W_DM": 10.0,
  "W_GROUP": 2.5, "GROUP_CAP": 4,
  "W_AFFINITY": 1.5,
  "W_INSTITUTION": 4.0, "W_LOCATION": 2.5,
  "W_SPECIALIZATION": 1.5, "SPECIALIZATION_CAP": 3,
  "W_LANGUAGE": 0.5, "W_BADGE": 0.75, "W_COMPLETE": 0.75,
  "MIN_SCORE": 2.0,
  "MAX_SUGGESTIONS_TO_STORE": 50,
  "DIVERSITY_HEAD": 20,
  "CANDIDATE_CAP": 300,
  "tuning": "recompile-only — no runtime config surface exists by design"
}
```

**Errors**: none.

### GET /api/v1/admin/suggestions/explain/{userId}
The stored suggestion set for one user (top 50, score DESC from
`friend_suggestions_by_user`) plus their persistent dismissals.

**Access**: `ADMIN` or `ANALYST`.

**Params**: path `userId` (UUID). No query params.

**Request body**: None.

**Response** — `200`. `storedScore` is the integer clustering score; `reason` is the
human-readable top-3 signal labels joined with " · " (fallback `"Suggested for you"`).

```json
{
  "userId": "7f2f9d1e-3a44-4b1c-9a5e-2c9a1f0d6b21",
  "suggestions": [
    {
      "candidateId": "9e8d7c6b-5a49-4838-a2b1-c0d9e8f7a6b5",
      "storedScore": 34,
      "reason": "5 mutual follows · in your contacts · same institution",
      "computedAt": "2026-08-06T22:10:05.881Z"
    },
    {
      "candidateId": "1a2b3c4d-5e6f-4a8b-9c0d-e1f2a3b4c5d6",
      "storedScore": 12,
      "reason": "you message each other",
      "computedAt": "2026-08-06T22:10:05.881Z"
    }
  ],
  "dismissedCandidateIds": ["3c4d5e6f-7a8b-4c9d-0e1f-2a3b4c5d6e7f"]
}
```

**Errors**: none.

---

## Search indices & health

### GET /api/v1/admin/search/indices
Existence + document count for the 8 platform ES indices (`irc-posts`, `irc-qna`,
`irc-answers`, `irc-research`, `irc-users`, `irc-channels`, `irc-sounds`,
`irc-chat-messages`).

**Access**: `ADMIN` or `ANALYST` (method override; the controller class default is `ADMIN`).

**Request body**: None.

**Response** — `200`. `docCount` only when the index exists; an ES failure replaces
`exists`/`docCount` with `error` for that row.

```json
[
  { "index": "irc-posts",    "exists": true, "docCount": 15230 },
  { "index": "irc-qna",      "exists": true, "docCount": 4620 },
  { "index": "irc-answers",  "exists": true, "docCount": 8110 },
  { "index": "irc-research", "exists": true, "docCount": 950 },
  { "index": "irc-users",    "exists": true, "docCount": 18234 },
  { "index": "irc-channels", "exists": true, "docCount": 212 },
  { "index": "irc-sounds",   "exists": true, "docCount": 340 },
  { "index": "irc-chat-messages", "exists": false }
]
```

**Errors**: none (per-index failures land inline as `error`).

### GET /api/v1/admin/search/health
ES reachability + per-index doc counts with drift against the canonical store where one has a
cheap full count (Postgres-canonical: `irc-users`, `irc-research` [PUBLISHED only], `irc-qna`,
`irc-channels` [CHANNEL conversations]). Cassandra-canonical indices (posts, answers, sounds,
chat-messages) report a `driftNote` instead — no pretend numbers.

**Access**: `ADMIN` or `ANALYST`.

**Request body**: None.

**Response** — `200`. A missing index counts as `docCount: 0`; any ES exception flips
`reachable` to `false` and puts `error` on that row. (`canonicalCount` is intended as
explicit-null for Cassandra-canonical rows but is dropped from the wire by the global
non-null serialization — clients must treat its absence as "unknown".)

```json
{
  "reachable": true,
  "indices": [
    { "index": "irc-posts", "docCount": 15230, "driftNote": "canonical store is Cassandra — no cheap full count" },
    { "index": "irc-qna", "docCount": 4620, "canonicalCount": 4620, "drift": 0 },
    { "index": "irc-answers", "docCount": 8110, "driftNote": "canonical store is Cassandra — no cheap full count" },
    { "index": "irc-research", "docCount": 948, "canonicalCount": 950, "drift": -2 },
    { "index": "irc-users", "docCount": 18240, "canonicalCount": 18234, "drift": 6 },
    { "index": "irc-channels", "docCount": 212, "canonicalCount": 212, "drift": 0 },
    { "index": "irc-sounds", "docCount": 340, "driftNote": "canonical store is Cassandra — no cheap full count" },
    { "index": "irc-chat-messages", "docCount": 0, "driftNote": "canonical store is Cassandra — no cheap full count" }
  ],
  "driftMeaning": "drift = esDocs - canonicalRows. Positive: stale docs (deleted rows still indexed). Negative: missing docs (index behind). Small transient drift is normal; persistent drift → run the reindex hook."
}
```

**Errors**: none.

---

## Query analytics

Anonymous search-query collector: Redis `irc:search:top:*` / `irc:search:zero:*` ZSETs,
per-scope per-day keys, 8-day retention, no user ids ever recorded.

### GET /api/v1/admin/search/analytics/top-queries
Most-run normalized queries over the trailing days, merged across day keys.

**Access**: `ADMIN` or `ANALYST`.

**Params**

| Param | Type | Default | Constraints |
|---|---|---|---|
| `scope` | string | `ALL` | `ALL`, one of `POST` `REEL` `QUESTION` `RESEARCH` `ANSWER` `USER` `CHANNEL` `SOUND`, or `MULTI` (multi-type searches); unknown → `ALL` |
| `days` | int | `7` | clamped 1–7 (echoed clamped in the body) |
| `limit` | int | `50` | capped at 200 |

**Request body**: None.

**Response** — `200`:

```json
{
  "scope": "ALL",
  "days": 7,
  "queries": [
    { "query": "crispr", "count": 142 },
    { "query": "machine learning", "count": 118 }
  ],
  "source": "Redis irc:search:top:* — anonymous counts, 8-day retention; no user ids are ever recorded (§12)."
}
```

**Errors**: none.

### GET /api/v1/admin/search/analytics/zero-results
Queries that returned zero hits — the content-gap / taxonomy signal. Same params as
top-queries.

**Access**: `ADMIN` or `ANALYST`.

**Request body**: None.

**Response** — `200`:

```json
{
  "scope": "ALL",
  "days": 7,
  "queries": [
    { "query": "quantum biology dept", "count": 9 }
  ],
  "note": "Degraded searches (ES down) are excluded — a zero here means ES answered and genuinely had nothing."
}
```

**Errors**: none.

---

## Reindex hooks & reindex-all

### The 7 synchronous per-index hooks (`SearchAdminController`)

One POST per rebuildable index. All share the same contract:

| Endpoint | Index | Source scanned |
|---|---|---|
| `POST /api/v1/admin/search/research/reindex` | `irc-research` | every PUBLISHED research row (Postgres) |
| `POST /api/v1/admin/search/posts/reindex` | `irc-posts` | every post (Cassandra) — also the mapping-repair path for legacy dynamic mappings |
| `POST /api/v1/admin/search/questions/reindex` | `irc-qna` | every live question |
| `POST /api/v1/admin/search/users/reindex` | `irc-users` | every active account |
| `POST /api/v1/admin/search/channels/reindex` | `irc-channels` | every live public channel |
| `POST /api/v1/admin/search/answers/reindex` | `irc-answers` | every live answer (reanswers included) |
| `POST /api/v1/admin/search/sounds/reindex` | `irc-sounds` | the whole sound library |

`irc-chat-messages` deliberately has **no** hook: its canonical store has no efficient full
scan, and the index self-heals by re-writing on every mutation.

**Access**: `ADMIN` only (method-level; no class-level guard, no `ANALYST` read, no step-up).

**Params**

| Param | Type | Default | Meaning |
|---|---|---|---|
| `drop` | boolean | `true` | delete + recreate the index first so the current entity mapping lands (picks up new `@Field`s, fixes broken dynamic mappings). `drop=false` refreshes documents/score counters without touching the mapping. |

**Request body**: None.

**Response** — `200`, synchronous (returns when the run is done). Research returns
`ResearchSearchService.ReindexResult`; the others return `ReindexSummary` — identical shape:

```json
{
  "indexDropped": true,
  "documentsIndexed": 950,
  "pages": 10,
  "durationMs": 4180,
  "note": "Reindexed 950 published research record(s) across 10 page(s) (index was dropped first)."
}
```

Partial-failure detail lands in `note` (the run does not 500 for per-page indexing errors).

**Errors**: none beyond 401/403 (ES connectivity problems surface as a 5xx envelope).

### POST /api/v1/admin/search/reindex-all
Sequential reindex of all 7 hook-equipped indices, asynchronous. Order: research → posts →
questions → answers → users → channels → sounds, each with `drop=true`. Per-step failures are
logged and counted; the job ledger row finishes `SUCCESS` (0 failed) or `PARTIAL`.

**Access**: `ADMIN` only + `@RequiresStepUp` (class-level `ADMIN`). Audit:
`ADMIN_SEARCH_REINDEX_ALL`.

**Request body**: None.

**Response** — `202 Accepted` with the job-ledger id; poll the ops job runs endpoint for
completion:

```json
{
  "jobId": "e7a1c9d3-5b2f-4e8a-9c0d-1f2a3b4c5d6e",
  "jobName": "search-reindex-all",
  "note": "sequential; poll GET /api/v1/admin/ops/jobs/search-reindex-all/runs"
}
```

The polled run row carries `outcome` (`RUNNING` → `SUCCESS`/`PARTIAL`), `itemsProcessed`
(indices ok) and `itemsFailed`.

**Errors**
- `403 STEP_UP_REQUIRED` — step-up marker missing/expired.

# Platform — search, observability, operations

The cross-cutting machinery: how content is found, how the system is observed,
and how it is run.

| Doc | What it answers |
|---|---|
| [search-feed-trending.md](search-feed-trending.md) | Elasticsearch index health and the 7 reindexes, trending controls and overrides, feed-ranking observability + runtime tuning, the suggestions engine |
| [logs-audit.md](logs-audit.md) | **The complete log catalog** — every log store with schema, writers and retention — plus the unified Log Explorer, alert rules and GDPR handling |
| [analytics-kpis.md](analytics-kpis.md) | The KPI tree, per-module metrics with honest EXISTS/PLANNED sourcing, the event-collection pipeline, the overview-page layout |
| [operations.md](operations.md) | Dependency health, the scheduled-jobs inventory, queue/DLQ ops, SSE and Redis ops, the env-var registry, backup/DR, runbooks |

## Jobs added by the moderation subsystem

Two entries in the pausable-jobs registry are newer than most of
[operations.md](operations.md)'s inventory:

| Job key | Cadence | What pausing it does |
|---|---|---|
| `moderation-sla-sweep` | every 5 s | Stops held content being force-resolved. Useful while rolling the inference container — **but nothing leaves `PENDING` until you resume it.** |
| `moderation-training-poll` | every 15 s while a job runs | Stops chasing in-flight fine-tunes. Harmless; the completion webhook still lands. |

Both pause/resume through the standard
`POST /api/v1/admin/ops/jobs/{jobKey}/pause|resume`. Full runbook:
[`../../moderation/operations.md`](../../moderation/operations.md).

API reference: [`../api/analytics-feed-search.md`](../api/analytics-feed-search.md) ·
[`../api/notifications-logs.md`](../api/notifications-logs.md) ·
[`../api/ops-activity-discovery.md`](../api/ops-activity-discovery.md).

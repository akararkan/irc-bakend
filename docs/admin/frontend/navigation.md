# Route & navigation tree

Part of the [admin dashboard frontend guide](README.md).
Legend: **SU** = step-up required (§[auth-and-roles.md](auth-and-roles.md)) ·
roles in the *Who* column are the `hasRole`/`hasAnyRole` grants as coded ·
list endpoints paginate per [conventions.md](conventions.md).
Wire-level request/response JSON: [../api/](../api/README.md).

---

## 7. Suggested route / nav tree

Role annotations = who sees the nav entry (from §1.2; per-endpoint grants in
§4 still apply within a page). `[A]`=ADMIN `[M]`=MODERATOR `[S]`=SUPPORT
`[AN]`=ANALYST.

```
/admin                                  shell: health chips [A] + audit ticker [A M S AN]
├── /users                              directory + detail tabs        [A M S]
│   ├── /users/:id                      profile · sessions · login-events · settings-audit
│   │                                   · moderation · data-lifecycle · (PII reveal, SU) [A]
│   ├── /users/new, /users/invites      create / bulk / invite         [A]
│   └── /users/analytics                growth                          [A M S AN]
├── /moderation                         unified queue + bulk            [A M]
│   ├── /moderation/content             posts / comments / stories      [A M]
│   ├── /moderation/blocklist           platform keywords               [A M]
│   └── /moderation/sounds              approval queue + catalog        [A M]
├── /safety                             reports triage                  [A M] (reads: +S)
│   ├── /safety/reports/:id             detail + evidence + action
│   ├── /safety/strikes                 ledger
│   └── /safety/analytics               volumes / SLAs / blocks
├── /research                           browse · flags · retract/delete [A M]
├── /qna                                questions · close/archive/delete[A M]
├── /tags                               hide/merge · trending overrides · backfill [A]
├── /chat                               conversations meta · calls · msg-requests [A M]
│   ├── /chat/channels(:id)             browse · verify · takedown/freeze · invite links [A M]
│   ├── /chat/streams(:id)              live board · force-stop · recordings (SU)
│   │                                   · rotate-key [A only]           [A M]
│   └── /chat/legal-holds               dual-control content release    [A]
├── /media                              assets · status board · reconcile · quotas [A]
│   └── /media/storage                  usage top-N                     [A]
├── /notifications                      stats + type registry           [A S AN]
│   ├── /notifications/announcements    composer (dry-run flow)         [A]
│   └── /notifications/email            deliverability + test send      [A]
├── /search                             index health · reindexes · query analytics [A] (health/indices: +AN)
├── /feed                               weights · config (SU) · preview · explain [A] (reads: +AN)
│   └── /feed/suggestions               PYMK knobs + explain            [A AN]
├── /logs                               explorer · saved views · alerts · retention [A M S AN]
│   └── /logs/audit                     per-user/per-resource browser + live stream [A M S AN]
├── /analytics                          overview · series · funnel · retention · export [A AN]
├── /ops                                health · jobs · queues/DLQ · SSE · Redis
│                                       · config (SU) · media-plane · sweeps [A]
├── /activity                           break-glass cases + gated reads [A]
├── /knowledge                          topics & madhhabs curation      [A]
└── /discovery                          contact-sync stats/compliance · per-user flags [A]
```

Hide, don't disable, sections the role can't see; inside a visible section,
render mutation buttons disabled-with-tooltip when the role lacks the grant
(e.g. MODERATOR on the users directory). Impersonation lives as an action on
the user detail page, not a nav entry — its banner (§3) is global.

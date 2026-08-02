# Admin Documentation — Task Order

What to document **first**, sorted as a simple task list. Everything here is docs
only (no code). Check items off as you go. Full map: [README.md](README.md).

Legend: ✅ done · ⬜ to do · 🔁 needs a quick verify-against-code pass.

---

## Do first — the foundation (read/build in this order)

1. ✅ **Architecture & access** — [architecture.md](architecture.md) — the access
   model + inventory of what already exists. *Everything else assumes this.*
2. ✅ **API blueprint** — [admin-api-blueprint.md](admin-api-blueprint.md) — every
   endpoint in one table, danger levels, phased build order.
3. ✅ **API controllers** — [api-controllers.md](api-controllers.md) — the real
   `@RestController`s + the build map (which controller each route belongs on).
4. ✅ **Logs & audit** — [logs-audit.md](logs-audit.md) — the complete log catalog
   (the flagship "what gets recorded" doc).

## Then — the core admin sections

5. ✅ **Users & roles** — [users-roles.md](users-roles.md) — directory + inspection.
6. ✅ **User administration (add & full control)** — [user-administration.md](user-administration.md)
   — **the priority action surface**: adding users + full lifecycle control.
7. ✅ **Content moderation** — [content-moderation.md](content-moderation.md).
8. ✅ **Safety & reports** — [safety-reports.md](safety-reports.md).

## Next — the remaining sections

9.  ✅ **Research & Q&A** — [research-qna.md](research-qna.md)
10. ✅ **Chat, channels & live** — [chat-channels-live.md](chat-channels-live.md)
11. ✅ **Sound library** — [sound-library.md](sound-library.md)
12. ✅ **Media & storage** — [media-storage.md](media-storage.md)
13. ✅ **Notifications & email** — [notifications-email.md](notifications-email.md)
14. ✅ **Search, feed & trending** — [search-feed-trending.md](search-feed-trending.md)
15. ✅ **Discovery, PYMK & privacy** — [discovery-pymk-privacy.md](discovery-pymk-privacy.md)
16. ✅ **Knowledge vocabulary** — [knowledge-vocabulary.md](knowledge-vocabulary.md)
17. ✅ **Activity & engagement** — [activity-engagement.md](activity-engagement.md)

## Last — the cross-cutting sections

18. ✅ **Analytics & KPIs** — [analytics-kpis.md](analytics-kpis.md)
19. ✅ **Operations** — [operations.md](operations.md)

---

## Open follow-ups (do these next)

Small, real tasks left in the docs — tackle top-down:

- 🔁 **Verify the GDPR purge cascades.** Confirm the account-purge job drops
  `activity_by_user*` + `reel_views_by_user` ([activity-engagement.md](activity-engagement.md) §5)
  and `UserContactHash` + `FriendSuggestionEntity`
  ([discovery-pymk-privacy.md](discovery-pymk-privacy.md) §8). Both are flagged
  `[PLANNED verification]` — if not wired, it's a privacy gap.
- ⬜ **Confirm the `role = SCHOLAR` default is intended** (registration hardcodes it,
  not `USER`) and decide what the admin-create form defaults to
  ([user-administration.md](user-administration.md) §2).
- ⬜ **Consolidate the recon flags** into one "Known issues" list (dead email-verify
  scaffolding, dead lock columns, `StrikeService` zero callers, QR-resolve seam,
  rate-limiter fail-open, the 2 stray endpoints, phantom roles).
- ⬜ **Anti-abuse / rate-limiting** — a small consolidation doc if wanted (today it's
  split across [safety-reports.md](safety-reports.md), [operations.md](operations.md) §6,
  and [discovery-pymk-privacy.md](discovery-pymk-privacy.md) §6).

> Every doc uses **[EXISTS] / [PARTIAL] / [PLANNED]** tags — keep that discipline
> when editing so nothing planned reads as if it's already built.

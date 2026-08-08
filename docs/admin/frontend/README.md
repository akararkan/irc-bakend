# Admin Dashboard — Frontend Build Guide

The build guide for **frontend developers** implementing the admin dashboard UI
(React + Vite, the ika app, route tree `/admin/**`) against the
**fully-implemented** Spring Boot admin backend (`:8080`).

Everything here is real: every path was verified against the controllers under
`app/admin/**`, plus `AdminUserController`, `AuditLogController`,
`SearchAdminController` and `TagAdminController`. **You should not need to read
the backend to build the UI** — but when you want the deep story of a section,
each page table links its section doc.

---

## The guide

| Doc | Read it when |
|---|---|
| [auth-and-roles.md](auth-and-roles.md) | **Start here.** Tokens, the double gate, the four staff roles → what nav to render, the two kinds of 403, the step-up modal flow, impersonation |
| [conventions.md](conventions.md) | Building any list, filter, export or async action — pagination (two shapes), time & filters, 202 + jobId jobs, CSV exports, the audit SSE stream, honest `note`/`warning` fields |
| [danger-zone.md](danger-zone.md) | Building anything destructive — the confirm/step-up/reason rules |
| [navigation.md](navigation.md) | Laying out the route tree and sidebar |

### Page maps — one table per dashboard page

| Doc | Pages | Section docs |
|---|---|---|
| [pages-users.md](pages-users.md) | Shell · Users & roles · Activity & break-glass · Discovery & PYMK | [../users/](../users/README.md) |
| [pages-trust-safety.md](pages-trust-safety.md) | Reports/keyword inbox · **Automated moderation** · Content takedown · Safety & reports | [../trust-safety/](../trust-safety/README.md) |
| [pages-content.md](pages-content.md) | Research · Q&A · Tags & trending · Sounds · Media & storage · Knowledge vocabulary | [../content/](../content/README.md) |
| [pages-communication.md](pages-communication.md) | Chat, channels & live · Notifications & announcements | [../communication/](../communication/README.md) |
| [pages-platform.md](pages-platform.md) | Search ops · Feed tuning & suggestions · Logs & audit · Analytics · Operations | [../platform/](../platform/README.md) |

---

## Companions

| Doc | For |
|---|---|
| [../api/](../api/README.md) | **Request/response JSON for every endpoint** — the per-domain wire reference. Use it alongside the page maps |
| [../foundation/architecture.md](../foundation/architecture.md) | The access model behind the roles |
| [../foundation/api-blueprint.md](../foundation/api-blueprint.md) | Endpoint catalog with danger levels |
| [../foundation/api-controllers.md](../foundation/api-controllers.md) | Controller-level reference |
| [../../errors/error-handling.md](../../errors/error-handling.md) | The error envelope |
| [../../errors/frontend-error-handling.md](../../errors/frontend-error-handling.md) | Client-side error handling — codes, retries, step-up, SSE |
| [../../errors/user-facing-messages.md](../../errors/user-facing-messages.md) | Every message string the backend can return |
| [../known-issues.md](../known-issues.md) | Freshness overlay — what's stale or knowingly open |

---

## Legend used throughout

- **SU** = step-up required (`@RequiresStepUp`) — see
  [auth-and-roles.md](auth-and-roles.md).
- Roles in a *Who* column are the `hasRole`/`hasAnyRole` grants **as coded**,
  not as aspired to.
- All list endpoints paginate per [conventions.md](conventions.md); page sizes
  clamp server-side to 1–100.

## Two things that will bite you if you skip them

1. **There are two moderation queues and they are not the same thing.** The
   reports inbox judges content that is **already live**; the automated-moderation
   review queue releases or buries content **nobody but its author has seen**. A
   mis-click has opposite consequences. Do not merge them in the UI —
   [pages-trust-safety.md](pages-trust-safety.md) explains the split.
2. **Wire the threshold dry-run into the editor before the save button.**
   `POST /moderation/settings/dry-run` replays proposed thresholds against real
   stored scores and returns exactly which past decisions would flip. A
   threshold slider without it ships a guess straight to production.

# Foundation — how the admin surface works

Read this directory before building anything against `/api/v1/admin/**`. It
covers the rules that apply to *every* admin endpoint, not any one feature.

| Doc | What it answers |
|---|---|
| [architecture.md](architecture.md) | Who can call what, how the double gate works, what step-up is and when it fires, what already existed before the admin build, how RBAC evolved, impersonation policy |
| [api-blueprint.md](api-blueprint.md) | The whole endpoint surface in one table with danger levels, plus the phased build order the backend actually followed |
| [api-controllers.md](api-controllers.md) | Controller-by-controller reference — exact mappings, DTO records, per-controller `@PreAuthorize`, and the strays that live outside the prefix |

## The three rules everything else assumes

1. **Double gate.** `SecurityConfig` admits `ADMIN`/`MODERATOR`/`SUPPORT`/`ANALYST`
   to `/api/v1/admin/**` at the filter chain; each controller then narrows with
   its own `@PreAuthorize`. A controller with no annotation is reachable by all
   four staff tiers — deny-by-default is a *convention*, not a mechanism, so
   always declare it.
2. **Every mutation is audited.** `AdminAuditor.record(...)` writes the named
   `ADMIN_{DOMAIN}_{VERB}` business event; the HTTP interceptor writes the
   request row for free. There are no anonymous admin actions.
3. **Danger needs step-up.** `@RequiresStepUp` demands a fresh re-auth marker
   (Redis, ~300 s). Reads never require it; irreversible or wide-blast-radius
   mutations always do.

## Where the endpoint reference lives

This directory describes the *shape* of the surface. For request/response JSON
per endpoint, go to [`../api/`](../api/README.md).

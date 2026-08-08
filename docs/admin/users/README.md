# Users — people and their data

Everything the admin surface can see and do about a person: who they are, what
they can do, what they have done, and how they are discovered by others.

| Doc | What it answers |
|---|---|
| [directory-and-roles.md](directory-and-roles.md) | The user directory and inspection views, the role/badge model, account state, sessions & 2FA, the deletion pipeline, growth analytics |
| [administration.md](administration.md) | The *action* surface — add users (single/bulk/invite/pre-verified), edit identity, reset password/2FA, disable/lock/ban, kill sessions, delete/restore, bulk ops, **impersonation** |
| [discovery-privacy.md](discovery-privacy.md) | How users find each other and the privacy machinery around it — PYMK sources and weights, contact-sync hashing and consent, discoverability flags, QR tokens, enumeration abuse |
| [activity-engagement.md](activity-engagement.md) | The per-user activity ledger (30 event types), reel-view analytics, GDPR erasure of activity, and why the ledger is the cheapest engagement telemetry the platform has |

## The split worth understanding

[`directory-and-roles.md`](directory-and-roles.md) is what you **see**;
[`administration.md`](administration.md) is what you **do**. They cover the same
user object from two sides deliberately — the read surface is broad and
low-risk, the write surface is narrow and mostly step-up gated.

## Privacy notes that bite here

- **PII reveal is a step-up-gated, separately-audited action** (`ADMIN_PII_REVEAL`).
  Directory rows are redacted by default.
- Break-glass access to a user's activity is a two-person flow with an
  approval case, not a single click — see
  [`activity-engagement.md`](activity-engagement.md).
- Contact-sync hashes are peppered server-side and never reversible from the
  admin surface; the compliance view reports counts, not contacts.

API reference: [`../api/users.md`](../api/users.md) ·
[`../api/ops-activity-discovery.md`](../api/ops-activity-discovery.md).

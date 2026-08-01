# Settings Module

Backend implementation of the IRC Settings module (per `IRC_Settings_Module_v2.md`).
This directory documents every section: what is enforced server-side, what is
client-owned, and exactly how each part is realised on the IRC backend.

> **Governing rule:** *any setting that protects a user from another user is
> backend-enforced* — in the query layer and the serialization layer, never by
> filtering in the UI. Cosmetic settings (theme, font, wallpaper) are stored as
> JSON and never interpreted by the backend.

## Platform adaptations (how this differs from the spec document)

The spec was written against a generic stack; these are the concrete choices for
the IRC codebase (base package `ak.dev.irc.app`):

| Spec assumption | IRC reality |
|-----------------|-------------|
| Snowflake `BIGINT` user ids | **`java.util.UUID`** everywhere (only chat message ids are Snowflake `long`) |
| Flyway migrations | Hibernate `ddl-auto=update` — entities create their own tables. New `NOT NULL` columns carry a `columnDefinition` DEFAULT; new enum values register with `EnumCheckConstraintReconciler` |
| Argon2id password hashing | Existing `BCryptPasswordEncoder(12)` retained (swapping would break every stored hash on an offline build); recovery codes are BCrypt-hashed. Documented divergence. |
| libphonenumber / TOTP lib / libvips / Bouncy Castle | The build runs **offline** (`mvn -o`), so no new Maven dependencies are added. Everything is **pure JDK**: a self-contained E.164 normalizer, RFC-6238 TOTP via `javax.crypto`, AES-GCM via JCE, image transcode via `ImageIO`, video transcode via the ffmpeg binary already configured for live streaming. |
| SMS gateway / FCM / real transcode | Behind interfaces (`SmsSender`, `PushSender`, `MediaProcessor`) with safe default impls (log/no-op/passthrough); a real provider drops in later. |

## Ownership tags

- **[B]** Backend-owned — enforced server-side, the client cannot bypass it.
- **[C]** Client-owned — stored for cross-device sync, never acted on by the backend.
- **[B+C]** Shared — client applies for responsiveness, backend enforces for correctness.

## Section index

| § | Topic | Owner | Doc |
|---|-------|-------|-----|
| 2 | Registration, phone + OTP, sessions | [B] | [auth-sessions.md](auth-sessions.md) |
| 3 | Contact synchronization | [B] | [discovery-contacts.md](discovery-contacts.md) |
| 4 | Account settings, sensitive changes | [B] | [auth-sessions.md](auth-sessions.md) |
| 5 | Privacy — Visibility Resolver | [B] | [privacy.md](privacy.md) |
| 6 | Search & discovery, QR | [B] | [discovery-contacts.md](discovery-contacts.md) |
| 7 | Online presence | [B] | [presence.md](presence.md) |
| 8 | Notifications matrix + DND | [B] | [notifications.md](notifications.md) |
| 9 | Messaging settings | [B+C] | [messaging-media.md](messaging-media.md) |
| 10 | Appearance | [C] | [core-settings.md](core-settings.md) |
| 11 | Accessibility | [C] | [core-settings.md](core-settings.md) |
| 12 | Security — 2FA, recovery, sessions, login history | [B] | [auth-sessions.md](auth-sessions.md) |
| 13 | Blocks, mute, restrict, hidden keywords | [B] | [privacy.md](privacy.md) |
| 14 | Device permissions / consent evidence | [C]/[B] | [discovery-contacts.md](discovery-contacts.md) |
| 15 | Storage management | [B+C] | [messaging-media.md](messaging-media.md) |
| 16 | Data export & account deletion | [B] | [data-export-deletion.md](data-export-deletion.md) |
| 17 | Community settings | [B] | [community.md](community.md) |
| 18 | Safety Center | [B] | [safety-center.md](safety-center.md) |
| 19 | About, app-config, policy acceptance | [C]/[B] | [about-policy.md](about-policy.md) |
| 20 | Media upload / quality / compression | [B+C] | [messaging-media.md](messaging-media.md) |
| 22 | Settings storage model & API surface | — | [core-settings.md](core-settings.md), [api-reference.md](api-reference.md) |

See also:
- [architecture.md](architecture.md) — storage tiers, the two enforcement layers, caching, audit, step-up, conventions.
- [api-reference.md](api-reference.md) — every endpoint, grouped by controller.
- [data-model.md](data-model.md) — every new table + the additive columns on existing tables.
- [config.md](config.md) — every new `application.yaml` key + env var (and the secrets to set in prod).
- [implementation-notes.md](implementation-notes.md) — **what's real vs a stand-in, deliberate divergences, and the follow-up seams.**

## Package map

```
ak.dev.irc.app
├── settings
│   ├── core          §10/11/9/20.3 cosmetic JSONB blocks + SettingsService + cache
│   ├── audit         §22.3 settings_audit trail
│   ├── privacy       §5/13 VisibilityResolver, lists, mute, hidden keywords
│   ├── presence      §7 three-way presence policy
│   ├── notification  §8 preference matrix, DND, push tokens, channels
│   ├── discovery     §6 discoverability flags (+ discovery.qr QR tokens)
│   ├── consent       §14 consent_events
│   ├── safety        §18 reports, strikes, security score
│   ├── data          §16 export jobs + account-deletion state machine
│   ├── storage       §15 server-side usage report
│   └── policy        §19 app-config + policy acceptance
├── security          §2/12 otp, twofa, crypto, phone, login events, sessions, step-up
└── media             §20 media_assets/renditions pipeline (upload-intent → process → serve)
```

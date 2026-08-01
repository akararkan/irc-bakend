# Core Settings — Appearance, Accessibility & Storage Model (§10, §11, §22)

## Appearance (§10) & Accessibility (§11) — **[C] client-owned**

Stored verbatim as JSONB blocks on `user_settings` and synced for cross-device
continuity; the backend **never interprets** them (adding a theme needs no
backend release). Unknown keys are ignored on read (`@JsonIgnoreProperties`), so
a newer client can add fields without a schema change.

- `appearance` — theme, fontSize, density, interfaceScale, reducedMotion, accentColor.
- `accessibility` — largeText, highContrast, screenReader, closedCaptions,
  reducedMotion, voiceNavigation, hapticFeedback.
- `messages` — wallpaper, chatTheme, fontSize, enterToSend.
- `media` — uploadQuality (`MediaTier`), uploadOverCellular, autoDownloadPhotos/Videos, playbackQuality.

> One accessibility item is **[B]**: closed captions require the server to store
> and serve WebVTT tracks (a `media_renditions` label `captions`, §20.4). The
> toggle itself stays cosmetic.

## Storage model (§22.1) — three tiers

| Tier | Storage | Examples |
|------|---------|----------|
| **Enforced** | dedicated columns / tables | privacy policy, discoverability, presence policy, blocks, 2FA, sessions, notification matrix, DND, media |
| **Structured** | preference tables | notification prefs, media prefs |
| **Cosmetic** | `user_settings.*` JSONB | theme, font, wallpaper, accessibility |

Rule of thumb: **if the backend ever needs it in a `WHERE` clause it is a column;
otherwise it is JSONB.**

## Service, cache, audit (§22.2, §22.3)

- `SettingsService` — `PATCH` performs a **JSON Merge Patch** on a block; `PUT`
  fully replaces it; both bump `settings_version`, invalidate the cache, and
  write an audit row.
- `SettingsCache` — the resolved cosmetic object cached in Redis as
  `settings:{userId}` (10-min TTL, explicit invalidation on write, fail-open on a
  Redis outage — the `CounterCache`/`RateLimiter` convention).
- `SettingsAuditService` — every privacy/security-relevant mutation writes a
  `settings_audit` row (`user, key, old, new, ip, ts`); best-effort, never fails
  the user's write. This is the only way to answer "my account was public and I
  never changed it".

## API (§22.3)

```
GET   /api/v1/settings                     full resolved cosmetic object
GET   /api/v1/settings/{section}           appearance|accessibility|messages|media
PATCH /api/v1/settings/{section}           JSON Merge Patch
PUT   /api/v1/settings/{appearance|accessibility|messages|media}
```

Enforced sections (privacy, security, notifications, presence, discovery, safety)
have their own controllers — see [api-reference.md](api-reference.md). Sensitive
security actions additionally require [step-up](auth-sessions.md#sessions-login-history-step-up-12).

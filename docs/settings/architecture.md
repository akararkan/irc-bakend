# Settings — Architecture

## Storage strategy (spec §22.1)

Not every setting deserves a column. Three tiers:

| Tier | Storage | Contents | Why |
|------|---------|----------|-----|
| **Enforced** | Dedicated Postgres columns / tables | privacy policies, discoverability, presence policy, blocks, 2FA, sessions, auto-delete TTL, notification matrix, DND | queried, indexed, joined on the hot path — must be filterable in SQL |
| **Structured** | JSONB blocks + preference tables | notification matrix override, media preferences | evolving schema, read as a whole, rarely filtered |
| **Cosmetic** | `user_settings.*` JSONB | theme, font size, wallpaper, accessibility toggles | backend never interprets them |

**Rule of thumb:** if the backend ever needs it in a `WHERE` clause, it is a column; otherwise it is JSONB.

## The two enforcement layers (spec §5)

Any setting that protects a user from another user is enforced twice, never in the UI:

1. **Query layer** — feed / profile / search queries carry the block + visibility
   predicate *into the SQL/CQL*, so hidden rows are never fetched. Filtering
   after fetch is how leaks happen (pagination counts, `totalElements`, cache
   side-channels).
2. **Serialization layer** — the `VisibilityResolver` decides field-by-field
   what a viewer may see, so a profile DTO is redacted regardless of which
   endpoint produced it.

The single funnel is `settings.privacy.service.VisibilityResolver`, a pure
ordered function over `(relationship, policy)` that fails closed and checks
blocks first — mirroring the existing chat `ChatPermissionEngine`.

## Caching (spec §22.2)

- The resolved cosmetic settings object is cached in Redis as `settings:{userId}`
  (`SettingsCache`, 10-min TTL, explicit invalidation on write).
- Relationship edges (follow/block) are already cached by `SocialGuard`
  (`user-blocked-ids`, 1 min) and Spring cache; block events invalidate
  immediately.
- Server-side storage usage is cached 1 h; notification prefs are cached per user.

Everything fails **open** on a Redis outage (degrade to a DB read), matching the
existing `CounterCache` / `RateLimiter` convention.

## Audit (spec §22.3)

Every privacy- or security-relevant mutation writes a `settings_audit` row
(`user, key, oldValue, newValue, ip, timestamp`) via `SettingsAuditService`.
This is the only way to answer "my account was public and I never changed it".
Audit writes are best-effort and never fail the user's actual write.

## Step-up authentication (spec §22.3)

Disabling 2FA, changing the recovery channel, and requesting account deletion
require a fresh password or OTP even inside a valid session
(`security.stepup.StepUpService`, a short-TTL Redis marker keyed by session id).
A stolen unlocked device must not be able to dismantle the account's security.

## How new code matches the existing codebase

| Concern | Convention |
|---------|-----------|
| Identifiers | `java.util.UUID` |
| Entities | extend `common.BaseAuditEntity`; Lombok `@Getter/@Setter/@Builder`; `@GeneratedValue(UUID)` |
| JSONB | `@JdbcTypeCode(SqlTypes.JSON)` + `columnDefinition="jsonb"` |
| Enum columns | `@Enumerated(STRING)`; new value on an existing table ⇒ `EnumCheckConstraintReconciler` entry |
| New NOT NULL column on existing table | `columnDefinition` carries a DEFAULT (auto-DDL ADD COLUMN) |
| Controllers | `@RestController` + `@RequiredArgsConstructor` + class/method `@PreAuthorize`; return `ResponseEntity<Dto>` (no envelope) |
| Current user | `SecurityUtils.requireCurrentUserId()` |
| Errors | throw `AppException` / `BadRequestException` / `ForbiddenException` / `ResourceNotFoundException` |
| Rate limits | `RateLimiter.check(action, actorId, burst, Duration)` |
| Async events | RabbitMQ topic exchange `irc.topic.exchange`; constants in `RabbitMQConstants` |
| Crypto | pure JDK (`javax.crypto`) — offline build forbids new Maven deps |

## Pragmatic stand-ins (spec Part 7)

External-infra features are wired end-to-end behind an interface with a safe
default, so the module runs and a real provider drops in later:

| Capability | Interface | Default impl |
|-----------|-----------|--------------|
| SMS OTP delivery | `security.otp.sms.SmsSender` | `LoggingSmsSender` (logs the code in dev) |
| Push notifications | `settings.notification.push.PushSender` | `NoOpPushSender` |
| Video transcode | `media.service.VideoProcessor` | passthrough (stores validated original) when `media.processing.enabled=false`; ffmpeg-binary run when enabled |
| Image re-encode | `media.service.ImageProcessor` (JDK `ImageIO`) | real downscale + metadata strip (works today) |
| Malware/NSFW scan | `media.service.MediaScanner` | allow-all default |

The worker itself is `media.service.MediaProcessingService` — a small **bounded
thread pool** (not the API threads), so one malicious 8K file can't degrade API
latency (§20.7). Orchestration (upload-intent → complete → status → delete) is
`media.service.MediaAssetService`.

Only three behaviours degrade to no-op (SMS send, push send, real video
transcode) — each isolated behind one interface. Every enforcement path
(privacy, blocks, presence, OTP verify, sessions, 2FA, notifications matrix,
DND, media status machine, dedup, usage, export, deletion, safety) runs for real.

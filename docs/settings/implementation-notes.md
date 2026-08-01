# Settings — Implementation Notes, Divergences & Seams

An honest account of what runs for real, what is a wired stand-in, where this
build deliberately diverges from `IRC_Settings_Module_v2.md`, and the seams left
for follow-up. Read this before assuming a behaviour is production-complete.

## The offline / pure-JDK constraint

This project builds **offline** (`mvn -o -Dmaven.test.skip=true …`). An offline
build cannot fetch a Maven dependency that is not already cached, so **no new
dependency was added**. Everything is pure JDK:

| Spec suggestion | What was built instead |
|-----------------|------------------------|
| libphonenumber | `security.phone.PhoneNormalizer` — conservative pure-JDK E.164 normaliser (handles `+`, `00`, trunk-`0`, default calling code) |
| a TOTP library | `security.twofa.TotpProvider` — RFC 6238 via `javax.crypto` HmacSHA1, Base32, `otpauth://` URI |
| Bouncy Castle / Argon2id | `SecretCipher` uses JDK JCE AES-GCM; recovery codes + passwords use the existing `BCryptPasswordEncoder(12)` |
| libvips / AVIF-WebP encoders | `media.service.ImageProcessor` — `ImageIO` downscale + metadata strip (real reduction; JPEG output guaranteed) |
| ffmpeg Maven wrapper | shells out to the `ffmpeg` binary already configured for live streaming, gated by `media.processing.enabled` |

## What runs for real vs. stand-in

**Runs end-to-end for real:** the visibility resolver, blocks/mute/hidden-keyword
filtering, presence policy + reciprocity, OTP challenge (hashed code, Redis TTL,
attempts, rate-limit, enumeration-safe), TOTP 2FA with replay guard + AES-GCM
secret, recovery codes, active sessions + revoke, login events + new-device
alert, step-up auth, the notification preference matrix + DND evaluation,
image re-encode + downscale + EXIF strip, media dedup + status machine + storage
usage, data export (JSON→ZIP), the account-deletion state machine, the report /
strike / security-score engine, discoverability flags, QR tokens, consent log.

**Wired behind an interface, default is a stand-in** (real provider drops in
later without touching callers):

| Capability | Interface | Default | Real drop-in |
|-----------|-----------|---------|--------------|
| SMS OTP delivery | `security.otp.sms.SmsSender` | `LoggingSmsSender` (logs the code) | Twilio / local gateway |
| Push notifications | `settings.notification.push.PushSender` | `NoOpPushSender` | FCM / APNs sender |
| Video transcode | `media.service.VideoProcessor` | passthrough (stores validated original) | ffmpeg run (`media.processing.enabled=true`) |
| Malware / NSFW scan | `media.service.MediaScanner` | allow-all | scanning service |

## Deliberate divergences from the spec

1. **UUID, not Snowflake.** User ids are `java.util.UUID` (only chat message ids
   are Snowflake `long`). The spec's `BIGINT` examples were adapted.
2. **BCrypt, not Argon2id.** Swapping the password encoder on an offline build
   would break every stored hash. Recovery codes are BCrypt-hashed too.
3. **Sessions evolve `refresh_tokens` additively** rather than replacing the
   raw-token store, so the existing refresh flow keeps working. `sid` is added
   and revoke is real; the immediate `sid`-denylist check in
   `JwtAuthenticationFilter` is written (`SessionDenylist`) but left as a
   one-line wiring seam so existing tokens are unaffected.
4. **Contact sync keeps the existing client-SHA-256 email model** (`ContactMatchService`)
   and adds the `/api/v1/contacts/sync` alias + consent + rate-limit. The spec's
   server-HMAC-pepper phone model is now possible (`users.phone_hmac` exists) but
   phone-based matching lights up only once accounts are phone-verified.

## Seams left for follow-up (searchable as `// SEAM:` in code)

- **Phone-primary login → JWT.** OTP verify returns `{verified:true}`; minting a
  session for a phone-primary account is a small addition in `AuthServiceImpl`
  (phone columns already exist on `User`).
- **Notification delivery wiring.** `NotificationPrefResolver` / `DndEvaluator`
  are standalone; `CassandraNotificationService` should call them per channel
  before fan-out (replacing its hardcoded email switch), and delete push tokens
  on session revoke.
- **ES discoverability denormalisation.** `discover.*` + `indexable` should be
  written into `UserSearchDocument` (via `UserSearchService.buildDoc` + a
  `profile.updated` reindex) and filtered in the search query; today the flags
  live in `user_discoverability` and gate via `DiscoverabilityService`.
- **QR resolve gate.** `GET /discovery/qr/resolve/{opaque}` should additionally
  check the target's `byQr` flag.
- **Data export to R2.** The export ZIP is written to a temp file and streamed;
  production should stream to R2 and return a presigned 48h URL.
- **Media RabbitMQ worker.** Queues/bindings exist; processing currently runs on
  an in-process bounded pool. Move to the `media.process` consumer for isolation
  at scale, and emit the `media.ready` SSE event.
- **Message edit-window (§9).** Enforce `now - createdAt <= editWindow` in
  `MessageService.edit` → `409 EDIT_WINDOW_EXPIRED`.
- **Community role widening (§17).** Communities reuse the existing channel/group
  roles; adding `MODERATOR`/`RESTRICTED` needs an `EnumCheckConstraintReconciler`
  entry for `conversation_members_role_check` and was deferred to avoid touching
  the live table.

## Build & verification

- **Compiles** offline: `mvn -o -Dmaven.test.skip=true compile` → exit 0.
- Per the project convention, the app is **run and live-tested by the maintainer**;
  this work is implementation + compile-check only. New tables materialise on
  boot via `ddl-auto=update`.

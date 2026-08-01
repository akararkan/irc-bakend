# Settings — Configuration

New `application.yaml` keys and environment variables the module adds. Every
properties class carries in-code defaults, so the app boots without any of these
set — but the **secrets below must be set in production**.

## Must-set secrets (production)

| Env var | Binds | Why it matters |
|---------|-------|----------------|
| `TWOFA_AES_KEY` | `app.security.twofa.secret-key` | Encrypts the TOTP secret at rest (AES-GCM). If unset, a **process-ephemeral** key is used and enrolled 2FA secrets do **not** survive a restart (logged loudly). |
| `OTP_PEPPER` | `app.otp.pepper` | HMAC key for OTP codes — the plaintext code is never stored, only `HMAC(code, pepper)`. |
| `CONTACT_PEPPER` | `app.security.contact.pepper` | HMAC key for the phone number (contact-matching without storing the raw number). Falls back to `OTP_PEPPER` if unset. |

## Media policy — top-level `media:` (spec §20)

```yaml
media:
  image:  { max-long-edge: 1920, chat-max-long-edge: 1280, profile-edge: 512,
            avif-quality: 55, webp-quality: 80, jpeg-quality: 82 }
  video:  { max-short-edge: 1080, max-fps: 30, crf: 23, audio-bitrate: 128k }
  limits: { image-max-bytes: 26214400, video-max-bytes: 536870912, max-input-megapixels: 100 }
  processing:
    ffmpeg-bin:  ${MEDIA_FFMPEG_BIN:${app.streaming.ffmpeg-bin:ffmpeg}}
    ffprobe-bin: ${MEDIA_FFPROBE_BIN:ffprobe}
    enabled:     ${MEDIA_TRANSCODE_ENABLED:false}
    timeout-seconds: 600
```

- `video.max-short-edge: 1080` is the **hard cap** — do not raise it.
- `media.processing.enabled=false` (default) → the video worker stores the
  validated original (passthrough); set `true` **and** put `ffmpeg`/`ffprobe` on
  the host PATH to enable real transcoding. Image re-encode (downscale + EXIF
  strip) works today with pure-JDK `ImageIO` regardless of this flag.

## Security / OTP / phone / settings — under `app:`

```yaml
app:
  security:
    step-up: { ttl-seconds: ${STEP_UP_TTL_SECONDS:300} }
    twofa:   { issuer: ${TWOFA_ISSUER:IRC}, secret-key: ${TWOFA_AES_KEY:},
               recovery-code-count: 10, allowed-drift-steps: 1 }
    contact: { pepper: ${CONTACT_PEPPER:} }
  otp:
    code-length: 6
    ttl-seconds: ${OTP_TTL_SECONDS:300}
    max-attempts: 5
    pepper: ${OTP_PEPPER:}
    resend-per-number-per-hour: 3
    resend-per-ip-per-hour: 10
  phone:
    default-calling-code: ${PHONE_DEFAULT_CALLING_CODE:964}   # Iraq
  settings:
    cache-ttl-seconds: ${SETTINGS_CACHE_TTL_SECONDS:600}
  about:
    min-supported-version: ${APP_MIN_VERSION:1.0.0}
    force-update: ${APP_FORCE_UPDATE:false}
    latest-version: ${APP_LATEST_VERSION:1.0.0}
    privacy-policy-version: "2026-08-01"
    terms-version: "2026-08-01"
    guidelines-version: "2026-08-01"
```

## Reused existing config

- **Storage** — the media pipeline reuses the existing `app.storage.*` (R2)
  credentials and the `S3Client`/`S3Presigner` beans; presigned PUT and putBytes
  were added to `S3StorageService`.
- **RabbitMQ** — media queues (`irc.queue.media.process`, `irc.queue.media.delete`)
  were added to the existing `irc.topic.exchange` topology; the current build
  runs media processing on an in-process bounded pool and leaves the RabbitMQ
  worker as a wired seam.
- **Security** — `app.security.permit-all` and `app.jwt.*` are unchanged; the
  password encoder stays `BCryptPasswordEncoder(12)`.

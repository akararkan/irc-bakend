# Settings — API Reference

Every endpoint added by the Settings module. All responses are **raw DTOs in
`ResponseEntity<T>`** (no success-envelope wrapper); errors use `ApiErrorResponse`
via `AppException`. Unless noted, every route is `@PreAuthorize("isAuthenticated()")`
and reads the caller via `SecurityUtils.requireCurrentUserId()`.

## Core settings — `SettingsController` `/api/v1/settings`
| Method | Path | Body | Purpose |
|--------|------|------|---------|
| GET | `/api/v1/settings` | — | Full resolved cosmetic settings (defaults merged) |
| GET | `/api/v1/settings/{section}` | — | One cosmetic section: `appearance`\|`accessibility`\|`messages`\|`media` |
| PATCH | `/api/v1/settings/{section}` | JSON Merge Patch | Partial update of a section |
| PUT | `/api/v1/settings/appearance` | block | Replace appearance |
| PUT | `/api/v1/settings/accessibility` | block | Replace accessibility |
| PUT | `/api/v1/settings/messages` | block | Replace messaging cosmetics |
| PUT | `/api/v1/settings/media` | block | Replace media preferences |

## Privacy — `PrivacySettingsController` `/api/v1/settings/privacy`
| Method | Path | Body |
|--------|------|------|
| GET | `/api/v1/settings/privacy` | — (resolved policy map) |
| PUT | `/api/v1/settings/privacy/{field}` | `{visibility}` |
| GET/POST | `/api/v1/settings/privacy/lists` | `{name}` on POST |
| DELETE | `/api/v1/settings/privacy/lists/{id}` | — |
| GET/POST | `/api/v1/settings/privacy/lists/{id}/members` | `{memberId}` on POST |
| DELETE | `/api/v1/settings/privacy/lists/{id}/members/{memberId}` | — |
| GET | `/api/v1/settings/privacy/muted` | — |
| POST/DELETE | `/api/v1/settings/privacy/muted/{userId}` | — |
| GET/POST | `/api/v1/settings/privacy/keywords` | `{keyword}` on POST |
| DELETE | `/api/v1/settings/privacy/keywords/{id}` | — |

## Presence — `PresenceSettingsController` `/api/v1/settings/presence`
| Method | Path | Body |
|--------|------|------|
| GET | `/api/v1/settings/presence` | — |
| PUT | `/api/v1/settings/presence` | `{onlineStatusPolicy, lastSeenPolicy}` (EVERYONE\|FRIENDS\|NOBODY) |

## Notifications — `NotificationSettingsController` `/api/v1/settings/notifications`
| Method | Path | Body |
|--------|------|------|
| GET | `/api/v1/settings/notifications` | — (event×channel matrix) |
| PUT | `/api/v1/settings/notifications/{eventType}/{channel}` | `{enabled}` |
| GET | `/api/v1/settings/notifications/dnd` | — |
| PUT | `/api/v1/settings/notifications/dnd` | `{enabled,timezone,startTime,endTime,daysMask,muteUntil}` |
| GET/POST | `/api/v1/settings/notifications/push-tokens` | `{provider,token,platform,sid}` on POST |
| DELETE | `/api/v1/settings/notifications/push-tokens/{id}` | — |

## Discovery — `DiscoverySettingsController` + `QrDiscoveryController`
| Method | Path | Body |
|--------|------|------|
| GET | `/api/v1/settings/discovery` | — |
| PUT | `/api/v1/settings/discovery` | `{byUsername,byPhone,byEmail,byQr,indexable}` |
| GET | `/api/v1/settings/discovery/qr` | — (opaque token + `irc://u/{opaque}`) |
| POST | `/api/v1/settings/discovery/qr/rotate` | — |
| GET | `/api/v1/discovery/qr/resolve/{opaque}` | — (resolve to public card) |

## Consent — `ConsentController` `/api/v1/settings/consent`
| Method | Path | Body |
|--------|------|------|
| POST | `/api/v1/settings/consent` | `{scope,granted,appVersion}` |
| GET | `/api/v1/settings/consent` | — (own history, paged) |
| GET | `/api/v1/settings/consent/{scope}` | — (current state) |

## Contacts — `ContactsController` `/api/v1/contacts`
| Method | Path | Body |
|--------|------|------|
| POST | `/api/v1/contacts/sync` | `{hashes[],appVersion}` — rate-limited 3/24h, writes consent |
| DELETE | `/api/v1/contacts/sync` | — |

## Blocks — `BlocksController` `/api/v1/blocks`
| Method | Path |
|--------|------|
| GET | `/api/v1/blocks` |
| POST | `/api/v1/blocks/{userId}` |
| DELETE | `/api/v1/blocks/{userId}` |

## Auth OTP — `OtpAuthController` `/api/v1/auth/otp` *(permitAll)*
| Method | Path | Body | Note |
|--------|------|------|------|
| POST | `/api/v1/auth/otp/request` | `{phone,purpose}` | **202 always** (enumeration-safe) |
| POST | `/api/v1/auth/otp/verify` | `{phone,code,purpose}` | `{verified}` or 400 |

## Security — `SecurityController` `/api/v1/security`
| Method | Path | Body | Note |
|--------|------|------|------|
| GET | `/api/v1/security/sessions` | — | active sessions |
| DELETE | `/api/v1/security/sessions/{sid}` | — | revoke (+denylist) |
| POST | `/api/v1/security/sessions/{sid}/trust` | `{days}` | trust device |
| POST | `/api/v1/security/2fa/setup` | — | returns QR URI + secret once |
| POST | `/api/v1/security/2fa/verify` | `{code}` | confirm enrol → recovery codes |
| POST | `/api/v1/security/2fa/disable` | — | **step-up required** |
| GET | `/api/v1/security/2fa/status` | — | enabled + codes remaining |
| POST | `/api/v1/security/recovery-codes/regenerate` | — | **step-up required** |
| GET | `/api/v1/security/login-history` | — | paged |
| POST | `/api/v1/security/step-up` | `{password}` or `{code}` | arm step-up window |
| POST | `/api/v1/security/phone/request` | `{phone}` | 202 |
| POST | `/api/v1/security/phone/verify` | `{phone,code}` | bind phone |

## Media — `MediaUploadController` `/api/v1/media`
| Method | Path | Body | Note |
|--------|------|------|------|
| POST | `/api/v1/media/upload-intent` | `{mime,sizeBytes,sha256,type}` + header `X-Media-Tier` | → `{mediaId,presignedPutUrl,deduped,status}` |
| POST | `/api/v1/media/{id}/complete` | — | 202 → enqueue processing |
| GET | `/api/v1/media/{id}` | — | status + renditions |
| DELETE | `/api/v1/media/{id}` | — | 202 |

## Storage — `StorageController` `/api/v1/storage`
| Method | Path |
|--------|------|
| GET | `/api/v1/storage/usage` |

## Data & account — `DataPrivacyController`
| Method | Path | Note |
|--------|------|------|
| POST | `/api/v1/privacy/export` | 202 `{jobId}` — rate 1/30d |
| GET | `/api/v1/privacy/export/{jobId}` | status |
| GET | `/api/v1/privacy/export/{jobId}/download` | streams the ZIP |
| DELETE | `/api/v1/privacy/history/{type}` | `search`\|`watch` |
| POST | `/api/v1/account/deletion/request` | 202 (SEAM: step-up) |
| POST | `/api/v1/account/deletion/cancel` | restore within grace |

## Safety — `SafetyController` `/api/v1/safety`
| Method | Path | Note |
|--------|------|------|
| POST | `/api/v1/safety/reports` | submit (dedup by target+reason) |
| GET | `/api/v1/safety/reports` | own reports, **coarse outcome only** |
| POST | `/api/v1/safety/reports/{id}/appeal` | reporter-only |
| GET | `/api/v1/safety/strikes` | active strikes |
| GET | `/api/v1/safety/score` | derived security-score checklist |

## About — `AppInfoController` `/api/v1/app`
| Method | Path | Auth |
|--------|------|------|
| GET | `/api/v1/app/config` | **permitAll** (min-version gate) |
| GET | `/api/v1/app/policies/{key}` | **permitAll** |
| POST | `/api/v1/app/policies/{key}/accept` | auth |
| GET | `/api/v1/app/policies/me/accepted` | auth |

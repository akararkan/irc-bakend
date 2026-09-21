# Authentication, Phone/OTP, 2FA & Sessions (§2, §4, §12)

**[B] Backend-owned.** Everything here is enforced server-side. Pure JDK — no
new crypto libraries (offline build). See [implementation-notes](implementation-notes.md)
for the divergences (BCrypt not Argon2; phone-primary-login is a seam).

## Phone registration & OTP (§2)

`security.otp.OtpService` is the reusable OTP engine, used by phone verification,
email verification, sensitive changes and step-up:

- **6-digit code** from `SecureRandom`; the plaintext is **never stored** — only
  `HMAC-SHA256(code, pepper)` (`security.crypto.Hashing`, `app.otp.pepper`).
- **TTL in Redis** (`otp:{purpose}:{destHash}`) with a Postgres `otp_challenges`
  row as the durable audit trail; Redis expiry does cleanup for free.
- **Max 5 attempts** per challenge, then it is burned.
- **Resend rate-limited** 3/hour/number and 10/hour/IP (`RateLimiter`, keyed by a
  deterministic UUID derived from the E.164 / IP).
- **Enumeration-safe:** `POST /api/v1/auth/otp/request` always returns **202**,
  identical whether or not the number is registered.

`issueFor` / `verifyFor` are the destination-generic core (an already-normalised
destination + an explicit TTL); `requestOtp` / `verifyOtp` are those plus E.164
normalisation, rate limiting and dispatch. Non-phone flows call the generic pair
so there is exactly one implementation of the hashing and attempt bookkeeping.

`security.phone.PhoneNormalizer` normalises input to E.164 (`+9647501234567`)
with a conservative pure-JDK algorithm and a default calling code
(`app.phone.default-calling-code`, Iraq `964`). It strips a trunk `0` both
before and *after* the country code, so `07701565811`, `+9647701565811` and
`+96407701565811` all collapse to one number — they must, because the stored
key is `HMAC(e164, pepper)` and two spellings would otherwise be two people.

**Endpoints:** `POST /api/v1/auth/otp/request`, `POST /api/v1/auth/otp/verify`
(both permitAll). Logged-in phone binding: `POST /api/v1/security/phone/request`
+ `/verify` (`security.phone.PhoneService` — sets `users.phone_e164` /
`phone_hmac` / `phone_verified_at`).

> **Seam:** minting a JWT session for a phone-*primary* account is a small
> addition to `AuthServiceImpl`; verify currently returns `{verified:true}`.

### Code delivery (`security.otp.delivery.OtpDeliveryService`)

`app.otp.delivery` (`OTP_DELIVERY`) picks the transport. **There is no SMS
gateway** — `LoggingSmsSender` only writes to the log — so the default is email:

| Value | Behaviour |
|---|---|
| `email` *(default)* | mails the code to the account address via `EmailService` |
| `sms` | hands off to `SmsSender`; log-only until a real gateway bean exists |
| `log` | prints the code, sends nothing — local dev and CI |

For phone *verification* the recipient address is passed down from
`PhoneService` (the number is unbound until confirmed, so the owner cannot be
resolved from it); for phone *login* it is looked up by number. Delivery is
best-effort — a transport failure leaves the challenge valid and is logged
rather than thrown, so it can never leak whether an address exists.

## Email verification (§4)

Confirms the address already on the account and sets `users.email_verified_at`.
That single flag carries **50 of the 100 security-checkup points** — both
"Account recovery configured" (30) and "Email address verified" (20) read
`User.isEmailVerified()` in `SecurityScoreService`.

`security.email.EmailVerificationService`, over the same OTP engine:

- **6-digit code**, TTL **15 minutes** (email is read later than an SMS), max 5
  attempts, single use, `OtpPurpose.EMAIL_VERIFY`-scoped so an `EMAIL_CHANGE`
  code can never be redeemed as proof of ownership of the current address.
- **3 sends per account/hour, 10 per IP/hour** — the phone budget, but keyed on
  the account so a resend storm can't be spread across sessions.
- On success: `emailVerifiedAt = now` + an `AuditAction.EMAIL_VERIFY` row
  ("Email verified by user").
- Fired **on registration** too, best-effort, so a new account arrives with a
  code already in the inbox. A mail failure there never fails the signup.

**Endpoints** (both `isAuthenticated()`):

| | |
|---|---|
| `POST /api/v1/security/email/request` | **no body** — 202 |
| `POST /api/v1/security/email/verify` | `{"code":"255758"}` → `{"verified":true,"email":"…"}` |

> **The destination is never in the request.** It is read off the account. A
> client-supplied address would let anyone mark an address they don't own as
> verified — and account recovery trusts that flag.

Errors: `409 EMAIL_ALREADY_VERIFIED` (flag already set, on both endpoints),
`400 OTP_INVALID` (wrong / expired / spent — one code covers all three),
`400 EMAIL_MISSING` (no address on the account), `429` on either budget.

With mail disabled the code is logged exactly like the SMS stub, so the flow
stays testable offline:

```
[EMAIL-DEV] to=user@example.com code=255758 purpose=EMAIL_VERIFY
```

`GET /api/v1/users/me` already exposes the flag as **`isEmailVerified`**
(`UserResponse`) — that is the field the UI renders the verified state from.

**Admin override unchanged:** `POST /admin/users/{id}/email/verify`
(`AdminUserServiceImpl.markEmailVerified`) still sets the same flag without a
code, for support cases, and writes `ADMIN_EMAIL_VERIFY` to the admin audit.

## Account settings & sensitive changes (§4)

Profile fields live on the existing `user_profiles`. Phone verification adds the
phone HMAC (spec §3). The re-verification pattern for phone/email/password
(verify current → OTP new → commit → revoke other sessions → notify old) reuses
the existing `AuthServiceImpl.changePassword` precedent (which already revokes
other sessions) plus the OTP engine above.

## Two-factor authentication (§12)

> **Full reference — including the end-user authenticator-app walkthrough, the
> two-leg login flow, recovery, and the OTP engine:
> [two-factor-authentication.md](two-factor-authentication.md).** This section is
> the architecture summary only.

**2FA is enforced at login.** A correct password on a 2FA account issues no
session — `POST /auth/login` returns `{mfaRequired: true, mfaToken}` and the
client must redeem it at `POST /auth/login/2fa` with a TOTP **or recovery** code.
The `MFA_CHALLENGE` token type is rejected by the JWT filter, is single-use, and
is capped at 5 attempts by a fail-closed Redis record. Earlier builds enrolled
the authenticator but never asked for a code at sign-in — 2FA only gated step-up,
and recovery codes had no redemption path at all.

`security.twofa.TotpProvider` — **RFC 6238 TOTP in pure JDK** (`javax.crypto`
HmacSHA1, Base32, 6 digits, 30s step, ±1 drift). `security.twofa.TwoFactorService`:

- **Secret** generated server-side, returned **once** as an `otpauth://`
  provisioning URI, then stored **encrypted at rest** (AES-GCM,
  `security.crypto.SecretCipher`, key `app.security.twofa.secret-key`).
- **Enrolment committed only after** the first code verifies (`confirmEnable`) —
  a mis-scanned QR can't lock the user out.
- **Replay guard:** the last accepted step index (`users.two_factor_last_step`)
  is stored; a code can't be used twice.

**Recovery codes** (`security.twofa.RecoveryCodeService`): 10 single-use codes,
shown once, stored as BCrypt hashes; regenerating invalidates the previous set.

**Endpoints:** `POST /2fa/setup` (**step-up** — binding a new authenticator is as
sensitive as removing one), `POST /2fa/verify` (→ recovery codes on first
enable), `POST /2fa/disable` (**step-up**), `GET /2fa/status`,
`POST /recovery-codes/regenerate` (**step-up**), plus the login leg
`POST /api/v1/auth/login/2fa`.

## Sessions, login history, step-up (§12)

- **Active sessions** = a read of `refresh_tokens` (evolved with `sid`,
  `device_name`, `platform`, `last_seen_at`, `trusted_until`, …). `SessionService`
  lists/revokes/trusts. Revoke flips `is_revoked` + writes the `sid` to
  `SessionDenylist` (Redis) for the remaining access-token window.
  `GET /security/sessions`, `DELETE /security/sessions/{sid}`,
  `POST /security/sessions/{sid}/trust`.
- **Login history** (`login_events`, append-only) via `LoginEventService`; a
  new-IP success emits a security alert (`NotificationService.sendSystemNotification`,
  which bypasses prefs/DND). `GET /security/login-history`.
- **Step-up** (`security.stepup.StepUpService`): a short-TTL Redis marker armed by
  a fresh password or 2FA code (`POST /security/step-up`), required by
  `disable 2FA` / `regenerate recovery codes` (and, as a seam, account deletion).

# Two-Factor Authentication, Authenticator Apps & OTP

Everything about the second factor: how a user sets up an authenticator app on
their phone, what the login flow looks like once 2FA is on, how recovery works
when the phone is lost, and the SMS-OTP engine that sits alongside it.

This page is the canonical reference. [auth-sessions.md](auth-sessions.md) keeps
the architecture-level summary and links here.

---

## 1. What protects an account

| Factor | What it is | Where it is required |
|---|---|---|
| Password | BCrypt, verified by Spring Security | Every login |
| **TOTP** (authenticator app) | RFC 6238, 6 digits, 30-second step | Every login **once enabled**; step-up |
| **Recovery code** | 10 single-use codes, shown once | Login, when the authenticator is unavailable |
| **SMS OTP** | 6-digit code, 5-minute TTL | Phone-number verification |
| **Step-up** | Fresh password or TOTP, short Redis window | Enabling/disabling 2FA, regenerating recovery codes, sensitive admin actions |

> **The login gate is real.** With 2FA enabled, a correct password on its own
> issues **nothing** — no session, no cookies, not even the user object. Earlier
> builds enrolled the authenticator but never asked for the code at sign-in, so
> the feature was decorative; that is fixed and the flow below is enforced.

---

## 2. Setting up an authenticator app *(end-user walkthrough)*

Any RFC 6238 app works — **Google Authenticator, Microsoft Authenticator, Authy,
1Password, Bitwarden, Aegis, Raivo**. Nothing proprietary is required: the
account is provisioned with a standard `otpauth://` URI.

**On the phone, one time:** install one of the apps above from the App Store or
Google Play.

**Then, in IRC:**

1. **Confirm it is really you.** Go to *Settings → Security → Two-factor
   authentication* and enter your password when prompted. (This is the step-up
   window — see §5. Without it, setup returns `403 STEP_UP_REQUIRED`.)
2. **Start setup.** The app shows a **QR code** plus a written **secret key**.
3. **Add it to your authenticator:**
   - *Scanning:* open the authenticator, tap **+** → **Scan a QR code**, point it
     at the screen.
   - *No camera / desktop-only app:* tap **+** → **Enter a setup key**, type the
     written secret, choose **Time-based**.
4. The authenticator now lists an entry like **IRC (yourusername)** with a
   6-digit code that changes every 30 seconds.
5. **Type the current 6-digit code back into IRC** and confirm.
6. **Save your recovery codes.** Ten codes are displayed **once, and only at this
   moment**. Screenshot them, print them, or store them in a password manager —
   §4 explains why this matters.

2FA is now on. Nothing else changes until your next sign-in.

### If the code is rejected

Almost always **clock drift** — TOTP is time-based, and the server accepts only
±30 seconds. On the phone, enable automatic date & time (Android: *Settings →
System → Date & time → Set time automatically*; iOS: *Settings → General → Date
& Time → Set Automatically*). Google Authenticator also has *Settings → Time
correction for codes → Sync now*.

Other causes: the code expired mid-typing (wait for the next one), or the entry
was added to the authenticator twice and you are reading the stale one.

---

## 3. Signing in with 2FA *(two legs)*

```
POST /api/v1/auth/login          →  password accepted, but 2FA is on
   ← 200 { "mfaRequired": true, "mfaToken": "<jwt>", "expiresIn": 300 }

POST /api/v1/auth/login/2fa      →  { "mfaToken": "<jwt>", "code": "123456" }
   ← 200 { "accessToken": …, "refreshToken": …, "user": … }
```

**Leg 1 — `POST /api/v1/auth/login`** (unchanged request shape). For an account
without 2FA the response is exactly as before. For a 2FA account the response
carries `mfaRequired: true` and an `mfaToken`, and **no session is created**.
`mfaRequired` is omitted entirely (never `false`) on ordinary logins, so a client
can branch on its presence.

**Leg 2 — `POST /api/v1/auth/login/2fa`** with the `mfaToken` and the user's
code. `code` accepts **either** the 6-digit authenticator code **or** a recovery
code — the server tries TOTP first, then recovery. On success the ordinary token
pair, cookies and user payload are returned.

### The `mfaToken`

A signed JWT of type `MFA_CHALLENGE`, and deliberately weak on purpose:

- **Cannot authenticate anything.** The JWT filter only ever accepts `ACCESS`
  (and read-only `IMPERSONATION`) tokens, so presenting an `mfaToken` as a Bearer
  credential fails like any garbage string.
- **Carries no identity.** Subject and type only — no role, no authorities, no
  email. A leaked challenge discloses nothing about the account.
- **5 minutes** (`app.security.twofa.challenge-ttl-seconds`).
- **Single use.** Redemption deletes the server-side record; two concurrent
  redemptions cannot both mint a session.
- **5 attempts** (`app.security.twofa.max-challenge-attempts`), counted
  atomically. Blow the ceiling and the challenge is burned — the user re-enters
  their password to get a new one. This ceiling is what keeps a 6-digit code out
  of brute-force range.

> **This gate fails closed.** Challenge state lives in Redis. Every other limiter
> on the platform fails *open* for availability; this one does not, because an
> unreachable Redis would otherwise mean unlimited unmetered guessing against the
> second factor. If Redis is down, 2FA logins are refused with
> `MFA_CHALLENGE_INVALID` until it returns. Password-only accounts are unaffected.

### Client / mobile integration

```jsonc
// 1. normal login call
POST /api/v1/auth/login  { "username": "…", "password": "…" }

// 2. branch on the response
if (res.mfaRequired) {
  //   → show a 6-digit code entry screen
  //   → keep res.mfaToken in memory ONLY (never localStorage — it is a credential)
  //   → offer a "use a recovery code instead" link on the same screen
  //   → on submit:
  POST /api/v1/auth/login/2fa  { "mfaToken": res.mfaToken, "code": entered }
} else {
  //   → already signed in, store the token pair as usual
}
```

Handle these on leg 2: `MFA_CODE_INVALID` → keep the screen open, let them retry;
`MFA_TOO_MANY_ATTEMPTS` and `MFA_CHALLENGE_INVALID` → send them back to the
password screen (the challenge is gone). Use `expiresIn` to show a countdown and
return to the password screen when it lapses. On mobile, set the code field to
`inputmode="numeric"`, `autocomplete="one-time-code"` and 6 characters so the OS
keyboard and paste behave.

---

## 4. Losing the phone — recovery

Three routes back in, in order of preference:

**1. A recovery code.** Enter it in the code field on the 2FA screen instead of a
6-digit code. Each is single-use. Ten are issued at enrolment, shown once;
`GET /api/v1/security/2fa/status` reports how many remain. Regenerate a fresh set
any time with `POST /api/v1/security/recovery-codes/regenerate` (step-up
required) — this invalidates every code from the previous set.

**2. Turn 2FA off from a signed-in session.** `POST /api/v1/security/2fa/disable`
(step-up required) also clears the recovery codes. Useful when you still have a
live session on another device and simply want to re-enrol a new phone.

**3. Admin reset — last resort.** If the authenticator *and* all recovery codes
are gone, an administrator resets the second factor. It disables 2FA, clears the
recovery codes, **revokes every active session**, writes an `ADMIN_2FA_RESET`
audit row, and notifies the account owner. Admins cannot do this to themselves
(they must use their own security settings), so the action always has a second
pair of eyes. See [admin/users/](../admin/users/user-administration.md).

> Enrolling a **new** phone is deliberately not automatic: 2FA must be disabled
> and set up again, so a stolen session cannot silently swap the authenticator to
> a device the real owner does not hold.

---

## 5. Step-up authentication

A short-lived Redis marker proving the person at the keyboard just re-authenticated.

`POST /api/v1/security/step-up` with **either** `{"password": "…"}` **or**
`{"code": "123456"}` (a TOTP code). Both arm the window; `204` on success.

Required by — and this is the security-relevant part — **enabling 2FA**
(`/2fa/setup`), **disabling 2FA**, and **regenerating recovery codes**. Setup is
gated because binding a new authenticator is exactly as sensitive as removing
one: without the re-auth, somebody holding a stolen session could enrol *their
own* device and lock the real owner out of their account.

Missing window → `403 STEP_UP_REQUIRED`. Wrong password → `400
STEP_UP_BAD_PASSWORD`. Wrong code → `400 TWO_FA_INVALID`. Neither supplied →
`400 STEP_UP_REQUIRED` with the "provide your password or a code" message.

---

## 6. SMS OTP (phone verification)

A separate engine from TOTP: 6-digit codes sent by SMS, used to prove ownership
of a phone number — which is what turns on phone-based contact discovery
([discovery-contacts.md](discovery-contacts.md)).

| Endpoint | Purpose |
|---|---|
| `POST /api/v1/security/phone/request` `{phone}` | Send a code (authenticated) |
| `POST /api/v1/security/phone/verify` `{phone, code}` | Bind + mark verified |
| `POST /api/v1/auth/otp/request` `{phone, purpose}` | Pre-auth OTP; always `202` |
| `POST /api/v1/auth/otp/verify` `{phone, code, purpose}` | Verify |

**How it is protected:**

- The plaintext code is **never stored** — only `HMAC-SHA256(code, pepper)`. A
  database leak does not expose live codes.
- **5-minute TTL**, **5 attempts**, then the challenge is burned. Attempts are
  counted with an atomic Redis `INCR`, so parallel guesses cannot race past the
  ceiling — a read-modify-write on a database row previously allowed exactly that.
- **Single use:** the winning verification deletes the challenge key; a second
  redemption of the same code fails.
- **Fails closed.** If the challenge store is unreachable, verification is
  refused rather than proceeding without an attempt ceiling.
- **Rate limits:** 3 sends per number per hour, 10 per IP per hour.
- **Enumeration-safe:** `/auth/otp/request` answers `202` unconditionally and
  never reveals whether a number is registered.
- **One number, one account:** a number already verified on another live account
  is refused with `409 PHONE_ALREADY_BOUND`. Without this, one contact hash would
  resolve to several accounts and every synced address book would surface strangers.

The Postgres `otp_challenges` row is the **audit trail**; Redis holds the live
challenge. Prior builds wrote the Redis key but read only Postgres, so expiry and
the attempt ceiling were both weaker than documented.

---

## 7. Configuration

```yaml
app:
  security:
    twofa:
      issuer: IRC                     # the label shown in the authenticator app
      recovery-code-count: 10
      allowed-drift-steps: 1          # ±30s clock tolerance
      challenge-ttl-seconds: 300      # MFA challenge lifetime
      max-challenge-attempts: 5       # codes per challenge before it burns
      secret-key: <base64>            # AES-GCM key for TOTP secrets at rest
  otp:
    code-length: 6
    ttl-seconds: 300
    max-attempts: 5
    pepper: <secret>                  # MUST be set in production
    resend-per-number-per-hour: 3
    resend-per-ip-per-hour: 10
```

TOTP secrets are stored **encrypted at rest** (AES-GCM via `SecretCipher`).
Recovery codes are stored only as BCrypt hashes. Both are cleared when 2FA is
disabled and when an account is erased.

---

## 8. Error codes

| Code | HTTP | Meaning |
|---|---|---|
| `MFA_CHALLENGE_INVALID` | 401 | Challenge expired, already used, unknown, or the store is unreachable → restart from the password screen |
| `MFA_CODE_INVALID` | 401 | Wrong TOTP/recovery code; the challenge survives, attempts decrement |
| `MFA_TOO_MANY_ATTEMPTS` | 401 | Ceiling hit; challenge burned → restart from the password screen |
| `TWO_FA_INVALID` | 400 | Wrong code on setup-confirm or step-up |
| `TWO_FA_ALREADY_ON` | 409 | Setup called while 2FA is already enabled |
| `TWO_FA_NOT_STARTED` | 400 | Verify called before setup |
| `STEP_UP_REQUIRED` | 403 | Sensitive action without a fresh re-auth |
| `STEP_UP_BAD_PASSWORD` | 400 | Wrong password on step-up |
| `OTP_INVALID` | 400 | Invalid/expired/exhausted SMS code |
| `PHONE_ALREADY_BOUND` | 409 | Number already verified on another account |

---

## 9. What the audit trail records

Every login attempt lands in `login_events` (`GET /api/v1/security/login-history`):

| `method` | `outcome` | Meaning |
|---|---|---|
| `PASSWORD` | `SUCCESS` | Ordinary login, no 2FA on the account |
| `PASSWORD` | `FAILED` | Wrong password |
| `PASSWORD` | `MFA_REQUIRED` | Password accepted, second factor still owed |
| `PASSWORD+TOTP` | `SUCCESS` | Full 2FA login |
| `PASSWORD+RECOVERY` | `SUCCESS` | **Recovery code redeemed** — worth alerting on |
| `PASSWORD+2FA` | `FAILED` | Wrong code at the second factor |

These rows are written in their **own transaction**. Failure paths record the
attempt and then throw, and with ordinary propagation the row was rolled back by
the very exception it documented — so the FAILED rows a brute-force investigation
depends on silently never landed.

A success from an IP never seen before also raises a security alert notification
that deliberately bypasses do-not-disturb and channel preferences.

---

## 10. Known limitations

- **Trusted devices are display-only.** `POST /security/sessions/{sid}/trust`
  stores `trusted_until` and the session list shows it, but nothing consults it —
  in particular it does **not** skip the 2FA prompt. Marking a device trusted
  currently changes nothing about how you sign in.
- **No WebAuthn / passkeys or hardware-key support.** TOTP and recovery codes are
  the whole second-factor surface.
- **No SMS second factor at login.** SMS OTP verifies a phone number; it is not
  an alternative to TOTP at sign-in.
- **2FA cannot be required org-wide.** It is opt-in per account; there is no
  policy switch to mandate it for admins or staff tiers.

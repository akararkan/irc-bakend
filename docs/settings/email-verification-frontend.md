# Verifying your email — frontend guide

The **Verify your email** card in Settings → Security, for the ika app.
Backend reference: [auth-sessions.md](auth-sessions.md#email-verification-4).
Companion: [phone-verification-frontend.md](phone-verification-frontend.md).

Worth building carefully: this one action is **50 of the 100 security-checkup
points** — it clears both *Account recovery configured* (30) and *Email address
verified* (20), because both read the same flag.

---

## 1. The two calls

Both need `Authorization: Bearer <accessToken>`; `http.js` already attaches it.

```
POST /api/v1/security/email/request     no body          → 202
POST /api/v1/security/email/verify      { "code": "…" }  → 200
```

```json
{ "verified": true, "email": "user@example.com" }
```

Add to `src/api/security.js`, next to the existing `phone` block:

```js
/* ---- email verification (logged-in) ----
   No destination in the request ON PURPOSE: the backend reads the address off
   the account, because a client-supplied one would let anyone mark an address
   they don't own as verified — and account recovery trusts that flag.
   Unlike phone binding, the result IS readable back: /users/me carries
   isEmailVerified, so this card survives a reload. */
email: {
  request() { return http.post('/api/v1/security/email/request') },        // 202; 409 EMAIL_ALREADY_VERIFIED; 429
  verify(code) { return http.post('/api/v1/security/email/verify', { code }) },  // {verified,email}; 400 OTP_INVALID
},
```

The code is **6 digits** and expires in **15 minutes** — *not* the 5 minutes the
phone card promises. Don't copy that string.

## 2. Reading the state

`meFrom` in `src/api/adapters.js` already maps it:

```js
emailVerified: !!u.isEmailVerified,
```

So the card reads **`me.emailVerified`** (view model); the wire field is
`isEmailVerified`. No adapter change needed.

- `true` → green verified state, no button, no input.
- `false` → **Send code** → 6-digit input → **Verify**.

After a successful verify, refetch `/users/me` **and** bump the security-score
panel. Two checklist items and the headline number all change at once, and a
stale 50/100 next to a freshly verified address is the most visible way to get
this wrong. `SecurityScorePanel` already re-runs on its `tick` state — reuse
that mechanism rather than adding a second refresh path.

## 3. Wiring the checkup rows

`SCORE_ACTION` in `SecurityExtraPanel.jsx` currently has only two entries, and
the comment above it says *"there is no email-verification endpoint on the
backend at all"* — **that is now out of date; delete it.** Add the two missing
keys, both pointing at the new card:

```js
const SCORE_ACTION = {
  two_factor:     { label: 'Set up 2FA',    to: '/settings/security#two-factor' },
  recovery:       { label: 'Verify email',  to: '/settings/security#verify-email' },
  email_verified: { label: 'Verify email',  to: '/settings/security#verify-email' },
  recent_review:  { label: 'Review sessions', to: '/settings/sessions#sessions' },
}
```

Both keys route to the same card by design — the backend scores one flag twice.
The rows disappear together the moment it flips, so there is no state where one
says "done" and the other doesn't.

## 4. Behaviour

Follow the phone card in the same file; the shape is nearly identical, minus the
number input.

- **30-second cooldown after every send.** The budget is 3 sends per hour per
  account. A user tapping *Resend* four times is locked out for the rest of the
  hour. Reuse the phone card's `cooldown` timer and `cooldownSecondsFrom(e)` on
  a 429 so the server's own `retryAfterSeconds` wins.
- **Show which inbox to check** — render the account's address on the card.
- **Guard Enter**, like the phone card does: it bypasses a disabled button, and
  each submit burns one of only 5 attempts.
- **Mention the spam folder** on the first send. It is the most common reason a
  code "never arrived", especially the first mail from a new sender.
- The code arrives by email whether the user is verifying an address *or* a
  phone number — there is no SMS gateway. Never write "we sent you an SMS".

## 5. Errors

`http.js` exposes the envelope's `errorCode` as `e.code` and the status as
`e.status`; use `errorText(e, fallback)` so the server's specific wording wins.

| `e.status` | `e.code` | Meaning | Copy |
|---|---|---|---|
| 400 | `OTP_INVALID` | wrong, expired **or** already used — one code covers all three | `errorText(e, 'That code is wrong or has expired')` |
| 409 | `EMAIL_ALREADY_VERIFIED` | flag already set | flip to the verified state and refetch `/users/me` — the UI is stale, this is not a failure to report |
| 400 | `EMAIL_MISSING` | no address on the account | hide the card; can't happen for email-registered users |
| 429 | `RATE_LIMITED` | 3/hour/account or 10/hour/IP; `details.retryAfterSeconds` | `setCooldown(cooldownSecondsFrom(e))` — `http.js` already toasted the "slow down" line |

There is no `EMAIL_INVALID`: the address comes from the account, not the request.

Only the server knows whether a rejected code was wrong, expired or spent — the
single vague message is deliberate, so surface `e.message` instead of guessing.

## 6. Local testing

New accounts are sent a code **at registration**, so a fresh signup may already
have one waiting before the card is ever opened.

With mail disabled the backend prints the code instead of sending it:

```
[EMAIL-DEV] to=user@example.com code=255758 purpose=EMAIL_VERIFY
```

To force that path, set `OTP_DELIVERY=log` in the backend's `.env`.

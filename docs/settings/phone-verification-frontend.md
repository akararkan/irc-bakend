# Adding a phone number — frontend guide

How the "add / verify my phone number" screen must talk to the backend.
Two calls, both need `Authorization: Bearer <accessToken>`.

---

## 1. Send the code

```
POST /api/v1/security/phone/request
Content-Type: application/json

{ "phone": "07701565811" }
```

→ `202 Accepted`, empty body.

The code is **6 digits** and expires in **5 minutes**.

> **The code is emailed, not texted.** There is no SMS gateway, so the code goes
> to the address on the account. The UI copy must say so — *"We emailed a code
> to a\*\*\*@gmail.com"* — or users will sit staring at their phone waiting for
> an SMS that never comes. Show the masked account email, not the phone number.
>
> A 202 means the code was generated, not that mail was delivered (sending is
> async). Move straight to the code-entry step.

## 2. Verify the code

```
POST /api/v1/security/phone/verify
Content-Type: application/json

{ "phone": "07701565811", "code": "255758" }
```

→ `200 OK`

```json
{ "verified": true, "phone": "+9647701565811" }
```

Store the returned `phone` — that is the canonical E.164 form. Display that,
not what the user typed.

> Send the **same `phone` string** in both calls. The server re-normalises it
> each time, so different shapes of the same number are fine, but sending the
> same string keeps debugging simple.

---

## The number format — the part that was breaking

The backend normalises to E.164 (`PhoneNormalizer`). For Iraq (`+964`) these
all now collapse to the same `+9647701565811`:

| User types | Result |
|---|---|
| `07701565811` | `+9647701565811` ✅ |
| `7701565811` | `+9647701565811` ✅ |
| `+964 770 156 5811` | `+9647701565811` ✅ |
| `00964 7701565811` | `+9647701565811` ✅ |
| `+964 0770 156 5811` | `+9647701565811` ✅ *(was broken — kept the trunk `0`)* |

Spaces, dashes, dots and parentheses are stripped, so `0770-156 5811` is fine.

**Why the trunk `0` mattered.** The stored key is `HMAC(e164, pepper)`. A number
saved as `+96407701565811` hashes differently from `+9647701565811`, so the two
are treated as different people — the number would verify but never match in
contact sync. Fixed server-side; the frontend does not need to work around it.

### What the frontend should do

- **Do not** build the E.164 yourself by string-concatenating `+964` onto what
  the user typed. That is exactly how the double-`0` case was produced.
- If you show a country-code picker, send **either** the picker's code + the
  local part **or** a raw full number — never both glued together by hand.
  Simplest correct approach: one plain text input, send it as-is.
- Light client-side check only: at least 8 digits after stripping non-digits.
  Let the server be the authority on validity.
- Show the canonical `phone` from the verify response back to the user so they
  can see the number that was actually saved.

---

## Errors to handle

All errors come back in the standard error envelope (see
`docs/errors/error-handling.md`). The wire field is **`errorCode`**; ika's
`http.js` exposes it as `e.code`:

| HTTP | code | Meaning | Suggested UI |
|---|---|---|---|
| 400 | `PHONE_REQUIRED` | empty input | "Enter a phone number" |
| 400 | `PHONE_INVALID` | no digits, or length outside 8–15 | "That doesn't look like a valid number" |
| 400 | `OTP_INVALID` | wrong, expired, or already-used code — **one code covers all three**, so read the human `message` field for the specific wording | "Incorrect or expired code" + offer *Resend* |
| 409 | `PHONE_ALREADY_BOUND` | number belongs to another account | "This number is already in use" |
| 429 | rate limit | 3 sends per number/hour, 10 per IP/hour | "Too many attempts, try again later" |

Attempts are capped at **5 wrong codes** per challenge; after that the code is
burned and the user must request a new one. Disable the *Resend* button for
~30–60s after each send so users don't burn the hourly limit in one screen.

---

## Local testing

The code is emailed to the logged-in account's address — check that inbox
(and the spam folder the first time).

To skip mail entirely while developing, set `OTP_DELIVERY=log` in `.env` and
read the code off the backend console:

```
[OTP-DEV] to=+9647701565811 body="Your IRC verification code is 255758. It expires in 5 minutes."
```

`OTP_DELIVERY` accepts `email` (default), `sms` (log-only until a gateway is
wired) and `log`.

# Contact Sync, Discovery & Consent (§3, §6, §14)

"Which people from my phone's address book are already on IRC?" — how it works,
what a mobile client must send, and exactly what privacy guarantees it does and
does not provide.

---

## 1. How contact matching works

The client hashes the address book **locally** and uploads only hex digests. Raw
phone numbers and email addresses never reach the server. A match is a hash join:
*my* uploaded `CONTACT` hashes against *other users'* identity hashes.

```
my phone           IRC server
──────────         ──────────────────────────────────────────
+964 750 123 4567  →  sha256("9647501234567")  ┐
ahmad@mail.com     →  sha256("ahmad@mail.com") ├→ CONTACT rows (mine)
                                                ┘
                       joined against
                                                ┌ IDENTITY        = sha256(their email)
                       other users' ────────────┤
                                                └ IDENTITY_PHONE  = sha256(their verified phone)
```

Two identity kinds exist per account, both derived server-side:

| Kind | Derived from | Written when |
|---|---|---|
| `IDENTITY` | registered email, lower-cased + trimmed | account creation / first sync / email change |
| `IDENTITY_PHONE` | **verified** phone, E.164 **without** the `+` | the moment a phone clears OTP verification |

> **Phone matching now actually works.** Until 2026-08-11 the server only ever
> derived an identity hash from the **email** — a client could upload a thousand
> perfectly-formed phone hashes, receive `stored: 1000`, and match nobody, for
> ever. `users.phone_hmac` (a *keyed* HMAC) exists but is **not** what matching
> joins on and never could be: clients hash locally, so the server can only match
> on a function the client can also compute. A peppered HMAC is unreproducible
> client-side.

Only **verified** numbers become identities. An unverified claim would let anyone
make themselves findable by someone else's number.

**One number, one account:** verifying a number already bound to another live
account returns `409 PHONE_ALREADY_BOUND`. Without that rule a single hash would
resolve to several accounts and every address book would surface strangers.

---

## 2. Mobile client integration

### Step 1 — ask for permission, record consent

Obtain the OS address-book permission first. Consent is recorded server-side as
evidence (§14) automatically on sync, and you may also post it explicitly:
`POST /api/v1/settings/consent {scope:"CONTACTS", granted:true, appVersion}`.

### Step 2 — normalize, exactly

Both sides must produce byte-identical strings or the match silently misses.

| Type | Normalization | Example |
|---|---|---|
| **Phone** | E.164, then **strip the leading `+`**. Digits only. | `+964 750 123 4567` → `9647501234567` |
| **Email** | `trim`, then lower-case with an **invariant/root locale** | ` Ahmad@Mail.com ` → `ahmad@mail.com` |

> Use a root/invariant locale for lower-casing. On a Turkish-locale device the
> default lower-case of `I` is `ı`, which would hash differently from the server's
> value and match nothing.

Convert every address-book number to E.164 **on the device**, where the user's
region is known — a bare `07501234567` is meaningless without it. Do **not** rely
on server-side normalization: the server's fallback assumes an Iraqi country code
for bare numbers, so a foreign number would be mangled.

### Step 3 — hash

`SHA-256` over the **UTF-8 bytes** of the normalized string, hex-encoded
**lower-case**. Upper-case hex is accepted (the server lower-cases) but send
lower-case.

### Step 4 — upload

```jsonc
POST /api/v1/contacts/sync
Authorization: Bearer <access token>

{
  "hashes": ["9f86d081…", "b1946ac9…"],   // ≤ 5000, deduplicated client-side
  "appVersion": "2.4.1"
}

→ 200 { "stored": 812, "skipped": 3, "matched": 47 }
```

| Field | Meaning |
|---|---|
| `stored` | Hashes persisted after de-duplication, hex validation and the cap |
| `skipped` | Entries the server discarded — malformed, duplicate, or past the cap. Lets you distinguish "my batch was trimmed" from "nobody matched" |
| `matched` | Distinct registered accounts found — **a count only, never identities**, and only counting people whose discovery settings allow it |

**Semantics that matter:**

- **Full replace.** Every sync deletes your previous upload. Never send partial
  batches expecting them to accumulate.
- **Cap 5000 per sync**, enforced at binding time — an oversized array is
  rejected with `400`, not silently truncated.
- **Rate limit: 3 syncs per 24 hours.** Exceeding it returns `429 RATE_LIMITED`
  with `details.retryAfterSeconds`. Cache the last-synced hash set locally and
  skip the call when the address book has not changed; there is no delta protocol.

### Step 5 — surface the results

`matched` is only a number. To show *who*, read
`GET /api/v1/users/me/suggestions` — contact matches carry a `reason` of
`"in your contacts"` or `"in each other's contacts"`. A sync triggers the
suggestion recompute automatically.

### Withdrawal

`DELETE /api/v1/contacts/sync` → `204`. Wipes your uploaded hashes and records a
consent revocation.

> **Word the UI carefully.** This deletes *your* address book from the server. It
> does **not** make you undiscoverable — your own identity hashes remain, so other
> people syncing their contacts can still find you. The control for that is
> *discovery settings* (§3), not this button.

### Deprecated alias

`POST /api/v1/users/contacts/sync` and `DELETE /api/v1/users/contacts` still work
and now behave identically, but are deprecated — use `/api/v1/contacts`. They
previously bypassed **both** the rate limit and the consent record, which made the
advertised anti-enumeration ceiling meaningless: post one hash, read `matched`,
repeat without limit. Both routes now funnel through one service.

---

## 3. Discovery settings — and what they now enforce

`GET` / `PUT /api/v1/settings/discovery` → `{byUsername, byPhone, byEmail, byQr, indexable}`.
`PUT` takes any subset; omitted fields are left unchanged.

| Flag | Default | Effect |
|---|---|---|
| `byUsername` | on | Findable by @handle |
| `byPhone` | **on** | Your phone identity participates in contact matching |
| `byEmail` | **on** | Your email identity participates in contact matching |
| `byQr` | on | Your QR token resolves to your profile card |
| `indexable` | on | Public profile is search-engine indexable |

> **These used to enforce nothing.** `isDiscoverableBy` had zero callers: a user
> could switch `byPhone` off, watch it persist, and remain fully matchable. The
> settings screen was lying. The flags are now applied **inside** the contact-match
> join and inside QR resolution — turning one off genuinely removes the account
> from that channel.
>
> `byPhone`/`byEmail` also **changed default from off to on**. Enforcing the old
> defaults would have switched contact discovery off for everybody; on-by-default
> matches WhatsApp/Telegram/Signal, and the toggle is now real for anyone who
> wants out.

QR resolution of a user with `byQr=false` returns **404**, not 403 — identical to
an unknown token, so the response cannot be used to probe the setting.

---

## 4. Privacy properties — stated honestly

**What holds:**

- Raw contacts never reach the server; only 64-char hex digests are accepted.
- Nothing raw is logged — the sync path logs counts only.
- Matching honours each target's own discovery settings.
- Soft-deleted accounts are excluded from matches and from the `matched` count.
- Users can wipe their upload at any time; account erasure removes both the
  uploaded hashes and the identity rows, and clears the stored phone number.

**What does not hold — do not claim otherwise in product copy:**

- **The hash is unsalted SHA-256, and phone numbers are a small space.** A
  national mobile range is ~10⁸–10⁹ candidates: anyone with read access to
  `user_contact_hashes` can brute-force the digests back to plaintext numbers
  offline. This is inherent to client-side-hashed contact discovery and is why
  Signal moved to SGX-backed private set intersection. Salting is impossible here
  — the client must be able to reproduce the value. **Treat the table as
  containing address books in the clear** for access-control and breach purposes.
- **`matched` is still a membership oracle**, just a rate-limited one: 3 syncs ×
  5000 hashes = 15 000 membership probes per account per day.
- **No retention window.** Uploaded hashes live until explicitly deleted or the
  account is purged. Do not promise auto-expiry.
- **The bidirectional reason string leaks a little.** `"in each other's contacts"`
  tells the viewer that the other person has *their* details saved — a fact that
  person never explicitly consented to reveal.

---

## 5. Consent evidence (§14)

`consent_events (user_id, scope, granted, app_version, occurred_at)` — append-only
via `ConsentService`. Consent is recorded **before** the hashes are stored, so the
evidence trail can never show data held without a recorded grant, and only when
the client actually sent something.

`POST /api/v1/settings/consent`, `GET /api/v1/settings/consent` (history),
`GET /api/v1/settings/consent/{scope}` (current state).

Admin oversight: `GET /api/v1/admin/discovery/contact-sync/stats` and
`.../compliance` (step-up) flag owners holding hashes without a recorded consent.

---

## 6. QR discovery

`QrTokenService` mints a rotatable **opaque** token (192 bits, not a raw user id):
`irc://u/{opaque}`. Rotating stops previously-printed codes working — that is the
point.

- `GET /api/v1/settings/discovery/qr` → token + URI (minted on first call)
- `POST /api/v1/settings/discovery/qr/rotate` → new token
- `GET /api/v1/discovery/qr/resolve/{opaque}` → the owner's public card, subject
  to `byQr`

---

## 7. Known limitations

- Unsalted hashing (§4) — the fundamental one.
- No retention sweep for `user_contact_hashes`.
- The rate limiter is calendar-bucketed rather than a true sliding window, so a
  caller gets 3 syncs at 23:59 UTC and 3 more at 00:01; and it **fails open** if
  Redis is unavailable.
- `matched` still counts accounts that have blocked you (they are correctly
  filtered out of the suggestions themselves, just not the count).
- The identity backfill at startup is unpaginated — fine at current scale, a
  memory risk on a very large user table.

# Contacts, Search & Discovery, Consent (§3, §6, §14)

## Contact synchronization (§3) — **[B]**

Reuses the platform's existing privacy-preserving `ContactMatchService` (the
client hashes contacts locally and uploads only hashes; a match is a hash join
against server-side identity hashes; raw numbers never reach the server).

`settings.contacts.ContactsController` adds the spec-named alias:
- **`POST /api/v1/contacts/sync`** `{hashes[],appVersion}` — **rate-limited 3/24h**
  (blocks the "upload the whole national number range and enumerate the user
  base" attack), records a **consent event** (§14), returns `{stored, matched}`.
- **`DELETE /api/v1/contacts/sync`** — purges the user's uploaded hashes + records
  a revocation consent event.

> **Divergence:** the existing model uses client `SHA-256(email)`. The spec
> prefers a server-side **HMAC with a pepper** for phone numbers; `users.phone_hmac`
> now exists (computed with `app.security.contact.pepper`), so phone-based matching
> lights up once accounts are phone-verified. See [implementation-notes](implementation-notes.md).

## Search & discovery flags (§6) — **[B]**

`user_discoverability (by_username, by_phone, by_email, by_qr, indexable)` via
`DiscoverabilityService`. Defaults: username **on**, phone/email **off**, QR
**on**, indexable **on**.

- **`GET/PUT /api/v1/settings/discovery`** `{byUsername,byPhone,byEmail,byQr,indexable}`.
- `isDiscoverableBy(userId, method)` gates the search / directory paths;
  `isIndexable(userId)` drives the public-profile `X-Robots-Tag`.

> **Seam:** the reference design denormalises `discover.*` + `indexable` into the
> Elasticsearch document (`UserSearchService.buildDoc` + a `profile.updated`
> reindex) so an undiscoverable user costs zero extra queries. Today the flags
> live in this table and gate via the service; the ES projection is the wiring
> to add. Search-engine `noindex` + sitemap omission is enforced in the Next.js
> public-profile route.

### QR discovery (`settings.discovery.qr`)

`QrTokenService` mints a rotatable **opaque** token (not a raw user id):
`irc://u/{opaque}`. Regenerating stops old printed codes working — that is the point.

- **`GET /api/v1/settings/discovery/qr`** → token + URI.
- **`POST /api/v1/settings/discovery/qr/rotate`** → new token.
- **`GET /api/v1/discovery/qr/resolve/{opaque}`** → the owner's public card.
  **Seam:** should additionally check the target's `by_qr` flag.

## Device permissions / consent evidence (§14) — **[C]/[B]**

OS-granted permissions are **[C]** (the settings screen deep-links into the OS
settings). The backend's only role is **consent evidence**:
`consent_events (user_id, scope, granted, app_version, occurred_at)` — append-only
via `ConsentService`. This is what lets the platform demonstrate that contact
sync (§3) was consented to, and when it was withdrawn.

- **`POST /api/v1/settings/consent`** `{scope,granted,appVersion}`,
  **`GET /api/v1/settings/consent`** (history), **`GET /.../consent/{scope}`** (current state).

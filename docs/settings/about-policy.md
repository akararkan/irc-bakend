# About, App-Config & Policy Acceptance (§19)

**Mostly [C] static content; two items are [B].** Package `settings.policy`.

## App config — the client version gate (**[B]**)

`AboutProperties` (`app.about.*`) holds `minSupportedVersion`, `forceUpdate`,
`latestVersion` and the current policy versions.

- **`GET /api/v1/app/config`** (**permitAll**) → `{minSupportedVersion, forceUpdate,
  latestVersion}`. This is the only reliable way to retire a client that has a
  security defect — the client version-gates itself against it before login.

## Policy documents & acceptance (**[B]**)

- **`GET /api/v1/app/policies/{key}`** (**permitAll**, `key` ∈ `privacy`|`terms`|
  `guidelines`) → `{key, version, effectiveDate, title, body}`.
- **`POST /api/v1/app/policies/{key}/accept`** — records a `policy_acceptances`
  row `(user_id, policy_key, version, accepted_at)` so a **re-consent prompt** can
  be triggered when a policy changes materially.
- **`GET /api/v1/app/policies/me/accepted`** → the caller's acceptances.

## Static content (**[C]**)

Application version, build number, licenses, Help Center, FAQ, contact support,
release notes are static/client-owned and not modelled server-side beyond the
config + policy endpoints above.

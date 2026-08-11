# Settings — Data Model

Every new table the module creates (Hibernate `ddl-auto=update` auto-creates
them), plus the **additive** columns added to two existing tables. All ids are
`java.util.UUID`. New `NOT NULL` columns carry a `columnDefinition` DEFAULT so
auto-DDL can add them to an existing table.

## New tables

### Core (`settings.core`, `settings.audit`)
| Table | PK | Key columns |
|-------|----|-------------|
| `user_settings` | `user_id` | `appearance` / `accessibility` / `messages` / `media` **JSONB**, `settings_version` |
| `settings_audit` | `id` | `user_id`, `setting_key`, `old_value` TEXT, `new_value` TEXT, `ip`, `created_at` |

### Privacy (`settings.privacy`)
| Table | PK | Key columns |
|-------|----|-------------|
| `user_privacy` | `user_id` | `privacy` **JSONB** (`{FieldKey → VisibilityLevel}`) |
| `privacy_lists` | `id` | `owner_id`, `name`, `type` (`CLOSE_FRIENDS`\|`CUSTOM`), `created_at` |
| `privacy_list_members` | (`list_id`,`member_id`) | `added_at` |
| `user_mutes` | (`muter_id`,`muted_id`) | `created_at` |
| `hidden_keywords` | `id` | `user_id`, `keyword_display`, `keyword_normalized` (unique per user) |

### Presence (`settings.presence`)
| Table | PK | Key columns |
|-------|----|-------------|
| `user_presence_policy` | `user_id` | `online_status_policy`, `last_seen_policy` (`EVERYONE`\|`FRIENDS`\|`NOBODY`) |

### Notifications (`settings.notification`)
| Table | PK | Key columns |
|-------|----|-------------|
| `user_notification_prefs` | `id` | `user_id`, `event_type`, `channel`, `enabled` — unique (`user_id`,`event_type`,`channel`) |
| `user_dnd` | `user_id` | `timezone` (IANA), `enabled`, `start_time`, `end_time`, `days_mask`, `mute_until` |
| `push_tokens` | `id` | `user_id`, `sid`, `provider`, `token` (unique), `platform`, `last_seen_at` |

### Discovery & consent (`settings.discovery`, `settings.consent`)
| Table | PK | Key columns |
|-------|----|-------------|
| `user_discoverability` | `user_id` | `by_username`, `by_phone`, `by_email`, `by_qr`, `indexable` |
| `qr_tokens` | `id` | `user_id` (unique), `opaque_token` (unique), `rotated_at`, `created_at` |
| `consent_events` | `id` | `user_id`, `scope`, `granted`, `app_version`, `occurred_at` (append-only) |

### Safety (`settings.safety`)
| Table | PK | Key columns |
|-------|----|-------------|
| `reports` | `id` | `reporter_id`, `target_type`, `target_id`, `reason`, `details`, `state`, `resolution` (private), `group_key` (=`target:reason` dedup), `created_at` |
| `user_strikes` | `id` | `user_id`, `report_id`, `reason`, `issued_at`, `expires_at` (issued + 90d) |

### Data & policy (`settings.data`, `settings.policy`)
| Table | PK | Key columns |
|-------|----|-------------|
| `export_jobs` | `id` | `user_id`, `status`, `file_path`, `size_bytes`, `error_message`, `created_at`, `ready_at`, `expires_at` |
| `account_deletion_requests` | `id` | `user_id`, `status`, `requested_at`, `purge_after` (+30d), `resolved_at` |
| `deleted_accounts` | `id` (=old user id) | `deleted_at` — tombstone preventing id reuse |
| `policy_acceptances` | (`user_id`,`policy_key`) | `version`, `accepted_at` |

### Security (`security.otp`, `security.login`, `security.twofa`)
| Table | PK | Key columns |
|-------|----|-------------|
| `otp_challenges` | `id` | `destination_hash` (HMAC), `code_hash` (HMAC), `purpose`, `attempts`, `expires_at`, `consumed_at`, `ip`, `device_id` |
| `login_events` | `id` | `user_id`, `ts`, `ip`, `coarse_geo`, `user_agent`, `method`, `outcome` (append-only) |
| `recovery_codes` | `id` | `user_id`, `code_hash` (BCrypt), `used_at` |

### Media (`media.entity`)
| Table | PK | Key columns |
|-------|----|-------------|
| `media_assets` | `id` | `owner_id`, `type`, `status`, `content_hash` (SHA-256, indexed for dedup), `original_bytes`, `stored_bytes` (powers §15), `width`, `height`, `duration_ms`, `requested_tier`, `blurhash`, `mime`, `error_message`, `purge_original_at` |
| `media_renditions` | (`media_id`,`label`) | `object_key`, `url`, `bytes`, `width`, `height`, `mime` |

## Additive columns on existing tables

### `users` (spec §2, §8, §12)
| Column | Purpose |
|--------|---------|
| `phone_e164` | normalised E.164 phone (nullable until verified) |
| `phone_hmac` | keyed HMAC of the phone. **Not** what contact matching joins on (that is the unkeyed `IDENTITY_PHONE` hash in `user_contact_hashes` — clients hash locally and cannot reproduce a pepper). The raw number is stored alongside in `phone_e164`; both are cleared on account erasure |
| `phone_verified_at` | phone verification timestamp |
| `two_factor_last_step` | TOTP replay guard — last accepted step index |
| `timezone` | IANA zone for DND (e.g. `Asia/Baghdad`) |

> `two_factor_enabled` and `two_factor_secret` already existed on `users` but were
> inert — they are now driven by `security.twofa.TwoFactorService` (the secret is
> AES-GCM-encrypted at rest).

### `refresh_tokens` (spec §12 — the Active Sessions source of truth)
| Column | Purpose |
|--------|---------|
| `sid` | stable session id (carried as the access-token `sid` claim — see denylist seam) |
| `device_name`, `platform`, `user_agent` | Active-Sessions display |
| `device_fingerprint` | stable `(platform, install id, model)` hash — **not** the IP |
| `last_seen_at` | last activity |
| `revoked_at` | revoke timestamp (mirrors `is_revoked`) |
| `trusted_until` | trusted-device 2FA-skip window |

All additive columns are nullable, so existing tokens keep validating unchanged;
new logins populate them.

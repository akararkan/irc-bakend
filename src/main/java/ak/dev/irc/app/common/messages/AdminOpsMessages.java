package ak.dev.irc.app.common.messages;

/**
 * User-facing messages emitted by the admin operations-plane modules
 * ({@code app/admin/ops}, {@code logs}, {@code analytics}, {@code media},
 * {@code feed}, {@code search}, {@code notification}, {@code safety},
 * {@code chat}, {@code support}, {@code impersonation}).
 * Catalog: docs/errors/user-facing-messages.md §1 {@code admin/*} tables,
 * §2 notes/warnings, §3 notifications. Conventions: see
 * {@link ak.dev.irc.app.common.messages} package javadoc.
 */
public final class AdminOpsMessages {

    private AdminOpsMessages() {}

    // ── error codes ─────────────────────────────────────────────────────
    public static final String JOB_NOT_TRIGGERABLE     = "JOB_NOT_TRIGGERABLE";
    public static final String JOB_PAUSED              = "JOB_PAUSED";
    public static final String JOB_LOCKED              = "JOB_LOCKED";
    public static final String JOB_NOT_PAUSABLE        = "JOB_NOT_PAUSABLE";
    public static final String INVALID_STATUS          = "INVALID_STATUS";
    public static final String DLQ_NOT_PARKED          = "DLQ_NOT_PARKED";
    public static final String QUEUE_UNAVAILABLE       = "QUEUE_UNAVAILABLE";
    public static final String INVALID_PREFIX          = "INVALID_PREFIX";
    public static final String PREFIX_FORBIDDEN        = "PREFIX_FORBIDDEN";
    public static final String PREFIX_NOT_ALLOWED      = "PREFIX_NOT_ALLOWED";
    public static final String INVALID_RULE_KIND       = "INVALID_RULE_KIND";
    public static final String INVALID_SEVERITY        = "INVALID_SEVERITY";
    public static final String INVALID_DATASET         = "INVALID_DATASET";
    public static final String INVALID_METRIC          = "INVALID_METRIC";
    public static final String INVALID_COHORT          = "INVALID_COHORT";
    public static final String INVALID_RANGE           = "INVALID_RANGE";
    public static final String RANGE_TOO_LARGE         = "RANGE_TOO_LARGE";
    public static final String INVALID_THRESHOLDS      = "INVALID_THRESHOLDS";
    public static final String INVALID_DATE            = "INVALID_DATE";
    public static final String ASSET_NOT_RETRYABLE     = "ASSET_NOT_RETRYABLE";
    public static final String MEDIA_RAW_MISSING       = "MEDIA_RAW_MISSING";
    public static final String INVALID_ROLE            = "INVALID_ROLE";
    public static final String INVALID_QUOTA           = "INVALID_QUOTA";
    public static final String INVALID_TYPE            = "INVALID_TYPE";
    public static final String MISSING_USER            = "MISSING_USER";
    public static final String INVALID_ROLLOUT         = "INVALID_ROLLOUT";
    public static final String INVALID_KNOB            = "INVALID_KNOB";
    public static final String INVALID_LANGUAGE        = "INVALID_LANGUAGE";
    public static final String INVALID_SCHEDULE        = "INVALID_SCHEDULE";
    public static final String INVALID_INPUT           = "INVALID_INPUT";
    public static final String LARGE_AUDIENCE_CONFIRMATION_REQUIRED =
            "LARGE_AUDIENCE_CONFIRMATION_REQUIRED";
    public static final String ANNOUNCEMENT_NOT_SCHEDULED = "ANNOUNCEMENT_NOT_SCHEDULED";
    public static final String INVALID_RESOLUTION      = "INVALID_RESOLUTION";
    public static final String INVALID_STATE           = "INVALID_STATE";
    public static final String REPORT_STATE_INVALID    = "REPORT_STATE_INVALID";
    public static final String STRIKE_NOT_ACTIVE       = "STRIKE_NOT_ACTIVE";
    public static final String STRIKE_TARGET_AMBIGUOUS = "STRIKE_TARGET_AMBIGUOUS";
    public static final String DM_BROWSE_FORBIDDEN     = "DM_BROWSE_FORBIDDEN";
    public static final String STREAM_NOT_LIVE         = "STREAM_NOT_LIVE";
    public static final String LEGAL_HOLD_WRONG_STATE  = "LEGAL_HOLD_WRONG_STATE";
    public static final String IMPERSONATION_REASON_REQUIRED = "IMPERSONATION_REASON_REQUIRED";
    public static final String SELF_ACTION_FORBIDDEN   = "SELF_ACTION_FORBIDDEN";
    public static final String IMPERSONATION_TARGET_ADMIN = "IMPERSONATION_TARGET_ADMIN";

    // ── message text (templates render with .formatted) ─────────────────
    public static final String JOB_NOT_TRIGGERABLE_MSG =
            "Job not triggerable. Allowed: %s";
    /** {@code JOB_NOT_TRIGGERABLE} internal fallback for an unwired whitelisted key. */
    public static final String JOB_NOT_TRIGGERABLE_UNHANDLED_MSG =
            "Unhandled job.";
    public static final String JOB_PAUSED_MSG =
            "Job '%s' is paused — resume it first (POST /api/v1/admin/ops/jobs/%s/resume).";
    public static final String JOB_LOCKED_MSG =
            "A run of this job is already in progress.";
    public static final String JOB_NOT_PAUSABLE_MSG =
            "Job not pausable. Pausable: %s";
    /** {@code INVALID_STATUS} on the DLQ browse filter. */
    public static final String INVALID_STATUS_DLQ_MSG =
            "Unknown status. Allowed: PARKED, REQUEUED, DISCARDED.";
    /** {@code INVALID_STATUS} on the admin media browse filter. */
    public static final String INVALID_STATUS_MSG =
            "Unknown status. Allowed: %s";
    /** {@code INVALID_STATUS} on the call browse filter. */
    public static final String INVALID_STATUS_CALL_MSG =
            "Unknown call status. Allowed: %s";
    /** {@code INVALID_STATUS} on the stream browse filter. */
    public static final String INVALID_STATUS_STREAM_MSG =
            "Unknown status. Allowed: LIVE, ENDED.";
    /** {@code INVALID_STATUS} on the legal-hold browse filter. */
    public static final String INVALID_STATUS_HOLD_MSG =
            "Unknown status. Allowed: OPEN, APPROVED, EXECUTED, REJECTED.";
    public static final String DLQ_NOT_PARKED_MSG =
            "Only PARKED dead letters can be requeued (this one is %s).";
    public static final String QUEUE_UNAVAILABLE_MSG =
            "RabbitMQ is unavailable — cannot requeue.";
    public static final String INVALID_PREFIX_MSG =
            "Prefix too short — refusing a near-global flush.";
    public static final String PREFIX_FORBIDDEN_MSG =
            "Prefix '%s' covers auth/abuse state and can never be flushed from the admin panel.";
    public static final String PREFIX_NOT_ALLOWED_MSG =
            "Prefix not in the allowlist. Flushable: %s";
    public static final String INVALID_RULE_KIND_MSG =
            "Unknown rule kind. Allowed: %s";
    public static final String INVALID_SEVERITY_MSG =
            "Unknown severity. Allowed: INFO, MEDIUM, HIGH.";
    public static final String INVALID_DATASET_MSG =
            "Unknown dataset. Allowed: engagement, signups, content.";
    public static final String INVALID_METRIC_MSG =
            "Metric must be 1-80 chars of letters, digits, dot, dash or underscore.";
    public static final String INVALID_COHORT_MSG =
            "cohort must be YYYY-MM.";
    public static final String INVALID_RANGE_MSG =
            "'to' must not be before 'from'.";
    public static final String RANGE_TOO_LARGE_MSG =
            "Backfill is capped at 90 days per call — split larger ranges.";
    /** {@code INVALID_THRESHOLDS} when a z threshold or minVolume is out of range. */
    public static final String INVALID_THRESHOLDS_MSG =
            "Thresholds must be positive (minVolume may be 0).";
    /** {@code INVALID_THRESHOLDS} when the two z thresholds are ordered wrong. */
    public static final String INVALID_THRESHOLDS_Z_ORDER_MSG =
            "zAlert must be >= zWarn.";
    public static final String INVALID_DATE_MSG =
            "Date must be YYYY-MM-DD.";
    public static final String ASSET_NOT_RETRYABLE_MSG =
            "Only failed (or stuck PROCESSING) assets can be reprocessed.";
    public static final String MEDIA_RAW_MISSING_MSG =
            "The raw original is gone — this asset can no longer be reprocessed.";
    public static final String INVALID_ROLE_MSG =
            "Unknown role. Allowed: %s";
    public static final String INVALID_QUOTA_MSG =
            "Quota values must be positive — use enabled=false to lift a quota.";
    /** {@code INVALID_TYPE} on the admin media browse filter. */
    public static final String INVALID_TYPE_MSG =
            "Unknown type. Allowed: %s";
    /** {@code INVALID_TYPE} on the conversation browse filter. */
    public static final String INVALID_TYPE_CONVERSATION_MSG =
            "Unknown type. Allowed: GROUP, CHANNEL.";
    /** {@code INVALID_TYPE} on the call browse filter. */
    public static final String INVALID_TYPE_CALL_MSG =
            "Unknown call type. Allowed: VOICE, VIDEO.";
    public static final String MISSING_USER_MSG =
            "userId is required.";
    public static final String INVALID_ROLLOUT_MSG =
            "rolloutPercent must be 0-100.";
    /** {@code INVALID_KNOB} for the author-run diversity knob. */
    public static final String INVALID_KNOB_RUN_MSG =
            "maxAuthorRun must be 1-10.";
    /** {@code INVALID_KNOB} for the double-valued knobs. */
    public static final String INVALID_KNOB_MSG =
            "Knob values must be finite, non-negative and ≤ 1000.";
    public static final String INVALID_LANGUAGE_MSG =
            "Unknown language code. Allowed: %s";
    /** {@code INVALID_SCHEDULE} on an unparseable scheduledAt. */
    public static final String INVALID_SCHEDULE_FORMAT_MSG =
            "scheduledAt must be ISO-8601 local date-time, e.g. 2026-08-07T09:00:00.";
    /** {@code INVALID_SCHEDULE} on a scheduledAt in the past. */
    public static final String INVALID_SCHEDULE_PAST_MSG =
            "scheduledAt is in the past — omit it to send immediately.";
    /** {@code INVALID_INPUT} on a blank announcement title/body. */
    public static final String INVALID_INPUT_TITLE_BODY_MSG =
            "title and body are required.";
    /** {@code INVALID_INPUT} on a legal-hold open without a conversation. */
    public static final String INVALID_INPUT_CONVERSATION_MSG =
            "conversationId is required.";
    /** {@code INVALID_INPUT} on a legal-hold open without a case reference. */
    public static final String INVALID_INPUT_CASE_REF_MSG =
            "A case reference (ticket / court order id) is required.";
    public static final String LARGE_AUDIENCE_CONFIRMATION_REQUIRED_MSG =
            "This announcement targets %s of %s users — set confirmLargeAudience=true to proceed.";
    public static final String ANNOUNCEMENT_NOT_SCHEDULED_MSG =
            "Only SCHEDULED announcements can be cancelled (this one is %s).";
    /** {@code INVALID_RESOLUTION} when the action body has no/NONE resolution. */
    public static final String INVALID_RESOLUTION_MSG =
            "resolution is required (WARNING_ISSUED, CONTENT_REMOVED, ACCOUNT_SUSPENDED, NO_ACTION).";
    /** {@code INVALID_RESOLUTION} when the resolution does not parse. */
    public static final String INVALID_RESOLUTION_PARSE_MSG =
            "Unknown resolution. Allowed: %s";
    public static final String INVALID_STATE_MSG =
            "Unknown state. Allowed: %s";
    public static final String REPORT_STATE_INVALID_MSG =
            "Cannot %s a report in state %s.";
    public static final String STRIKE_NOT_ACTIVE_MSG =
            "Strike is not active.";
    public static final String STRIKE_TARGET_AMBIGUOUS_MSG =
            "issueStrike inline is only supported for USER-target reports; "
                    + "use POST /api/v1/admin/safety/users/{userId}/strikes for content authors.";
    public static final String DM_BROWSE_FORBIDDEN_MSG =
            "DM conversations are not browsable — aggregate stats only.";
    public static final String STREAM_NOT_LIVE_MSG =
            "Stream is not live.";
    public static final String LEGAL_HOLD_WRONG_STATE_MSG =
            "Cannot %s a %s hold — only %s holds can be %sd.";
    /** 409 with the default {@code ConflictException} code — no custom code at the throw site. */
    public static final String LEGAL_HOLD_DUAL_CONTROL_MSG =
            "Dual control: the admin who opened a legal hold cannot approve it — "
                    + "a second admin must review.";
    public static final String IMPERSONATION_REASON_REQUIRED_MSG =
            "A reason of at least 10 characters is required to impersonate.";
    public static final String SELF_ACTION_FORBIDDEN_MSG =
            "You cannot impersonate yourself.";
    public static final String IMPERSONATION_TARGET_ADMIN_MSG =
            "Admins cannot be impersonated.";

    // ── notes / warnings inside 200 bodies ──────────────────────────────
    public static final String NOTE_RABBIT_ADMIN_UNAVAILABLE =
            "RabbitAdmin unavailable";
    public static final String NOTE_R2_NOT_CONFIGURED =
            "R2 credentials not configured";
    public static final String NOTE_DLQ_PARKING =
            "Dead letters park in the dead_letters table (browse via "
                    + "GET /queues/dlq) — a nonzero dead-letter QUEUE depth means the drain "
                    + "consumer itself is down.";
    public static final String NOTE_DLQ_REQUEUE_REPARK =
            "If the consumer still can't process it, it will re-park as a NEW row.";
    public static final String NOTE_DLQ_DISCARD_KEPT =
            "Row kept for the audit trail; the retention sweep prunes it at 90 days.";
    public static final String WARN_JOB_PAUSE =
            "Scheduled runs are suppressed until resumed — pausing "
                    + "retention/GDPR sweeps defers legally-relevant deletion work.";
    public static final String NOTE_CHAT_BACKFILL_IDEMPOTENT =
            "Idempotent (writes only missing message_by_id rows); "
                    + "poll GET /api/v1/admin/ops/jobs/chat-message-backfill/runs";
    public static final String WARN_PERMIT_ALL_ON =
            "⚠ CRITICAL: permit-all is ON — every gate incl. /api/v1/admin/** is open";
    public static final String NOTE_AUDIT_STORE_SKIPPED =
            "audit store skipped — it is partitioned per user; add user:<id|@username> "
                    + "to include it (a global Cassandra scan is impossible by design).";
    public static final String NOTE_AUDIT_NEWEST_PAGE =
            "audit rows are the newest page of the user partition with filters applied "
                    + "in-memory — fetch more via /api/v1/admin/audit?userId=.";
    public static final String NOTE_CONSENT_STORE_SKIPPED =
            "consent store skipped — user-anchored; add user: to include it.";
    public static final String NOTE_OTP_AGGREGATE_ONLY =
            "Aggregate-only: codes and raw destinations are never stored or "
                    + "shown; destinationHash is HMAC'd and useful only as a correlator.";
    public static final String NOTE_POST_CREATES_COLLECTOR_SOURCED =
            "posts/stories have no historical date bucket — postCreatesPerDay is "
                    + "collector-sourced and starts at collector deployment.";
    public static final String NOTE_FUNNEL_MILESTONES_SET_ONCE =
            "Milestones are set-once from user_first_events; users who signed up "
                    + "before the funnel tracker deployed only accrue milestones going forward.";
    public static final String NOTE_FEED_PREVIEW_SHADOW =
            "Shadow-scored twice over the live candidate set — nothing was "
                    + "persisted; the two fetches may see slightly different candidates if "
                    + "content landed in between.";
    public static final String NOTE_MEDIA_ORPHAN_DEFINITION =
            "An orphan is a bucket object with no media_renditions.object_key row "
                    + "and no parseable raw/{assetId}. raw/ objects inside their 7-day retention "
                    + "are never flagged.";
    /** Asset errorMessage stamped by the raw-purge sweep on abandoned PENDING intents. */
    public static final String NOTE_ABANDONED_INTENT_PURGED =
            "Abandoned upload intent purged by admin sweep.";
    public static final String NOTE_REINDEX_ALL_SEQUENTIAL =
            "sequential; poll GET /api/v1/admin/ops/jobs/search-reindex-all/runs";
    public static final String NOTE_ZERO_RESULTS_DEGRADED_EXCLUDED =
            "Degraded searches (ES down) are excluded — a zero here means ES "
                    + "answered and genuinely had nothing.";
    public static final String NOTE_DRIFT_CASSANDRA_CANONICAL =
            "canonical store is Cassandra — no cheap full count";
    public static final String NOTE_NOTIF_STATS_LEGACY_INBOX =
            "Aggregated from the legacy Postgres inbox — the live write path is "
                    + "Cassandra (notifications_by_user), which has no cross-user aggregate; a rollup "
                    + "collector is the planned successor source.";
    public static final String NOTE_NO_NOTIFICATION_KIND =
            "no NotificationKind — legacy/relational-only type";
    public static final String NOTE_RECORDINGS_DETAIL_CAPPED =
            "Detailed rows capped at %s of %s recordings; fleetBytes still covers everything.";
    public static final String WARN_LEGAL_HOLD_EXPORT =
            "This export contains private message content released under "
                    + "legal hold %s. Handle per your evidence-retention "
                    + "policy; the hold is now EXECUTED and cannot be re-run.";

    // ── notification copy (sendSystemNotification / DeliverRequest) ─────
    public static final String NOTIF_LOG_ALERT_TITLE = "⚠ Log alert: %s";
    public static final String NOTIF_LOG_ALERT_FAILED_LOGIN_ACCOUNT_BODY =
            "%s failed logins in %sm for account %s";
    public static final String NOTIF_LOG_ALERT_FAILED_LOGIN_IP_BODY =
            "%s failed logins in %sm from IP %s";
    public static final String NOTIF_LOG_ALERT_REPORT_PILE_ON_BODY =
            "%s reports on group %s in %sm — brigading or real incident";
    public static final String NOTIF_LOG_ALERT_DLQ_BODY =
            "%s dead-lettered message(s) parked in the last %sm — inspect /admin/ops/queues/dlq";
    public static final String NOTIF_LOG_ALERT_OTP_BODY =
            "%s OTP challenges issued in %sm — possible abuse";
    public static final String NOTIF_LOG_ALERT_PURGE_SILENCE_BODY =
            "Deletions are past purge_after but no account-purge run recorded in "
                    + "%sm — scheduler or job broken";
    public static final String NOTIF_ANOMALY_TITLE = "Metric anomaly: %s";
    public static final String NOTIF_ANOMALY_FLATLINE_BODY =
            "Metric '%s' flatlined at 0 on %s (28d mean %s) — pipeline-dead detector.";
    public static final String NOTIF_ANOMALY_DEVIATION_BODY =
            "%s: metric '%s' was %s on %s vs a 28d mean of %s.";
    public static final String NOTIF_STRIKE_TITLE = "Account warning";
    public static final String NOTIF_STRIKE_BODY =
            "A moderation strike was recorded on your account: %s. "
                    + "Strikes expire automatically after 90 days.";
    public static final String NOTIF_CHANNEL_TAKEDOWN_TITLE = "Your channel was taken down";
    public static final String NOTIF_CHANNEL_RESTORED_TITLE = "Your channel was restored";
    public static final String NOTIF_CHANNEL_RESTORED_BODY =
            "Your channel \"%s\" has been restored.";
    public static final String NOTIF_STREAM_FORCE_STOP_TITLE =
            "Your live stream was ended by moderation";
    public static final String NOTIF_STREAM_KEY_ROTATED_TITLE = "Your stream key was rotated";
    public static final String NOTIF_STREAM_KEY_ROTATED_BODY =
            "For security, the stream key of \"%s\" was rotated. Your new key: %s";
    /** Email subject of the admin self-test email. */
    public static final String NOTIF_TEST_EMAIL_SUBJECT = "[TEST] %s";
}

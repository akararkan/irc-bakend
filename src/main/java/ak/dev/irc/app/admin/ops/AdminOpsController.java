package ak.dev.irc.app.admin.ops;

import ak.dev.irc.app.admin.support.AdminAuditor;
import ak.dev.irc.app.admin.support.RequiresStepUp;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.chat.enums.LiveStreamStatus;
import ak.dev.irc.app.chat.repository.LiveStreamRepository;
import ak.dev.irc.app.chat.service.LiveStreamService;
import ak.dev.irc.app.chat.service.MediaControlClient;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.messages.AdminOpsMessages;
import ak.dev.irc.app.common.util.Pages;
import ak.dev.irc.app.rabbitmq.constants.RabbitMQConstants;
import ak.dev.irc.app.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Operations console (blueprint §3.12, operations.md): composite dependency
 * health, the scheduled-jobs inventory backed by the new {@code job_runs}
 * ledger, whitelisted manual job triggers (Redis-locked so a manual run can't
 * overlap the scheduled one), queue depths, the SSE fleet summary, the
 * sanitized config registry, the MediaMTX view, and the orphaned-LIVE-stream
 * sweep that never existed (a crash used to leave LIVE rows forever).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/ops")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminOpsController {

    private final OpsHealthService opsHealthService;
    private final JobRunRepository jobRunRepository;
    private final JobRunRecorder jobRunRecorder;
    private final StringRedisTemplate redis;
    private final ObjectProvider<RabbitAdmin> rabbitAdmin;
    private final ObjectProvider<org.springframework.amqp.rabbit.core.RabbitTemplate> rabbitTemplate;
    private final DeadLetterRepository deadLetterRepository;
    private final JobPauseRegistry jobPauseRegistry;
    private final ak.dev.irc.app.chat.cassandra.ChatMessageByIdBackfill chatMessageByIdBackfill;
    private final ak.dev.irc.app.config.EnumCheckConstraintReconciler enumCheckConstraintReconciler;
    private final org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor taskExecutor;
    private final MediaControlClient mediaControlClient;
    private final LiveStreamRepository liveStreamRepository;
    private final LiveStreamService liveStreamService;
    private final AdminAuditor adminAuditor;

    // manual-trigger targets
    private final ak.dev.irc.app.common.tag.job.TrendingTagJob trendingTagJob;
    private final ak.dev.irc.app.common.notification.job.TrendingNotificationJob trendingNotificationJob;
    private final ak.dev.irc.app.settings.data.service.AccountLifecycleService accountLifecycleService;
    private final ak.dev.irc.app.user.service.NotificationCleanupJob notificationCleanupJob;
    private final ak.dev.irc.app.research.job.ScheduledPublishJob scheduledPublishJob;
    private final ak.dev.irc.app.chat.service.CallService callService;
    private final ak.dev.irc.app.chat.service.ScheduledMessageService scheduledMessageService;

    // SSE fleet
    private final ak.dev.irc.app.audit.realtime.AuditRealtimeService auditRealtime;
    private final ak.dev.irc.app.chat.realtime.ChatSseService chatSse;
    private final ak.dev.irc.app.user.realtime.NotificationSseService notificationSse;
    private final ak.dev.irc.app.activity.realtime.UserActivityRealtimeService activityRealtime;
    private final ak.dev.irc.app.qna.realtime.QnaRealtimeService qnaRealtime;
    private final ak.dev.irc.app.research.realtime.ResearchRealtimeService researchRealtime;
    private final ak.dev.irc.app.post.realtime.PostRealtimeService postRealtime;
    private final ak.dev.irc.app.post.realtime.StoryRealtimeService storyRealtime;
    private final ak.dev.irc.app.post.realtime.StoryTrayRealtimeService storyTrayRealtime;

    // sanitized config registry
    @Value("${app.security.permit-all:false}") private boolean permitAll;
    @Value("${app.security.step-up.ttl-seconds:300}") private long stepUpTtlSeconds;
    @Value("${media.processing.enabled:false}") private boolean transcodeEnabled;
    @Value("${app.streaming.control-api-base:http://localhost:9997}") private String streamControlBase;
    @Value("${app.streaming.webrtc-base:http://localhost:8889}") private String streamWebrtcBase;
    @Value("${app.streaming.playback-base:http://localhost:8888}") private String streamPlaybackBase;
    @Value("${app.streaming.ingest-base:rtmp://localhost:1935}") private String streamIngestBase;
    @Value("${app.streaming.recordings-dir:./recordings}") private String recordingsDir;
    @Value("${irc.email.provider:smtp}") private String emailProvider;
    @Value("${irc.email.enabled:true}") private boolean emailEnabled;
    @Value("${spring.cassandra.keyspace-name:irc_keyspace}") private String cassandraKeyspace;
    @Value("${spring.data.redis.host:localhost}") private String redisHost;
    @Value("${app.tags.trending-refresh-ms:600000}") private long trendingRefreshMs;
    @Value("${irc.base-url:http://localhost:5173}") private String frontendBaseUrl;

    /** Registry key → (schedule, description) for the jobs view. */
    private static final Map<String, String[]> JOB_REGISTRY = new LinkedHashMap<>();

    /** jobKey → runnable, for the whitelisted manual triggers. */
    private static final List<String> TRIGGERABLE = List.of(
            "trending-rebuild", "trending-digest", "account-purge", "notification-cleanup",
            "research-scheduled-publish", "calls-sweep-missed", "chat-scheduled-messages");

    static {
        JOB_REGISTRY.put("research-scheduled-publish", new String[]{
                "fixedDelay ${app.research.scheduled-publish-ms:60000}",
                "Auto-publish DRAFT research whose scheduledPublishAt is due"});
        JOB_REGISTRY.put("account-purge", new String[]{
                "cron 0 30 3 * * *",
                "Anonymize + purge accounts past the 30-day grace (GDPR cascade)"});
        JOB_REGISTRY.put("notification-cleanup", new String[]{
                "cron 0 15 3 * * *",
                "Delete READ notifications older than 90 days (+ job_runs ledger prune)"});
        JOB_REGISTRY.put("trending-digest", new String[]{
                "cron ${irc.trending.notifications.cron:0 0 9 * * *} UTC",
                "Daily TRENDING_DIGEST fan-out from QUESTION+RESEARCH trending tags"});
        JOB_REGISTRY.put("trending-rebuild", new String[]{
                "fixedDelay ${app.tags.trending-refresh-ms:600000}",
                "Rebuild trending_tags top-100 per scope (honors PIN/BAN overrides)"});
        JOB_REGISTRY.put("chat-scheduled-messages", new String[]{
                "fixedDelay 15s", "Send due scheduled chat messages"});
        JOB_REGISTRY.put("calls-sweep-missed", new String[]{
                "fixedDelay 20s", "Flip RINGING calls past ring timeout to MISSED"});
        JOB_REGISTRY.put("search-reindex-all", new String[]{
                "manual only", "Sequential reindex of the 7 hook-equipped ES indices"});
        JOB_REGISTRY.put("sse-heartbeats", new String[]{
                "9 sweeps @ 15-25s", "SSE keepalives — deliberately NOT ledgered (noise)"});
    }

    // ── health / jobs / queues ──────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(opsHealthService.health());
    }

    @GetMapping("/jobs")
    public ResponseEntity<List<Map<String, Object>>> jobs() {
        // One DISTINCT ON scan instead of a point query per registry entry.
        Map<String, JobRun> latest = new LinkedHashMap<>();
        for (JobRun r : jobRunRepository.latestRunPerJob()) latest.put(r.getJobName(), r);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, String[]> e : JOB_REGISTRY.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("job", e.getKey());
            row.put("schedule", e.getValue()[0]);
            row.put("description", e.getValue()[1]);
            row.put("triggerable", TRIGGERABLE.contains(e.getKey()));
            JobRun run = latest.get(e.getKey());
            if (run == null) {
                row.put("lastRun", null);
            } else {
                row.put("lastRun", run.getStartedAt());
                row.put("lastOutcome", run.getOutcome());
                row.put("lastDurationMs", run.getDurationMs());
                row.put("lastItemsProcessed", run.getItemsProcessed());
            }
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/jobs/{jobKey}/runs")
    public ResponseEntity<?> jobRuns(@PathVariable String jobKey,
                                     @PageableDefault(size = 25) Pageable pageable) {
        return ResponseEntity.ok(jobRunRepository.runsFor(jobKey,
                PageRequest.of(Math.max(0, pageable.getPageNumber()),
                        Pages.clamp(pageable.getPageSize()))));
    }

    /** Manual trigger — whitelisted jobs only, Redis-locked against overlap. */
    @PostMapping("/jobs/{jobKey}/run")
    @RequiresStepUp
    public ResponseEntity<Map<String, Object>> runJob(@PathVariable String jobKey) {
        if (!TRIGGERABLE.contains(jobKey)) {
            throw new BadRequestException(
                    AdminOpsMessages.JOB_NOT_TRIGGERABLE_MSG.formatted(TRIGGERABLE),
                    AdminOpsMessages.JOB_NOT_TRIGGERABLE);
        }
        if (jobPauseRegistry.isPaused(jobKey)) {
            throw new BadRequestException(
                    AdminOpsMessages.JOB_PAUSED_MSG.formatted(jobKey, jobKey),
                    AdminOpsMessages.JOB_PAUSED);
        }
        String lockKey = "ops:job-lock:" + jobKey;
        Boolean locked = redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofMinutes(10));
        if (!Boolean.TRUE.equals(locked)) {
            throw new BadRequestException(AdminOpsMessages.JOB_LOCKED_MSG,
                    AdminOpsMessages.JOB_LOCKED);
        }
        UUID adminId = SecurityUtils.getCurrentUserId().orElse(null);
        adminAuditor.record(AuditOperation.OTHER, "Job", null, "ADMIN_JOB_RUN", jobKey);
        try {
            switch (jobKey) {
                // These three self-record their scheduled runs — the manual
                // path just invokes the same body.
                case "trending-digest" -> trendingNotificationJob.runNow();
                case "account-purge" -> accountLifecycleService.purgeExpired();
                case "notification-cleanup" -> notificationCleanupJob.purgeOldRead();
                case "trending-rebuild" -> jobRunRecorder.record("trending-rebuild", adminId, () -> {
                    trendingTagJob.rebuildTrending();
                    return JobRunRecorder.JobStats.NONE;
                });
                case "research-scheduled-publish" ->
                        jobRunRecorder.record("research-scheduled-publish", adminId, () -> {
                            scheduledPublishJob.publishDueResearch();
                            return JobRunRecorder.JobStats.NONE;
                        });
                case "calls-sweep-missed" -> jobRunRecorder.record("calls-sweep-missed", adminId, () -> {
                    callService.sweepMissed();
                    return JobRunRecorder.JobStats.NONE;
                });
                case "chat-scheduled-messages" ->
                        jobRunRecorder.record("chat-scheduled-messages", adminId, () -> {
                            scheduledMessageService.fireDue();
                            return JobRunRecorder.JobStats.NONE;
                        });
                default -> throw new BadRequestException(
                        AdminOpsMessages.JOB_NOT_TRIGGERABLE_UNHANDLED_MSG,
                        AdminOpsMessages.JOB_NOT_TRIGGERABLE);
            }
        } finally {
            try {
                redis.delete(lockKey);
            } catch (Exception ignored) {
            }
        }
        return ResponseEntity.accepted().body(Map.of("job", jobKey, "triggered", true));
    }

    @GetMapping("/queues")
    public ResponseEntity<Map<String, Object>> queues() {
        RabbitAdmin admin = rabbitAdmin.getIfAvailable();
        Map<String, Object> out = new LinkedHashMap<>();
        if (admin == null) {
            out.put("note", AdminOpsMessages.NOTE_RABBIT_ADMIN_UNAVAILABLE);
            return ResponseEntity.ok(out);
        }
        for (String queue : List.of(RabbitMQConstants.NOTIFICATION_QUEUE,
                RabbitMQConstants.ANALYTICS_QUEUE, RabbitMQConstants.DEAD_LETTER_QUEUE,
                RabbitMQConstants.MEDIA_PROCESS_QUEUE, RabbitMQConstants.MEDIA_DELETE_QUEUE)) {
            try {
                var props = admin.getQueueProperties(queue);
                if (props == null) {
                    out.put(queue, Map.of("declared", false));
                } else {
                    out.put(queue, Map.of(
                            "messages", props.get("QUEUE_MESSAGE_COUNT"),
                            "consumers", props.get("QUEUE_CONSUMER_COUNT")));
                }
            } catch (Exception e) {
                out.put(queue, Map.of("error", String.valueOf(e.getMessage())));
            }
        }
        out.put("note", AdminOpsMessages.NOTE_DLQ_PARKING);
        out.put("dlqParked", deadLetterRepository.countByStatusExact(DeadLetter.Status.PARKED));
        return ResponseEntity.ok(out);
    }

    // ── SSE / config / media plane ──────────────────────────────────────

    @GetMapping("/sse")
    public ResponseEntity<Map<String, Object>> sse() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("auditAdmins", auditRealtime.adminCount());
        out.put("chatUsers", chatSse.connectedUserCount());
        out.put("notificationUsers", notificationSse.connectedUserCount());
        out.put("activityUsers", activityRealtime.userCount());
        out.put("qnaTopics", qnaRealtime.topicCount());
        out.put("researchTopics", researchRealtime.topicCount());
        out.put("postTopics", postRealtime.topicCount());
        out.put("storyTopics", storyRealtime.topicCount());
        out.put("storyTrayViewers", storyTrayRealtime.viewerCount());
        return ResponseEntity.ok(out);
    }

    // ── DLQ parking lot (operations.md §5.2) ────────────────────────────

    @GetMapping("/queues/dlq")
    public ResponseEntity<?> dlqBrowse(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String routingKey,
            @PageableDefault(size = 25) Pageable pageable) {
        DeadLetter.Status parsed = null;
        if (status != null && !status.isBlank()) {
            try {
                parsed = DeadLetter.Status.valueOf(status.trim().toUpperCase());
            } catch (Exception e) {
                throw new BadRequestException(AdminOpsMessages.INVALID_STATUS_DLQ_MSG,
                        AdminOpsMessages.INVALID_STATUS);
            }
        }
        return ResponseEntity.ok(deadLetterRepository.browse(parsed,
                (routingKey == null || routingKey.isBlank()) ? null : routingKey.trim(),
                PageRequest.of(Math.max(0, pageable.getPageNumber()),
                        Pages.clamp(pageable.getPageSize()))));
    }

    /** Republish a parked dead letter to its ORIGINAL exchange + routing key. */
    @PostMapping("/queues/dlq/{id}/requeue")
    @RequiresStepUp
    public ResponseEntity<Map<String, Object>> dlqRequeue(@PathVariable UUID id) {
        DeadLetter dl = deadLetterRepository.findById(id)
                .orElseThrow(() -> new ak.dev.irc.app.common.exception.ResourceNotFoundException(
                        "DeadLetter", "id", id));
        if (dl.getStatus() != DeadLetter.Status.PARKED) {
            throw new BadRequestException(
                    AdminOpsMessages.DLQ_NOT_PARKED_MSG.formatted(dl.getStatus()),
                    AdminOpsMessages.DLQ_NOT_PARKED);
        }
        var template = rabbitTemplate.getIfAvailable();
        if (template == null) {
            throw new BadRequestException(AdminOpsMessages.QUEUE_UNAVAILABLE_MSG,
                    AdminOpsMessages.QUEUE_UNAVAILABLE);
        }
        byte[] payload = java.util.Base64.getDecoder().decode(
                dl.getPayloadB64() == null ? "" : dl.getPayloadB64());
        var props = new org.springframework.amqp.core.MessageProperties();
        if (dl.getTypeId() != null) props.setHeader("__TypeId__", dl.getTypeId());
        props.setHeader("x-dlq-replay-of", dl.getId().toString());
        template.send(
                dl.getOriginalExchange() == null || dl.getOriginalExchange().isBlank()
                        ? RabbitMQConstants.IRC_EXCHANGE : dl.getOriginalExchange(),
                dl.getRoutingKey() == null ? "" : dl.getRoutingKey(),
                new org.springframework.amqp.core.Message(payload, props));

        dl.setStatus(DeadLetter.Status.REQUEUED);
        dl.setResolvedAt(java.time.LocalDateTime.now());
        dl.setResolvedBy(SecurityUtils.getCurrentUserId().orElse(null));
        deadLetterRepository.save(dl);
        adminAuditor.record(AuditOperation.UPDATE, "DeadLetter", id,
                "ADMIN_DLQ_REQUEUE", dl.getRoutingKey());
        return ResponseEntity.ok(Map.of("id", id, "requeuedTo",
                dl.getOriginalExchange() + "/" + dl.getRoutingKey(),
                "note", AdminOpsMessages.NOTE_DLQ_REQUEUE_REPARK));
    }

    @DeleteMapping("/queues/dlq/{id}")
    @RequiresStepUp
    public ResponseEntity<Map<String, Object>> dlqDiscard(@PathVariable UUID id) {
        DeadLetter dl = deadLetterRepository.findById(id)
                .orElseThrow(() -> new ak.dev.irc.app.common.exception.ResourceNotFoundException(
                        "DeadLetter", "id", id));
        dl.setStatus(DeadLetter.Status.DISCARDED);
        dl.setResolvedAt(java.time.LocalDateTime.now());
        dl.setResolvedBy(SecurityUtils.getCurrentUserId().orElse(null));
        deadLetterRepository.save(dl);
        adminAuditor.record(AuditOperation.DELETE, "DeadLetter", id,
                "ADMIN_DLQ_DISCARD", dl.getRoutingKey());
        return ResponseEntity.ok(Map.of("id", id, "status", "DISCARDED",
                "note", AdminOpsMessages.NOTE_DLQ_DISCARD_KEPT));
    }

    // ── job pause / resume ──────────────────────────────────────────────

    @GetMapping("/jobs/paused")
    public ResponseEntity<Map<String, Boolean>> pausedJobs() {
        return ResponseEntity.ok(jobPauseRegistry.snapshot());
    }

    @PostMapping("/jobs/{jobKey}/pause")
    @RequiresStepUp
    public ResponseEntity<Map<String, Object>> pauseJob(@PathVariable String jobKey) {
        if (!JobPauseRegistry.PAUSABLE.contains(jobKey)) {
            throw new BadRequestException(
                    AdminOpsMessages.JOB_NOT_PAUSABLE_MSG.formatted(JobPauseRegistry.PAUSABLE),
                    AdminOpsMessages.JOB_NOT_PAUSABLE);
        }
        jobPauseRegistry.pause(jobKey);
        adminAuditor.record(AuditOperation.UPDATE, "Job", null, "ADMIN_JOB_PAUSE", jobKey);
        return ResponseEntity.ok(Map.of("job", jobKey, "paused", true,
                "warning", AdminOpsMessages.WARN_JOB_PAUSE));
    }

    @PostMapping("/jobs/{jobKey}/resume")
    @RequiresStepUp
    public ResponseEntity<Map<String, Object>> resumeJob(@PathVariable String jobKey) {
        if (!JobPauseRegistry.PAUSABLE.contains(jobKey)) {
            throw new BadRequestException(
                    AdminOpsMessages.JOB_NOT_PAUSABLE_MSG.formatted(JobPauseRegistry.PAUSABLE),
                    AdminOpsMessages.JOB_NOT_PAUSABLE);
        }
        jobPauseRegistry.resume(jobKey);
        adminAuditor.record(AuditOperation.UPDATE, "Job", null, "ADMIN_JOB_RESUME", jobKey);
        return ResponseEntity.ok(Map.of("job", jobKey, "paused", false));
    }

    // ── Redis panel ─────────────────────────────────────────────────────

    /** INFO summary — memory, clients, keyspace, hit ratio. Read-only. */
    @GetMapping("/redis")
    public ResponseEntity<Map<String, Object>> redisInfo() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            java.util.Properties info = redis.execute(
                    (org.springframework.data.redis.core.RedisCallback<java.util.Properties>)
                            conn -> conn.serverCommands().info());
            if (info == null) {
                out.put("reachable", false);
                return ResponseEntity.ok(out);
            }
            out.put("reachable", true);
            for (String key : List.of("redis_version", "uptime_in_days", "connected_clients",
                    "used_memory_human", "used_memory_peak_human", "maxmemory_human",
                    "maxmemory_policy", "total_commands_processed", "instantaneous_ops_per_sec",
                    "keyspace_hits", "keyspace_misses", "evicted_keys", "expired_keys")) {
                Object v = info.get(key);
                if (v != null) out.put(key, v);
            }
            long hits = parseLong(info.get("keyspace_hits"));
            long misses = parseLong(info.get("keyspace_misses"));
            out.put("hitRatio", hits + misses == 0 ? null
                    : Math.round(hits * 10000.0 / (hits + misses)) / 100.0);
            for (Object k : info.keySet()) {
                String ks = String.valueOf(k);
                if (ks.startsWith("db")) out.put(ks, info.get(k));
            }
        } catch (Exception e) {
            out.put("reachable", false);
            out.put("error", e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    /** Cache-key prefixes an admin may flush. Auth/abuse state is NEVER flushable:
     *  sid denylist, step-up grants, OTP state and rate-limit counters would all
     *  turn a "clear cache" into a security bypass. */
    private static final List<String> FLUSHABLE_PREFIXES = List.of(
            "irc:search:top:", "irc:search:zero:", "user-profile", "settings:",
            "chat:presence:", "email-ctx:", "trending:");

    private static final List<String> NEVER_FLUSH = List.of(
            "sid:", "stepup:", "otp:", "rl:", "ops:job-");

    @DeleteMapping("/redis/keys")
    @RequiresStepUp
    public ResponseEntity<Map<String, Object>> flushRedisPrefix(@RequestParam String prefix) {
        String p = prefix.trim();
        if (p.length() < 3) {
            throw new BadRequestException(AdminOpsMessages.INVALID_PREFIX_MSG,
                    AdminOpsMessages.INVALID_PREFIX);
        }
        for (String banned : NEVER_FLUSH) {
            if (p.startsWith(banned) || banned.startsWith(p)) {
                throw new BadRequestException(
                        AdminOpsMessages.PREFIX_FORBIDDEN_MSG.formatted(p),
                        AdminOpsMessages.PREFIX_FORBIDDEN);
            }
        }
        boolean allowed = FLUSHABLE_PREFIXES.stream().anyMatch(p::startsWith);
        if (!allowed) {
            throw new BadRequestException(
                    AdminOpsMessages.PREFIX_NOT_ALLOWED_MSG.formatted(FLUSHABLE_PREFIXES),
                    AdminOpsMessages.PREFIX_NOT_ALLOWED);
        }
        long deleted = 0;
        try (var cursor = redis.scan(org.springframework.data.redis.core.ScanOptions.scanOptions()
                .match(p + "*").count(500).build())) {
            List<String> batch = new ArrayList<>(500);
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() == 500) {
                    deleted += nz(redis.delete(batch));
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) deleted += nz(redis.delete(batch));
        }
        adminAuditor.record(AuditOperation.DELETE, "RedisKeys", null,
                "ADMIN_REDIS_FLUSH", p + "* (" + deleted + " keys)");
        return ResponseEntity.ok(Map.of("prefix", p, "deleted", deleted));
    }

    // ── chat-messages ES backfill + schema reconciler report ────────────

    /**
     * Re-runs the {@code message_by_id} backfill: clears the completion
     * marker and executes the same idempotent, bounded walk the boot path
     * uses (only-missing rows are written — safe on live traffic).
     */
    @PostMapping("/es/chat-messages/backfill")
    @RequiresStepUp
    public ResponseEntity<Map<String, Object>> chatMessagesBackfill() {
        UUID adminId = SecurityUtils.getCurrentUserId().orElse(null);
        adminAuditor.record(AuditOperation.UPDATE, "ChatMessages", null,
                "ADMIN_CHAT_BACKFILL_RUN");
        var run = jobRunRecorder.start("chat-message-backfill", adminId);
        taskExecutor.execute(() -> {
            try {
                chatMessageByIdBackfill.clearMarker();
                chatMessageByIdBackfill.backfill();
                jobRunRecorder.finish(run, JobRunRecorder.JobStats.NONE, null);
            } catch (Exception e) {
                jobRunRecorder.finish(run, JobRunRecorder.JobStats.NONE, e);
            }
        });
        return ResponseEntity.accepted().body(Map.of(
                "jobId", run.getId(),
                "note", AdminOpsMessages.NOTE_CHAT_BACKFILL_IDEMPOTENT));
    }

    /** Startup outcome of the enum-CHECK constraint reconciler. */
    @GetMapping("/config/reconciler")
    public ResponseEntity<Map<String, Object>> reconcilerReport() {
        return ResponseEntity.ok(enumCheckConstraintReconciler.report());
    }

    private static long parseLong(Object o) {
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return 0;
        }
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }

    /** Sanitized env/flag registry — secrets never rendered, by construction. */
    @GetMapping("/config")
    @RequiresStepUp
    public ResponseEntity<Map<String, Object>> config() {
        adminAuditor.record(AuditOperation.READ, "Config", null, "ADMIN_OPS_CONFIG_VIEW");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("app.security.permit-all", permitAll);
        out.put("permitAllWarning", permitAll
                ? AdminOpsMessages.WARN_PERMIT_ALL_ON
                : null);
        out.put("app.security.step-up.ttl-seconds", stepUpTtlSeconds);
        out.put("media.processing.enabled (MEDIA_TRANSCODE_ENABLED)", transcodeEnabled);
        out.put("app.streaming.control-api-base", streamControlBase);
        out.put("app.streaming.webrtc-base", streamWebrtcBase);
        out.put("app.streaming.playback-base", streamPlaybackBase);
        out.put("app.streaming.ingest-base", streamIngestBase);
        out.put("app.streaming.recordings-dir", recordingsDir);
        out.put("irc.email.provider", emailProvider);
        out.put("irc.email.enabled", emailEnabled);
        out.put("spring.cassandra.keyspace-name", cassandraKeyspace);
        out.put("spring.data.redis.host", redisHost);
        out.put("app.tags.trending-refresh-ms", trendingRefreshMs);
        out.put("irc.base-url", frontendBaseUrl);
        return ResponseEntity.ok(out);
    }

    @GetMapping("/media-plane")
    public ResponseEntity<Map<String, Object>> mediaPlane() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("controlApi", streamControlBase);
        out.put("reachable", mediaControlClient.ping());
        out.put("paths", raw(mediaControlClient.listPaths()));
        out.put("webrtcSessions", raw(mediaControlClient.listWebrtcSessions()));
        out.put("rtmpConns", raw(mediaControlClient.listRtmpConns()));
        return ResponseEntity.ok(out);
    }

    /**
     * Ends LIVE rows orphaned by a crash: no MediaMTX publisher session and
     * past the grace window, or LIVE beyond the hard age cap.
     */
    @PostMapping("/streams/sweep-orphans")
    @RequiresStepUp
    public ResponseEntity<Map<String, Object>> sweepOrphans(
            @RequestParam(defaultValue = "30") int graceMinutes,
            @RequestParam(defaultValue = "12") int maxAgeHours,
            @RequestParam(defaultValue = "false") boolean dryRun) {
        String sessions = mediaControlClient.listWebrtcSessions();
        String rtmp = mediaControlClient.listRtmpConns();
        Instant graceCutoff = Instant.now().minus(Duration.ofMinutes(Math.max(1, graceMinutes)));
        Instant hardCutoff = Instant.now().minus(Duration.ofHours(Math.max(1, maxAgeHours)));

        List<Map<String, Object>> swept = new ArrayList<>();
        for (var stream : liveStreamRepository.findByStatusAndStartedAtBefore(
                LiveStreamStatus.LIVE, graceCutoff)) {
            boolean hasPublisher = containsPath(sessions, stream.getId())
                    || containsPath(rtmp, stream.getId());
            boolean tooOld = stream.getStartedAt() != null
                    && stream.getStartedAt().isBefore(hardCutoff);
            if (hasPublisher && !tooOld) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("streamId", stream.getId());
            row.put("hostId", stream.getHostId());
            row.put("startedAt", stream.getStartedAt());
            row.put("reason", tooOld ? "LIVE beyond " + maxAgeHours + "h"
                    : "no publisher session past grace");
            if (!dryRun) {
                try {
                    liveStreamService.end(stream.getId(), stream.getHostId());
                    row.put("ended", true);
                } catch (Exception e) {
                    row.put("ended", false);
                    row.put("error", e.getMessage());
                }
            }
            swept.add(row);
        }
        if (!dryRun && !swept.isEmpty()) {
            adminAuditor.record(AuditOperation.UPDATE, "LiveStream", null,
                    "ADMIN_STREAM_SWEEP", "ended=" + swept.size());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dryRun", dryRun);
        body.put("mediamtxReachable", sessions != null || rtmp != null);
        body.put("swept", swept);
        return ResponseEntity.ok(body);
    }

    private static boolean containsPath(String json, UUID streamId) {
        return json != null && json.contains(streamId.toString());
    }

    private static Object raw(String json) {
        return json == null ? "unreachable" : json;
    }
}

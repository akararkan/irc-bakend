package ak.dev.irc.app.admin.search;

import ak.dev.irc.app.admin.ops.JobRunRecorder;
import ak.dev.irc.app.admin.support.AdminAuditor;
import ak.dev.irc.app.admin.support.RequiresStepUp;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Search operations beyond the 7 existing reindex hooks (blueprint §3.9):
 * per-index health for all 8 {@code irc-*} indices, and the async
 * reindex-all orchestration (202 + job id — the individual hooks stay
 * synchronous by design until migrated).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/search")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminSearchOpsController {

    private final ElasticsearchOperations esOps;
    private final ThreadPoolTaskExecutor taskExecutor;
    private final JobRunRecorder jobRunRecorder;
    private final AdminAuditor adminAuditor;
    private final ak.dev.irc.app.research.search.service.ResearchSearchService researchSearchService;
    private final ak.dev.irc.app.post.search.service.PostSearchService postSearchService;
    private final ak.dev.irc.app.qna.search.service.QnaSearchService qnaSearchService;
    private final ak.dev.irc.app.qna.search.service.AnswerSearchService answerSearchService;
    private final ak.dev.irc.app.user.search.service.UserSearchService userSearchService;
    private final ak.dev.irc.app.chat.search.service.ChannelSearchService channelSearchService;
    private final ak.dev.irc.app.post.search.service.SoundSearchService soundSearchService;
    private final ak.dev.irc.app.common.search.service.SearchQueryLogService queryLogService;
    private final ak.dev.irc.app.user.repository.UserRepository userRepository;
    private final ak.dev.irc.app.research.repository.ResearchRepository researchRepository;
    private final ak.dev.irc.app.qna.repository.QuestionRepository questionRepository;
    private final ak.dev.irc.app.chat.repository.ConversationRepository conversationRepository;

    private static final Map<String, Class<?>> INDICES = new LinkedHashMap<>();

    static {
        INDICES.put("irc-posts", ak.dev.irc.app.post.search.document.PostSearchDocument.class);
        INDICES.put("irc-qna", ak.dev.irc.app.qna.search.document.QnaSearchDocument.class);
        INDICES.put("irc-answers", ak.dev.irc.app.qna.search.document.AnswerSearchDocument.class);
        INDICES.put("irc-research", ak.dev.irc.app.research.search.document.ResearchSearchDocument.class);
        INDICES.put("irc-users", ak.dev.irc.app.user.search.document.UserSearchDocument.class);
        INDICES.put("irc-channels", ak.dev.irc.app.chat.search.document.ChannelSearchDocument.class);
        INDICES.put("irc-sounds", ak.dev.irc.app.post.search.document.SoundSearchDocument.class);
        INDICES.put("irc-chat-messages", ak.dev.irc.app.chat.search.document.ChatMessageDocument.class);
    }

    /** Per-index existence + doc count for the 8 platform indices. */
    @GetMapping("/indices")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")   // observability read (§6 matrix)
    public ResponseEntity<List<Map<String, Object>>> indices() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Class<?>> e : INDICES.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", e.getKey());
            try {
                boolean exists = esOps.indexOps(e.getValue()).exists();
                row.put("exists", exists);
                if (exists) {
                    row.put("docCount", esOps.count(
                            NativeQuery.builder()
                                    .withQuery(q -> q.matchAll(m -> m))
                                    .withMaxResults(0)
                                    .build(),
                            e.getValue()));
                }
            } catch (Exception ex) {
                row.put("error", ex.getMessage());
            }
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Sequential reindex of all 7 hook-equipped indices, async (202 + job id).
     * chat-messages deliberately has no reindex hook — its canonical store has
     * no efficient full scan and it re-writes on every mutation.
     */
    @PostMapping("/reindex-all")
    @RequiresStepUp
    public ResponseEntity<Map<String, Object>> reindexAll() {
        UUID adminId = SecurityUtils.getCurrentUserId().orElse(null);
        var run = jobRunRecorder.start("search-reindex-all", adminId);
        adminAuditor.record(AuditOperation.UPDATE, "Search", null, "ADMIN_SEARCH_REINDEX_ALL");
        taskExecutor.execute(() -> {
            int ok = 0, failed = 0;
            failed += reindexStep("research", () -> researchSearchService.reindexAllPublished(true)) ? 0 : 1;
            ok = countOk(ok, failed, 1);
            failed += reindexStep("posts", () -> postSearchService.reindexAll(true)) ? 0 : 1;
            ok = countOk(ok, failed, 2);
            failed += reindexStep("questions", () -> qnaSearchService.reindexAll(true)) ? 0 : 1;
            ok = countOk(ok, failed, 3);
            failed += reindexStep("answers", () -> answerSearchService.reindexAll(true)) ? 0 : 1;
            ok = countOk(ok, failed, 4);
            failed += reindexStep("users", () -> userSearchService.reindexAll(true)) ? 0 : 1;
            ok = countOk(ok, failed, 5);
            failed += reindexStep("channels", () -> channelSearchService.reindexAll(true)) ? 0 : 1;
            ok = countOk(ok, failed, 6);
            failed += reindexStep("sounds", () -> soundSearchService.reindexAll(true)) ? 0 : 1;
            ok = countOk(ok, failed, 7);
            jobRunRecorder.finish(run, new JobRunRecorder.JobStats(ok, failed), null);
        });
        return ResponseEntity.accepted().body(Map.of(
                "jobId", run.getId(),
                "jobName", "search-reindex-all",
                "note", "sequential; poll GET /api/v1/admin/ops/jobs/search-reindex-all/runs"));
    }

    // ── query analytics (anonymous collector, search-ops.md §7) ─────────

    @GetMapping("/analytics/top-queries")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
    public ResponseEntity<Map<String, Object>> topQueries(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "ALL") String scope,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "7") int days,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int limit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scope", scope);
        body.put("days", Math.max(1, Math.min(days, 7)));
        body.put("queries", queryLogService.topQueries(scope, days, limit, false));
        body.put("source", "Redis irc:search:top:* — anonymous counts, 8-day retention; "
                + "no user ids are ever recorded (§12).");
        return ResponseEntity.ok(body);
    }

    /** Searches that returned zero hits — the content-gap / taxonomy signal. */
    @GetMapping("/analytics/zero-results")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
    public ResponseEntity<Map<String, Object>> zeroResults(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "ALL") String scope,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "7") int days,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int limit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scope", scope);
        body.put("days", Math.max(1, Math.min(days, 7)));
        body.put("queries", queryLogService.topQueries(scope, days, limit, true));
        body.put("note", "Degraded searches (ES down) are excluded — a zero here means ES "
                + "answered and genuinely had nothing.");
        return ResponseEntity.ok(body);
    }

    // ── health with canonical drift ─────────────────────────────────────

    /**
     * ES reachability + per-index doc counts, with drift against the
     * canonical Postgres store where one exists. Cassandra-canonical indices
     * (posts, answers, sounds, chat-messages) have no cheap full count —
     * drift is reported as unknown rather than pretending.
     */
    @GetMapping("/health")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean reachable = true;
        for (Map.Entry<String, Class<?>> e : INDICES.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", e.getKey());
            Long esCount = null;
            try {
                if (esOps.indexOps(e.getValue()).exists()) {
                    esCount = esOps.count(
                            NativeQuery.builder()
                                    .withQuery(q -> q.matchAll(m -> m))
                                    .withMaxResults(0)
                                    .build(),
                            e.getValue());
                    row.put("docCount", esCount);
                } else {
                    row.put("docCount", 0L);
                    esCount = 0L;
                }
            } catch (Exception ex) {
                reachable = false;
                row.put("error", ex.getMessage());
            }
            Long canonical = canonicalCount(e.getKey());
            if (canonical != null) {
                row.put("canonicalCount", canonical);
                if (esCount != null) row.put("drift", esCount - canonical);
            } else {
                row.put("canonicalCount", null);
                row.put("driftNote", "canonical store is Cassandra — no cheap full count");
            }
            rows.add(row);
        }
        body.put("reachable", reachable);
        body.put("indices", rows);
        body.put("driftMeaning", "drift = esDocs - canonicalRows. Positive: stale docs "
                + "(deleted rows still indexed). Negative: missing docs (index behind). "
                + "Small transient drift is normal; persistent drift → run the reindex hook.");
        return ResponseEntity.ok(body);
    }

    private Long canonicalCount(String index) {
        try {
            return switch (index) {
                case "irc-users" -> userRepository.countByDeletedAtIsNull();
                case "irc-research" -> researchRepository.countByStatusAndDeletedAtIsNull(
                        ak.dev.irc.app.research.enums.ResearchStatus.PUBLISHED);
                case "irc-qna" -> questionRepository.countByDeletedAtIsNull();
                case "irc-channels" -> conversationRepository.countByTypeAndDeletedAtIsNull(
                        ak.dev.irc.app.chat.enums.ConversationType.CHANNEL);
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    private boolean reindexStep(String name, Runnable step) {
        try {
            step.run();
            return true;
        } catch (Exception e) {
            log.warn("[REINDEX-ALL] {} failed: {}", name, e.getMessage());
            return false;
        }
    }

    private static int countOk(int ok, int failed, int total) {
        return total - failed;
    }
}

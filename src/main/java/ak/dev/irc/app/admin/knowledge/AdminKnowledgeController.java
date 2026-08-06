package ak.dev.irc.app.admin.knowledge;

import ak.dev.irc.app.admin.support.AdminAuditor;
import ak.dev.irc.app.admin.support.RequiresStepUp;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.knowledge.entity.Madhhab;
import ak.dev.irc.app.knowledge.entity.Topic;
import ak.dev.irc.app.knowledge.repository.MadhhabRepository;
import ak.dev.irc.app.knowledge.repository.TopicRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Curation console for the trilingual knowledge taxonomy (blueprint §3.14,
 * knowledge-vocabulary.md): the tables' FIRST write callers — until now the
 * only writes were DB migrations. Every mutation evicts the Redis-cached
 * vocabulary regions (the one non-obvious correctness requirement) and
 * retire is soft — a hard delete would break {@code findById} validation on
 * every profile referencing the row.
 */
@RestController
@RequestMapping("/api/v1/admin/knowledge")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminKnowledgeController {

    private final TopicRepository topicRepository;
    private final MadhhabRepository madhhabRepository;
    private final JdbcTemplate jdbcTemplate;
    private final CacheManager cacheManager;
    private final AdminAuditor adminAuditor;

    public record VocabBody(@NotBlank @Size(max = 150) String nameEn,
                            @NotBlank @Size(max = 100) String nameAr,
                            @NotBlank @Size(max = 100) String nameCkb) {
    }

    public record VocabPatch(@Size(max = 150) String nameEn,
                             @Size(max = 100) String nameAr,
                             @Size(max = 100) String nameCkb) {
    }

    // ── list with usage counts (K1) ─────────────────────────────────────

    @GetMapping("/topics")
    public ResponseEntity<List<Map<String, Object>>> topics() {
        Map<Integer, Long> usage = usageCounts(
                "SELECT s.topic_id, COUNT(*) FROM user_topic_specializations s GROUP BY s.topic_id");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Topic t : topicRepository.findAll()) {
            out.add(vocabRow(t.getId(), t.getNameEn(), t.getNameAr(), t.getNameCkb(),
                    t.getArchivedAt(), usage.getOrDefault(t.getId(), 0L)));
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/madhhabs")
    public ResponseEntity<List<Map<String, Object>>> madhhabs() {
        Map<Integer, Long> usage = usageCounts(
                "SELECT p.madhhab_id, COUNT(*) FROM user_profiles p "
                        + "WHERE p.madhhab_id IS NOT NULL GROUP BY p.madhhab_id");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Madhhab m : madhhabRepository.findAll()) {
            out.add(vocabRow(m.getId(), m.getNameEn(), m.getNameAr(), m.getNameCkb(),
                    m.getArchivedAt(), usage.getOrDefault(m.getId(), 0L)));
        }
        return ResponseEntity.ok(out);
    }

    // ── add / edit / retire (K2-K4) ─────────────────────────────────────

    @PostMapping("/topics")
    @RequiresStepUp
    @Transactional
    public ResponseEntity<Topic> addTopic(@RequestBody VocabBody body) {
        requireUniqueTopic(body.nameEn(), null);
        Topic saved = topicRepository.save(Topic.builder()
                .nameEn(body.nameEn().trim()).nameAr(body.nameAr().trim())
                .nameCkb(body.nameCkb().trim()).build());
        evict();
        adminAuditor.record(AuditOperation.CREATE, "Topic", null, "ADMIN_VOCAB_ADD",
                "topic '" + saved.getNameEn() + "' (id=" + saved.getId() + ")");
        return ResponseEntity.status(201).body(saved);
    }

    @PostMapping("/madhhabs")
    @RequiresStepUp
    @Transactional
    public ResponseEntity<Madhhab> addMadhhab(@RequestBody VocabBody body) {
        requireUniqueMadhhab(body.nameEn(), null);
        Madhhab saved = madhhabRepository.save(Madhhab.builder()
                .nameEn(body.nameEn().trim()).nameAr(body.nameAr().trim())
                .nameCkb(body.nameCkb().trim()).build());
        evict();
        adminAuditor.record(AuditOperation.CREATE, "Madhhab", null, "ADMIN_VOCAB_ADD",
                "madhhab '" + saved.getNameEn() + "' (id=" + saved.getId() + ")");
        return ResponseEntity.status(201).body(saved);
    }

    @PatchMapping("/topics/{id}")
    @RequiresStepUp
    @Transactional
    public ResponseEntity<Topic> editTopic(@PathVariable Integer id, @RequestBody VocabPatch body) {
        Topic t = topicRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Topic", "id", id));
        if (body.nameEn() != null && !body.nameEn().isBlank()) {
            requireUniqueTopic(body.nameEn(), id);
            t.setNameEn(body.nameEn().trim());
        }
        if (body.nameAr() != null && !body.nameAr().isBlank()) t.setNameAr(body.nameAr().trim());
        if (body.nameCkb() != null && !body.nameCkb().isBlank()) t.setNameCkb(body.nameCkb().trim());
        topicRepository.save(t);
        evict();
        adminAuditor.record(AuditOperation.UPDATE, "Topic", null, "ADMIN_VOCAB_EDIT",
                "topic id=" + id);
        return ResponseEntity.ok(t);
    }

    @PatchMapping("/madhhabs/{id}")
    @RequiresStepUp
    @Transactional
    public ResponseEntity<Madhhab> editMadhhab(@PathVariable Integer id, @RequestBody VocabPatch body) {
        Madhhab m = madhhabRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Madhhab", "id", id));
        if (body.nameEn() != null && !body.nameEn().isBlank()) {
            requireUniqueMadhhab(body.nameEn(), id);
            m.setNameEn(body.nameEn().trim());
        }
        if (body.nameAr() != null && !body.nameAr().isBlank()) m.setNameAr(body.nameAr().trim());
        if (body.nameCkb() != null && !body.nameCkb().isBlank()) m.setNameCkb(body.nameCkb().trim());
        madhhabRepository.save(m);
        evict();
        adminAuditor.record(AuditOperation.UPDATE, "Madhhab", null, "ADMIN_VOCAB_EDIT",
                "madhhab id=" + id);
        return ResponseEntity.ok(m);
    }

    @PostMapping("/topics/{id}/retire")
    @RequiresStepUp
    @Transactional
    public ResponseEntity<Void> retireTopic(@PathVariable Integer id) {
        Topic t = topicRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Topic", "id", id));
        t.setArchivedAt(LocalDateTime.now());
        topicRepository.save(t);
        evict();
        adminAuditor.record(AuditOperation.UPDATE, "Topic", null, "ADMIN_VOCAB_RETIRE",
                "topic '" + t.getNameEn() + "' (id=" + id + ")");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/madhhabs/{id}/retire")
    @RequiresStepUp
    @Transactional
    public ResponseEntity<Void> retireMadhhab(@PathVariable Integer id) {
        Madhhab m = madhhabRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Madhhab", "id", id));
        m.setArchivedAt(LocalDateTime.now());
        madhhabRepository.save(m);
        evict();
        adminAuditor.record(AuditOperation.UPDATE, "Madhhab", null, "ADMIN_VOCAB_RETIRE",
                "madhhab '" + m.getNameEn() + "' (id=" + id + ")");
        return ResponseEntity.noContent().build();
    }

    /** Manual escape hatch (K5) — writes above already evict automatically. */
    @PostMapping("/cache/evict")
    public ResponseEntity<Void> evictCaches() {
        evict();
        adminAuditor.record(AuditOperation.OTHER, "Knowledge", null, "ADMIN_VOCAB_CACHE_EVICT");
        return ResponseEntity.noContent().build();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private void evict() {
        for (String cache : List.of("knowledge-topics", "knowledge-madhhabs")) {
            var c = cacheManager.getCache(cache);
            if (c != null) c.clear();
        }
    }

    private void requireUniqueTopic(String nameEn, Integer exceptId) {
        String needle = nameEn.trim().toLowerCase();
        for (Topic t : topicRepository.findAll()) {
            if (t.getNameEn() != null && t.getNameEn().trim().toLowerCase().equals(needle)
                    && (exceptId == null || !t.getId().equals(exceptId))) {
                throw new BadRequestException("A topic named '" + nameEn + "' already exists.",
                        "DUPLICATE_VOCAB_NAME");
            }
        }
    }

    private void requireUniqueMadhhab(String nameEn, Integer exceptId) {
        String needle = nameEn.trim().toLowerCase();
        for (Madhhab m : madhhabRepository.findAll()) {
            if (m.getNameEn() != null && m.getNameEn().trim().toLowerCase().equals(needle)
                    && (exceptId == null || !m.getId().equals(exceptId))) {
                throw new BadRequestException("A madhhab named '" + nameEn + "' already exists.",
                        "DUPLICATE_VOCAB_NAME");
            }
        }
    }

    private Map<Integer, Long> usageCounts(String sql) {
        Map<Integer, Long> usage = new LinkedHashMap<>();
        try {
            jdbcTemplate.query(sql, rs -> {
                usage.put(rs.getInt(1), rs.getLong(2));
            });
        } catch (Exception ignored) {
        }
        return usage;
    }

    private static Map<String, Object> vocabRow(Integer id, String en, String ar, String ckb,
                                                LocalDateTime archivedAt, long usage) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("nameEn", en);
        row.put("nameAr", ar);
        row.put("nameCkb", ckb);
        row.put("retired", archivedAt != null);
        row.put("archivedAt", archivedAt);
        row.put("usageCount", usage);
        return row;
    }
}

package ak.dev.irc.app.admin.moderation;

import ak.dev.irc.app.admin.support.AdminAuditor;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.messages.AdminContentMessages;
import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.settings.privacy.service.KeywordNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Platform keyword blocklist — CRUD, Redis-cached normalized sets, the
 * create-path enforcement hook, and the dry-run tester. Enforcement is
 * fail-open: an infrastructure error must never block user content.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformKeywordService {

    private static final String CACHE_KEY_BLOCK = "platform:keywords:block";
    private static final String CACHE_KEY_FLAG  = "platform:keywords:flag";
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final String SEP = ""; // unit separator — never appears in a normalized keyword

    private final PlatformKeywordRepository keywordRepository;
    private final PlatformKeywordHitRepository hitRepository;
    private final KeywordNormalizer normalizer;
    private final StringRedisTemplate redis;
    private final AdminAuditor adminAuditor;

    // ── CRUD ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PlatformKeyword> list() {
        return keywordRepository.findAll();
    }

    @Transactional
    public PlatformKeyword add(String keyword, PlatformKeyword.Severity severity, String note) {
        if (keyword == null || keyword.isBlank()) {
            throw new BadRequestException(AdminContentMessages.INVALID_INPUT_KEYWORD_MSG,
                    AdminContentMessages.INVALID_INPUT);
        }
        String display = keyword.trim();
        if (display.length() > 100) display = display.substring(0, 100);
        String norm = normalizer.normalize(display);
        if (norm.isBlank()) {
            throw new BadRequestException(AdminContentMessages.INVALID_KEYWORD_MSG,
                    AdminContentMessages.INVALID_KEYWORD);
        }
        PlatformKeyword existing = keywordRepository.findByKeywordNormalized(norm).orElse(null);
        if (existing != null) {
            existing.setSeverity(severity);
            existing.setNote(note);
            invalidateCache();
            adminAuditor.record(AuditOperation.UPDATE, "PlatformKeyword", existing.getId(),
                    "ADMIN_BLOCKLIST_ADD", norm + " (" + severity + ", updated)");
            return keywordRepository.save(existing);
        }
        PlatformKeyword saved = keywordRepository.save(PlatformKeyword.builder()
                .keywordDisplay(display)
                .keywordNormalized(norm)
                .severity(severity)
                .note(note)
                .addedBy(SecurityUtils.getCurrentUserId().orElse(null))
                .build());
        invalidateCache();
        adminAuditor.record(AuditOperation.CREATE, "PlatformKeyword", saved.getId(),
                "ADMIN_BLOCKLIST_ADD", norm + " (" + severity + ")");
        return saved;
    }

    @Transactional
    public PlatformKeyword update(UUID id, PlatformKeyword.Severity severity, String note) {
        PlatformKeyword kw = keywordRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("PlatformKeyword", "id", id));
        if (severity != null) kw.setSeverity(severity);
        if (note != null) kw.setNote(note);
        invalidateCache();
        adminAuditor.record(AuditOperation.UPDATE, "PlatformKeyword", id,
                "ADMIN_BLOCKLIST_UPDATE", kw.getKeywordNormalized() + " → " + kw.getSeverity());
        return keywordRepository.save(kw);
    }

    @Transactional
    public void remove(UUID id) {
        PlatformKeyword kw = keywordRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("PlatformKeyword", "id", id));
        keywordRepository.delete(kw);
        invalidateCache();
        adminAuditor.record(AuditOperation.DELETE, "PlatformKeyword", id,
                "ADMIN_BLOCKLIST_REMOVE", kw.getKeywordNormalized());
    }

    /** Dry-run: which keywords would match this text after normalization. */
    @Transactional(readOnly = true)
    public List<String> test(String text) {
        List<String> matches = new ArrayList<>();
        for (PlatformKeyword kw : keywordRepository.findAll()) {
            if (normalizer.matchesAny(text, List.of(kw.getKeywordNormalized()))) {
                matches.add(kw.getKeywordDisplay() + " (" + kw.getSeverity() + ")");
            }
        }
        return matches;
    }

    // ── Enforcement hook (called from content-create paths) ─────────────

    /**
     * Checks text against the blocklist. BLOCK → throws
     * {@code CONTENT_BLOCKED_BY_POLICY}; FLAG → returns the matched keyword
     * (caller records the hit once the content id exists); no match → null.
     * Fail-open on any infrastructure error.
     */
    public String blockOrFlag(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            String blocked = firstMatch(text, cached(CACHE_KEY_BLOCK, PlatformKeyword.Severity.BLOCK));
            if (blocked != null) {
                throw new BadRequestException(
                        AdminContentMessages.CONTENT_BLOCKED_BY_POLICY_MSG,
                        AdminContentMessages.CONTENT_BLOCKED_BY_POLICY);
            }
            return firstMatch(text, cached(CACHE_KEY_FLAG, PlatformKeyword.Severity.FLAG));
        } catch (BadRequestException policy) {
            throw policy;
        } catch (Exception infra) {
            log.debug("[KEYWORDS] enforcement skipped (fail-open): {}", infra.getMessage());
            return null;
        }
    }

    /** Records a FLAG hit for the moderation queue (best-effort, async-safe). */
    public void recordHit(String targetType, String targetRef, UUID authorId, String keywordNorm) {
        try {
            hitRepository.save(PlatformKeywordHit.builder()
                    .targetType(targetType)
                    .targetRef(targetRef)
                    .authorId(authorId)
                    .keywordNormalized(keywordNorm)
                    .build());
        } catch (Exception e) {
            log.debug("[KEYWORDS] hit record failed: {}", e.getMessage());
        }
    }

    private String firstMatch(String text, List<String> normalizedKeywords) {
        if (normalizedKeywords.isEmpty()) return null;
        String normText = normalizer.normalize(text);
        for (String kw : normalizedKeywords) {
            if (!kw.isBlank() && normText.contains(kw)) {
                return kw;
            }
        }
        return null;
    }

    private List<String> cached(String key, PlatformKeyword.Severity severity) {
        try {
            String raw = redis.opsForValue().get(key);
            if (raw != null) {
                return raw.isEmpty() ? List.of() : List.of(raw.split(java.util.regex.Pattern.quote(SEP)));
            }
        } catch (Exception ignored) {
        }
        List<String> fresh = keywordRepository.findBySeverity(severity).stream()
                .map(PlatformKeyword::getKeywordNormalized)
                .toList();
        try {
            redis.opsForValue().set(key, String.join(SEP, fresh), CACHE_TTL);
        } catch (Exception ignored) {
        }
        return fresh;
    }

    private void invalidateCache() {
        try {
            redis.delete(List.of(CACHE_KEY_BLOCK, CACHE_KEY_FLAG));
        } catch (Exception ignored) {
        }
    }
}

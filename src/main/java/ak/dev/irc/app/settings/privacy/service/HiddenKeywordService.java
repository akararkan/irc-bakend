package ak.dev.irc.app.settings.privacy.service;

import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.settings.privacy.entity.HiddenKeyword;
import ak.dev.irc.app.settings.privacy.repository.HiddenKeywordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Hidden-keyword muting (spec §13). Keywords are stored in normalized form and
 * matched at feed assembly and notification fan-out. Callers on the read path
 * fetch {@link #normalizedFor} once and use {@link KeywordNormalizer#matchesAny}.
 */
@Service
@RequiredArgsConstructor
public class HiddenKeywordService {

    private static final int MAX_KEYWORDS = 200;

    private final HiddenKeywordRepository repo;
    private final KeywordNormalizer normalizer;

    @Transactional(readOnly = true)
    public List<HiddenKeyword> list(UUID userId) {
        return repo.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public List<String> normalizedFor(UUID userId) {
        return repo.findNormalizedByUser(userId);
    }

    @Transactional
    public HiddenKeyword add(UUID userId, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            throw new BadRequestException("Keyword must not be blank.");
        }
        String display = keyword.trim();
        if (display.length() > 100) display = display.substring(0, 100);
        String norm = normalizer.normalize(display);
        if (norm.isEmpty()) throw new BadRequestException("Keyword normalizes to empty.");
        if (repo.existsByUserIdAndKeywordNormalized(userId, norm)) {
            return repo.findByUserIdOrderByCreatedAtDesc(userId).stream()
                    .filter(k -> k.getKeywordNormalized().equals(norm))
                    .findFirst().orElseThrow();
        }
        if (repo.findByUserIdOrderByCreatedAtDesc(userId).size() >= MAX_KEYWORDS) {
            throw new BadRequestException("Hidden-keyword limit reached (" + MAX_KEYWORDS + ").");
        }
        return repo.save(HiddenKeyword.builder()
                .userId(userId)
                .keywordDisplay(display)
                .keywordNormalized(norm)
                .build());
    }

    @Transactional
    public void remove(UUID userId, UUID id) {
        repo.deleteByIdAndUserId(id, userId);
    }

    /** True when the given text trips one of the user's hidden keywords. */
    @Transactional(readOnly = true)
    public boolean isHidden(UUID userId, String text) {
        return normalizer.matchesAny(text, normalizedFor(userId));
    }
}

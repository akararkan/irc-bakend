package ak.dev.irc.app.settings.safety.service;

import ak.dev.irc.app.settings.safety.entity.UserStrike;
import ak.dev.irc.app.settings.safety.repository.UserStrikeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Decaying strike ledger (spec §18). Strikes expire after 90 days; a user's
 * "active" strike count is what drives automated restrictions.
 */
@Service
@RequiredArgsConstructor
public class StrikeService {

    private final UserStrikeRepository repo;

    /** Only the non-expired strikes a user currently carries. */
    @Transactional(readOnly = true)
    public List<UserStrike> myStrikes(UUID userId) {
        return repo.findByUserIdOrderByIssuedAtDesc(userId).stream()
                .filter(UserStrike::isActive)
                .toList();
    }

    @Transactional(readOnly = true)
    public long activeCount(UUID userId) {
        return repo.countActive(userId, LocalDateTime.now());
    }

    /**
     * Issue a strike (called by moderation flows). 90-day decay is applied by
     * the entity's {@code @PrePersist}.
     */
    @Transactional
    public UserStrike issueStrike(UUID userId, UUID reportId, String reason) {
        return repo.save(UserStrike.builder()
                .userId(userId)
                .reportId(reportId)
                .reason(reason)
                .build());
    }
}

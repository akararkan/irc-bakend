package ak.dev.irc.app.research.repository;

import ak.dev.irc.app.research.entity.ResearchView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.UUID;

@Repository
public interface ResearchViewRepository extends JpaRepository<ResearchView, UUID> {

    /** Prevent duplicate counted views within a window (same user or IP) */
    boolean existsByResearchIdAndUserIdAndCreatedAtAfter(UUID researchId, UUID userId, LocalDateTime after);

    boolean existsByResearchIdAndIpAddressAndUserIsNullAndCreatedAtAfter(
            UUID researchId, String ipAddress, LocalDateTime after);

    long countByResearchId(UUID researchId);
}

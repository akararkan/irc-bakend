package ak.dev.irc.app.admin.moderation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ModerationDecisionRepository extends JpaRepository<ModerationDecision, UUID> {

    List<ModerationDecision> findByTargetTypeAndTargetRefOrderByCreatedAtDesc(String targetType,
                                                                              String targetRef);

    Page<ModerationDecision> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByCreatedAtAfter(java.time.LocalDateTime after);
}

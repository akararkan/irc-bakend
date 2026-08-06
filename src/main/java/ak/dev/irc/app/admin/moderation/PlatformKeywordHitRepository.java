package ak.dev.irc.app.admin.moderation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PlatformKeywordHitRepository extends JpaRepository<PlatformKeywordHit, UUID> {

    Page<PlatformKeywordHit> findByResolvedFalseOrderByCreatedAtAsc(Pageable pageable);

    long countByResolvedFalse();

    long countByKeywordNormalizedAndCreatedAtAfter(String keywordNormalized,
                                                   java.time.LocalDateTime after);
}

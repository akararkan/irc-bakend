package ak.dev.irc.app.admin.moderation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface PlatformKeywordHitRepository extends JpaRepository<PlatformKeywordHit, UUID> {

    /** Unresolved keyword hits, oldest first (the review queue). */
    @Query(value = """
        SELECT h FROM PlatformKeywordHit h
        WHERE h.resolved = FALSE
        ORDER BY h.createdAt ASC
        """,
        countQuery = "SELECT COUNT(h) FROM PlatformKeywordHit h WHERE h.resolved = FALSE")
    Page<PlatformKeywordHit> openHits(Pageable pageable);
}

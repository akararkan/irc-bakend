package ak.dev.irc.app.admin.activity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface BreakGlassCaseRepository extends JpaRepository<BreakGlassCase, UUID> {

    /** Cases against one user in a given state (the open-case dedup guard). */
    @Query("""
        SELECT b FROM BreakGlassCase b
        WHERE b.targetUserId = :targetUserId AND b.status = :status
        """)
    List<BreakGlassCase> casesForTarget(@Param("targetUserId") UUID targetUserId,
                                        @Param("status") BreakGlassCase.Status status);

    /** All cases, newest first. */
    @Query(value = "SELECT b FROM BreakGlassCase b ORDER BY b.openedAt DESC",
           countQuery = "SELECT COUNT(b) FROM BreakGlassCase b")
    Page<BreakGlassCase> browse(Pageable pageable);
}

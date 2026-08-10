package ak.dev.irc.app.admin.research;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ResearchFlagRepository extends JpaRepository<ResearchFlag, UUID> {

    /** Full flag history of one research item, newest first. */
    @Query("""
        SELECT f FROM ResearchFlag f
        WHERE f.researchId = :researchId
        ORDER BY f.createdAt DESC
        """)
    List<ResearchFlag> flagsFor(@Param("researchId") UUID researchId);

    /** The open-flag review queue, oldest first (FIFO). */
    @Query(value = """
        SELECT f FROM ResearchFlag f
        WHERE f.resolvedAt IS NULL
        ORDER BY f.createdAt ASC
        """,
        countQuery = "SELECT COUNT(f) FROM ResearchFlag f WHERE f.resolvedAt IS NULL")
    Page<ResearchFlag> openFlags(Pageable pageable);
}

package ak.dev.irc.app.admin.analytics;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CohortRetentionRepository extends JpaRepository<CohortRetention, UUID> {

    @Query("""
        SELECT c FROM CohortRetention c
        WHERE c.cohortWeek IN :weeks
        ORDER BY c.cohortWeek DESC, c.weekOffset ASC
        """)
    List<CohortRetention> grid(@Param("weeks") List<String> weeks);

    @Query("""
        SELECT c FROM CohortRetention c
        WHERE c.cohortWeek = :week AND c.weekOffset = :offset
        """)
    Optional<CohortRetention> cell(@Param("week") String week, @Param("offset") int offset);
}

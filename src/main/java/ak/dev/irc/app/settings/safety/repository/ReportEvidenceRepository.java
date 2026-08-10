package ak.dev.irc.app.settings.safety.repository;

import ak.dev.irc.app.settings.safety.entity.ReportEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReportEvidenceRepository extends JpaRepository<ReportEvidence, UUID> {

    boolean existsByGroupKey(String groupKey);

    @Query("SELECT e FROM ReportEvidence e WHERE e.groupKey = :groupKey")
    Optional<ReportEvidence> findByGroupKey(@Param("groupKey") String groupKey);
}

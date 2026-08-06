package ak.dev.irc.app.settings.safety.repository;

import ak.dev.irc.app.settings.safety.entity.ReportEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReportEvidenceRepository extends JpaRepository<ReportEvidence, UUID> {

    boolean existsByGroupKey(String groupKey);

    Optional<ReportEvidence> findByGroupKey(String groupKey);
}

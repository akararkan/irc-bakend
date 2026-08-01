package ak.dev.irc.app.settings.safety.repository;

import ak.dev.irc.app.settings.safety.entity.Report;
import ak.dev.irc.app.settings.safety.enums.ReportState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReportRepository extends JpaRepository<Report, UUID> {

    Page<Report> findByReporterIdOrderByCreatedAtDesc(UUID reporterId, Pageable pageable);

    /** Dedup probe: an already-open report by the same reporter for the same group. */
    Optional<Report> findFirstByReporterIdAndGroupKeyAndStateIn(UUID reporterId,
                                                                String groupKey,
                                                                Collection<ReportState> states);
}

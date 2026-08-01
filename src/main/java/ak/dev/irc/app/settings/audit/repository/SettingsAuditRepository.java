package ak.dev.irc.app.settings.audit.repository;

import ak.dev.irc.app.settings.audit.entity.SettingsAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface SettingsAuditRepository extends JpaRepository<SettingsAudit, UUID> {

    Page<SettingsAudit> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}

package ak.dev.irc.app.settings.policy.repository;

import ak.dev.irc.app.settings.policy.entity.PolicyAcceptance;
import ak.dev.irc.app.settings.policy.entity.PolicyAcceptance.PolicyAcceptanceId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PolicyAcceptanceRepository
        extends JpaRepository<PolicyAcceptance, PolicyAcceptanceId> {

    List<PolicyAcceptance> findByIdUserId(UUID userId);
}

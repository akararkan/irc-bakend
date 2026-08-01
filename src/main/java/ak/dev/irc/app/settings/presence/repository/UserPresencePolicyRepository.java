package ak.dev.irc.app.settings.presence.repository;

import ak.dev.irc.app.settings.presence.entity.UserPresencePolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface UserPresencePolicyRepository extends JpaRepository<UserPresencePolicy, UUID> {
}

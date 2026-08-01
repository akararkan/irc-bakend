package ak.dev.irc.app.settings.discovery.repository;

import ak.dev.irc.app.settings.discovery.entity.UserDiscoverability;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface UserDiscoverabilityRepository extends JpaRepository<UserDiscoverability, UUID> {
}

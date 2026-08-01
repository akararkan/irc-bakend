package ak.dev.irc.app.settings.privacy.repository;

import ak.dev.irc.app.settings.privacy.entity.UserPrivacy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface UserPrivacyRepository extends JpaRepository<UserPrivacy, UUID> {
}

package ak.dev.irc.app.settings.notification.repository;

import ak.dev.irc.app.settings.notification.entity.UserDnd;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface UserDndRepository extends JpaRepository<UserDnd, UUID> {
}

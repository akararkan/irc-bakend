package ak.dev.irc.app.user.repository;

import ak.dev.irc.app.user.entity.UserInvite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserInviteRepository extends JpaRepository<UserInvite, UUID> {

    Optional<UserInvite> findByToken(String token);
}

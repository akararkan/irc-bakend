package ak.dev.irc.app.settings.data.repository;

import ak.dev.irc.app.settings.data.entity.DeletedAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface DeletedAccountRepository extends JpaRepository<DeletedAccount, UUID> {
}

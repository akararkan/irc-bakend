package ak.dev.irc.app.settings.data.repository;

import ak.dev.irc.app.settings.data.entity.AccountDeletionRequest;
import ak.dev.irc.app.settings.data.enums.DeletionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountDeletionRequestRepository
        extends JpaRepository<AccountDeletionRequest, UUID> {

    Optional<AccountDeletionRequest> findFirstByUserIdAndStatus(UUID userId, DeletionStatus status);

    List<AccountDeletionRequest> findByStatusAndPurgeAfterBefore(DeletionStatus status,
                                                                 LocalDateTime cutoff);
}

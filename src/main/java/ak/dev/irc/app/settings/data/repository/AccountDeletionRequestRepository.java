package ak.dev.irc.app.settings.data.repository;

import ak.dev.irc.app.settings.data.entity.AccountDeletionRequest;
import ak.dev.irc.app.settings.data.enums.DeletionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountDeletionRequestRepository
        extends JpaRepository<AccountDeletionRequest, UUID> {

    Optional<AccountDeletionRequest> findFirstByUserIdAndStatus(UUID userId, DeletionStatus status);

    @Query("SELECT r FROM AccountDeletionRequest r WHERE r.status = :status AND r.purgeAfter < :cutoff")
    List<AccountDeletionRequest> findByStatusAndPurgeAfterBefore(@Param("status") DeletionStatus status,
                                                                 @Param("cutoff") LocalDateTime cutoff);

    Optional<AccountDeletionRequest> findFirstByUserIdOrderByRequestedAtDesc(UUID userId);

    @Query("SELECT COUNT(r) FROM AccountDeletionRequest r WHERE r.status = :status")
    long countByStatus(@Param("status") DeletionStatus status);
}

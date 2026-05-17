package ak.dev.irc.app.user.repository;

import ak.dev.irc.app.user.entity.ScholarVerification;
import ak.dev.irc.app.user.enums.VerificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ScholarVerificationRepository extends JpaRepository<ScholarVerification, UUID> {

    @Query("SELECT v FROM ScholarVerification v WHERE v.user.id = :userId ORDER BY v.createdAt DESC")
    Optional<ScholarVerification> findLatestByUserId(@Param("userId") UUID userId);

    Page<ScholarVerification> findByStatus(VerificationStatus status, Pageable pageable);

    boolean existsByUserIdAndStatus(UUID userId, VerificationStatus status);
}

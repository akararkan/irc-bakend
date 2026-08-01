package ak.dev.irc.app.security.twofa.repository;

import ak.dev.irc.app.security.twofa.entity.RecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RecoveryCodeRepository extends JpaRepository<RecoveryCode, UUID> {

    List<RecoveryCode> findByUserIdAndUsedAtIsNull(UUID userId);

    long countByUserIdAndUsedAtIsNull(UUID userId);

    @Modifying
    @Query("DELETE FROM RecoveryCode rc WHERE rc.userId = :userId")
    void deleteAllForUser(@Param("userId") UUID userId);
}

package ak.dev.irc.app.settings.discovery.qr.repository;

import ak.dev.irc.app.settings.discovery.qr.entity.QrToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface QrTokenRepository extends JpaRepository<QrToken, UUID> {

    @Query("SELECT t FROM QrToken t WHERE t.userId = :userId")
    Optional<QrToken> findByUserId(@Param("userId") UUID userId);

    Optional<QrToken> findByOpaqueToken(String opaqueToken);
}

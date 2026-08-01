package ak.dev.irc.app.settings.discovery.qr.repository;

import ak.dev.irc.app.settings.discovery.qr.entity.QrToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface QrTokenRepository extends JpaRepository<QrToken, UUID> {

    Optional<QrToken> findByUserId(UUID userId);

    Optional<QrToken> findByOpaqueToken(String opaqueToken);
}

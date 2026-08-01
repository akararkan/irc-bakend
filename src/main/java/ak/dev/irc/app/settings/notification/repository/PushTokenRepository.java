package ak.dev.irc.app.settings.notification.repository;

import ak.dev.irc.app.settings.notification.entity.PushToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PushTokenRepository extends JpaRepository<PushToken, UUID> {

    List<PushToken> findByUserId(UUID userId);

    Optional<PushToken> findByToken(String token);

    long deleteByToken(String token);

    @Modifying
    @Query("DELETE FROM PushToken p WHERE p.sid = :sid")
    void deleteBySid(@Param("sid") UUID sid);

    @Modifying
    @Query("DELETE FROM PushToken p WHERE p.id = :id AND p.userId = :userId")
    void deleteOwned(@Param("id") UUID id, @Param("userId") UUID userId);
}

package ak.dev.irc.app.user.repository;

import ak.dev.irc.app.user.entity.RefreshToken;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByToken(String token);

    @Modifying
    @Query("""
        UPDATE RefreshToken rt
        SET rt.isRevoked = true
        WHERE rt.user.id = :userId AND rt.isRevoked = false
        """)
    void revokeAllForUser(@Param("userId") UUID userId);

    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.isRevoked = true WHERE rt.token = :token")
    void revokeByToken(@Param("token") String token);

    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.user.id = :userId")
    void deleteAllByUserId(@Param("userId") UUID userId);
}

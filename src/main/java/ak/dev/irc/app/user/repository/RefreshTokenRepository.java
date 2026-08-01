package ak.dev.irc.app.user.repository;

import ak.dev.irc.app.user.entity.RefreshToken;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByToken(String token);

    // ── Session surfacing (spec §12) ─────────────────────────────────────────

    Optional<RefreshToken> findBySid(UUID sid);

    /** Active (non-revoked, unexpired) sessions for the Active Sessions screen. */
    @Query("""
        SELECT rt FROM RefreshToken rt
        WHERE rt.user.id = :userId
          AND rt.isRevoked = false
          AND rt.expiresAt > :now
        ORDER BY rt.lastSeenAt DESC NULLS LAST, rt.createdAt DESC
        """)
    List<RefreshToken> findActiveSessions(@Param("userId") UUID userId,
                                          @Param("now") LocalDateTime now);

    @Modifying
    @Query("""
        UPDATE RefreshToken rt
        SET rt.isRevoked = true, rt.revokedAt = :now
        WHERE rt.sid = :sid AND rt.isRevoked = false
        """)
    int revokeBySid(@Param("sid") UUID sid, @Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.trustedUntil = :until WHERE rt.sid = :sid")
    int trustBySid(@Param("sid") UUID sid, @Param("until") LocalDateTime until);

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

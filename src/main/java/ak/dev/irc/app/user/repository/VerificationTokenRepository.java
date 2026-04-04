package ak.dev.irc.app.user.repository;

import ak.dev.irc.app.user.entity.VerificationToken;
import ak.dev.irc.app.user.enums.TokenType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface VerificationTokenRepository extends JpaRepository<VerificationToken, UUID> {

    Optional<VerificationToken> findByTokenAndTypeAndIsUsedFalse(String token, TokenType type);

    @Modifying
    @Query("""
        UPDATE VerificationToken vt
        SET vt.isUsed = true
        WHERE vt.user.id = :userId
          AND vt.type    = :type
          AND vt.isUsed  = false
        """)
    void invalidateAllForUserAndType(@Param("userId") UUID userId,
                                     @Param("type")   TokenType type);
}

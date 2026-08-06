package ak.dev.irc.app.security.otp.repository;

import ak.dev.irc.app.security.otp.entity.OtpChallenge;
import ak.dev.irc.app.security.otp.enums.OtpPurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OtpChallengeRepository extends JpaRepository<OtpChallenge, UUID> {

    Optional<OtpChallenge> findFirstByDestinationHashAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
            String destinationHash, OtpPurpose purpose);

    /** Retention sweep (logs-audit.md §7): 180d on the indexed expiry column. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
            "DELETE FROM OtpChallenge c WHERE c.expiresAt < :cutoff")
    int deleteExpiredBefore(@org.springframework.data.repository.query.Param("cutoff")
                            java.time.LocalDateTime cutoff);

    /** OTP-abuse alert rule: challenge volume inside the sweep window. */
    @org.springframework.data.jpa.repository.Query(
            "SELECT COUNT(c) FROM OtpChallenge c WHERE c.createdAt >= :since")
    long countIssuedSince(@org.springframework.data.repository.query.Param("since")
                          java.time.LocalDateTime since);

    /** Aggregate-only admin surface (users-roles.md §5): challenge volume and
     *  outcome mix per purpose in a window — never row-level codes/hashes. */
    @org.springframework.data.jpa.repository.Query("""
        SELECT c.purpose, COUNT(c),
               SUM(CASE WHEN c.consumedAt IS NOT NULL THEN 1 ELSE 0 END),
               SUM(CASE WHEN c.attempts >= 3 THEN 1 ELSE 0 END)
        FROM OtpChallenge c WHERE c.createdAt >= :since
        GROUP BY c.purpose
        """)
    java.util.List<Object[]> statsByPurposeSince(
            @org.springframework.data.repository.query.Param("since") java.time.LocalDateTime since);

    /** Burst detector: destinations hammered hardest in the window (hashes only). */
    @org.springframework.data.jpa.repository.Query("""
        SELECT c.destinationHash, COUNT(c)
        FROM OtpChallenge c WHERE c.createdAt >= :since
        GROUP BY c.destinationHash
        HAVING COUNT(c) >= :threshold
        ORDER BY COUNT(c) DESC
        """)
    java.util.List<Object[]> hotDestinationsSince(
            @org.springframework.data.repository.query.Param("since") java.time.LocalDateTime since,
            @org.springframework.data.repository.query.Param("threshold") long threshold);
}

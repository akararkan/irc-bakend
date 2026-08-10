package ak.dev.irc.app.moderation.repository;

import ak.dev.irc.app.moderation.entity.ModerationCase;
import ak.dev.irc.app.moderation.enums.ModeratedEntityType;
import ak.dev.irc.app.moderation.enums.ModerationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ModerationCaseRepository extends JpaRepository<ModerationCase, UUID> {

    /** Latest case for a content unit — the one whose verdict is authoritative. */
    Optional<ModerationCase> findFirstByEntityTypeAndEntityRefOrderByRevisionDesc(
            ModeratedEntityType entityType, String entityRef);

    List<ModerationCase> findByEntityTypeAndEntityRefOrderByRevisionDesc(
            ModeratedEntityType entityType, String entityRef);

    /**
     * SLA sweeper feed (§5.6): held past the deadline with no verdict. Ordered
     * oldest-first so the longest-waiting author is served first.
     */
    @Query("""
            SELECT c FROM ModerationCase c
            WHERE c.status = ak.dev.irc.app.moderation.enums.ModerationStatus.PENDING
              AND c.holdDeadline <= :now
            ORDER BY c.holdDeadline ASC
            """)
    List<ModerationCase> findBreachedHolds(@Param("now") LocalDateTime now, Pageable pageable);

    /**
     * Cases still inside their hold window that the inline attempt could not
     * decide — the worker retries these before the deadline bites.
     */
    @Query("""
            SELECT c FROM ModerationCase c
            WHERE c.status = ak.dev.irc.app.moderation.enums.ModerationStatus.PENDING
              AND c.holdDeadline > :now
              AND c.attempts < :maxAttempts
            ORDER BY c.submittedAt ASC
            """)
    List<ModerationCase> findRetryable(@Param("now") LocalDateTime now,
                                       @Param("maxAttempts") int maxAttempts,
                                       Pageable pageable);

    /**
     * A verdict was reached but the content flip failed or never ran. Left alone
     * these are invisible failures — approved content that never publishes — so
     * the sweeper re-drives them.
     */
    @Query("""
            SELECT c FROM ModerationCase c
            WHERE c.appliedAt IS NULL
              AND c.status IN (ak.dev.irc.app.moderation.enums.ModerationStatus.APPROVED,
                               ak.dev.irc.app.moderation.enums.ModerationStatus.REJECTED)
            ORDER BY c.decidedAt ASC
            """)
    List<ModerationCase> findUnapplied(Pageable pageable);

    /**
     * Cases parked in review that the classifier never actually saw — their hold
     * window expired while the inference service was unreachable. Once it is back,
     * these are re-scorable rather than human work: after an outage the queue
     * fills with content nobody has an opinion about.
     */
    @Query("""
            SELECT c FROM ModerationCase c
            WHERE c.status = ak.dev.irc.app.moderation.enums.ModerationStatus.IN_REVIEW
              AND c.reasonCode = 'SLA_BREACH'
              AND c.modelVersion IS NULL
            ORDER BY c.submittedAt ASC
            """)
    List<ModerationCase> findNeverScored(Pageable pageable);

    @EntityGraph(attributePaths = "fields")
    @Query("SELECT c FROM ModerationCase c WHERE c.id = :id")
    Optional<ModerationCase> findWithFields(@Param("id") UUID id);

    /** The review queue (§12.1), newest-risk-first when sorted by maxScore. */
    @Query(value = "SELECT c FROM ModerationCase c WHERE c.status = :status",
           countQuery = "SELECT COUNT(c) FROM ModerationCase c WHERE c.status = :status")
    Page<ModerationCase> findByStatus(@Param("status") ModerationStatus status, Pageable pageable);

    @Query(value = "SELECT c FROM ModerationCase c WHERE c.status = :status AND c.entityType = :entityType",
           countQuery = "SELECT COUNT(c) FROM ModerationCase c WHERE c.status = :status AND c.entityType = :entityType")
    Page<ModerationCase> findByStatusAndEntityType(@Param("status") ModerationStatus status,
                                                   @Param("entityType") ModeratedEntityType entityType,
                                                   Pageable pageable);

    @Query(value = "SELECT c FROM ModerationCase c WHERE c.status = :status AND c.slaBreached = :slaBreached",
           countQuery = "SELECT COUNT(c) FROM ModerationCase c WHERE c.status = :status AND c.slaBreached = :slaBreached")
    Page<ModerationCase> findByStatusAndSlaBreached(@Param("status") ModerationStatus status,
                                                    @Param("slaBreached") boolean slaBreached,
                                                    Pageable pageable);

    long countByStatus(ModerationStatus status);

    long countByStatusAndSlaBreached(ModerationStatus status, boolean slaBreached);

    long countByStatusAndDecidedAtAfter(ModerationStatus status, LocalDateTime after);

    long countBySubmittedAtAfter(LocalDateTime after);

    /** Author history for the review screen — "has this account been here before?" (§12.1). */
    @Query("SELECT COUNT(c) FROM ModerationCase c WHERE c.authorId = :authorId AND c.status = :status")
    long countByAuthorIdAndStatus(@Param("authorId") UUID authorId, @Param("status") ModerationStatus status);

    /** Volume breakdown for the metrics endpoint (§12.5). */
    @Query("""
            SELECT c.entityType, c.status, COUNT(c)
            FROM ModerationCase c
            WHERE c.submittedAt >= :since
            GROUP BY c.entityType, c.status
            """)
    List<Object[]> countByTypeAndStatusSince(@Param("since") LocalDateTime since);

    /** Band breakdown: how much traffic the system decides without a human (§12.5). */
    @Query("""
            SELECT c.decidedBy, c.status, COUNT(c)
            FROM ModerationCase c
            WHERE c.submittedAt >= :since
            GROUP BY c.decidedBy, c.status
            """)
    List<Object[]> countByActorAndStatusSince(@Param("since") LocalDateTime since);

    /** Which label is actually driving verdicts — threshold-tuning signal (§12.5). */
    @Query("""
            SELECT c.maxLabel, COUNT(c), AVG(c.maxScore)
            FROM ModerationCase c
            WHERE c.submittedAt >= :since AND c.maxLabel IS NOT NULL
            GROUP BY c.maxLabel
            """)
    List<Object[]> labelBreakdownSince(@Param("since") LocalDateTime since);

    /** Per-entity-type SLA compliance (§12.5). */
    @Query("""
            SELECT c.entityType, COUNT(c), SUM(CASE WHEN c.slaBreached = true THEN 1 ELSE 0 END)
            FROM ModerationCase c
            WHERE c.submittedAt >= :since
            GROUP BY c.entityType
            """)
    List<Object[]> slaBreakdownSince(@Param("since") LocalDateTime since);

    /** Author-visible status list — "what of mine is still pending?" */
    Page<ModerationCase> findByAuthorIdOrderBySubmittedAtDesc(UUID authorId, Pageable pageable);
}

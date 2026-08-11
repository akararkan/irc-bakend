package ak.dev.irc.app.user.repository;

import ak.dev.irc.app.user.entity.UserContactHash;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface UserContactHashRepository extends JpaRepository<UserContactHash, UUID> {

    /**
     * Users whose registered identity appears in :ownerId's uploaded contacts.
     *
     * <p>Each identity row is gated by the <b>target's own</b> discoverability
     * setting for that channel — an email identity only matches when the target
     * allows {@code byEmail}, a phone identity only when they allow
     * {@code byPhone}. Enforcing it inside the join is what makes the settings
     * screen truthful; a post-filter would still have leaked the count. A user
     * with no settings row falls back to the entity defaults via the
     * {@code d IS NULL} arm. Soft-deleted accounts are excluded so a match count
     * can never confirm a departed account.</p>
     */
    @Query("""
        SELECT DISTINCT ident.ownerId
        FROM UserContactHash mine, UserContactHash ident
        WHERE mine.ownerId = :ownerId AND mine.kind = 'CONTACT'
          AND ident.hash = mine.hash
          AND ident.ownerId <> :ownerId
          AND EXISTS (SELECT 1 FROM User u
                      WHERE u.id = ident.ownerId AND u.deletedAt IS NULL)
          AND ((ident.kind = 'IDENTITY'
                AND NOT EXISTS (SELECT 1 FROM UserDiscoverability d
                                WHERE d.userId = ident.ownerId AND d.byEmail = FALSE))
            OR (ident.kind = 'IDENTITY_PHONE'
                AND NOT EXISTS (SELECT 1 FROM UserDiscoverability d
                                WHERE d.userId = ident.ownerId AND d.byPhone = FALSE)))
        """)
    List<UUID> findMatchedUserIds(@Param("ownerId") UUID ownerId);

    /**
     * Users who uploaded one of :ownerId's identity hashes among THEIR contacts
     * (reverse direction). This arm needs no discoverability gate: it asks
     * "who has me saved", and the answer is only ever combined with the forward
     * match, which is already gated.
     */
    @Query("""
        SELECT DISTINCT theirs.ownerId
        FROM UserContactHash myIdent, UserContactHash theirs
        WHERE myIdent.ownerId = :ownerId
          AND myIdent.kind IN ('IDENTITY', 'IDENTITY_PHONE')
          AND theirs.kind = 'CONTACT'
          AND theirs.hash = myIdent.hash
          AND theirs.ownerId <> :ownerId
          AND EXISTS (SELECT 1 FROM User u
                      WHERE u.id = theirs.ownerId AND u.deletedAt IS NULL)
        """)
    List<UUID> findReverseMatchedUserIds(@Param("ownerId") UUID ownerId);

    /** Identity rows of one kind for a user — the reconcile read. */
    @Query("SELECT h FROM UserContactHash h WHERE h.ownerId = :ownerId AND h.kind = :kind")
    List<UserContactHash> findByOwnerIdAndKind(@Param("ownerId") UUID ownerId,
                                               @Param("kind") String kind);

    @Modifying
    @Query("DELETE FROM UserContactHash h WHERE h.ownerId = :ownerId AND h.kind = :kind")
    void deleteByOwnerIdAndKind(@Param("ownerId") UUID ownerId, @Param("kind") String kind);

    boolean existsByOwnerIdAndKind(UUID ownerId, String kind);

    @Query("SELECT COUNT(h) FROM UserContactHash h WHERE h.ownerId = :ownerId AND h.kind = :kind")
    long countByOwnerIdAndKind(@Param("ownerId") UUID ownerId, @Param("kind") String kind);

    /** Active users still missing their server-side IDENTITY hash (startup backfill). */
    @Query("""
        SELECT u.id, u.email FROM User u
        WHERE u.deletedAt IS NULL
          AND u.id NOT IN (SELECT h.ownerId FROM UserContactHash h WHERE h.kind = 'IDENTITY')
        """)
    List<Object[]> findUsersMissingIdentityHash();

    // ── Admin oversight (docs/admin/discovery-pymk-privacy.md §5.2) ───────

    @Query("SELECT COUNT(h) FROM UserContactHash h WHERE h.kind = :kind")
    long countByKind(@Param("kind") String kind);

    @Query("SELECT COUNT(DISTINCT h.ownerId) FROM UserContactHash h WHERE h.kind = :kind")
    long countDistinctOwnersByKind(@Param("kind") String kind);

    @Query("SELECT DISTINCT h.ownerId FROM UserContactHash h WHERE h.kind = 'CONTACT'")
    List<java.util.UUID> findDistinctContactOwners(org.springframework.data.domain.Pageable pageable);

    /** Owners approaching the 5000-hash cap — harvesting signal. */
    @Query("""
        SELECT h.ownerId, COUNT(h) FROM UserContactHash h
        WHERE h.kind = 'CONTACT'
        GROUP BY h.ownerId HAVING COUNT(h) >= :threshold
        ORDER BY COUNT(h) DESC
        """)
    List<Object[]> ownersNearCap(@Param("threshold") long threshold);
}

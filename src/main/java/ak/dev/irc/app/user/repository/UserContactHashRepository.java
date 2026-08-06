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

    /** Users whose registered identity appears in :ownerId's uploaded contacts. */
    @Query("""
        SELECT DISTINCT ident.ownerId FROM UserContactHash mine, UserContactHash ident
        WHERE mine.ownerId = :ownerId AND mine.kind = 'CONTACT'
          AND ident.kind = 'IDENTITY'
          AND ident.hash = mine.hash
          AND ident.ownerId <> :ownerId
        """)
    List<UUID> findMatchedUserIds(@Param("ownerId") UUID ownerId);

    /** Users who uploaded :ownerId's identity hash among THEIR contacts (reverse direction). */
    @Query("""
        SELECT DISTINCT theirs.ownerId FROM UserContactHash myIdent, UserContactHash theirs
        WHERE myIdent.ownerId = :ownerId AND myIdent.kind = 'IDENTITY'
          AND theirs.kind = 'CONTACT'
          AND theirs.hash = myIdent.hash
          AND theirs.ownerId <> :ownerId
        """)
    List<UUID> findReverseMatchedUserIds(@Param("ownerId") UUID ownerId);

    @Modifying
    @Query("DELETE FROM UserContactHash h WHERE h.ownerId = :ownerId AND h.kind = :kind")
    void deleteByOwnerIdAndKind(@Param("ownerId") UUID ownerId, @Param("kind") String kind);

    boolean existsByOwnerIdAndKind(UUID ownerId, String kind);

    long countByOwnerIdAndKind(UUID ownerId, String kind);

    /** Active users still missing their server-side IDENTITY hash (startup backfill). */
    @Query("""
        SELECT u.id, u.email FROM User u
        WHERE u.deletedAt IS NULL
          AND u.id NOT IN (SELECT h.ownerId FROM UserContactHash h WHERE h.kind = 'IDENTITY')
        """)
    List<Object[]> findUsersMissingIdentityHash();

    // ── Admin oversight (docs/admin/discovery-pymk-privacy.md §5.2) ───────

    long countByKind(String kind);

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

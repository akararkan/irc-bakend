package ak.dev.irc.app.user.repository;

import ak.dev.irc.app.user.entity.CloseFriendsList;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Set;
import java.util.UUID;

@Repository
public interface CloseFriendsRepository
        extends JpaRepository<CloseFriendsList, CloseFriendsList.CloseFriendsId> {

    @Query("SELECT cf FROM CloseFriendsList cf WHERE cf.id.ownerId = :ownerId ORDER BY cf.createdAt DESC")
    Page<CloseFriendsList> findByOwnerId(@Param("ownerId") UUID ownerId, Pageable pageable);

    @Query("SELECT COUNT(cf) > 0 FROM CloseFriendsList cf WHERE cf.id.ownerId = :ownerId AND cf.id.friendId = :friendId")
    boolean existsByIdOwnerIdAndIdFriendId(@Param("ownerId") UUID ownerId, @Param("friendId") UUID friendId);

    /** Set of friend IDs for a given owner — used for story visibility filtering. */
    @Query("SELECT cf.id.friendId FROM CloseFriendsList cf WHERE cf.id.ownerId = :ownerId")
    Set<UUID> findFriendIds(@Param("ownerId") UUID ownerId);

    // ── Admin analytics (users-roles.md §6.1) ─────────────────────────────

    /** Users with at least one close friend — the adoption numerator. */
    @Query("SELECT COUNT(DISTINCT cf.id.ownerId) FROM CloseFriendsList cf")
    long countDistinctOwners();

    @Query("SELECT COUNT(cf) FROM CloseFriendsList cf")
    long countEdges();

    /** List-size distribution, bucketed in SQL so one row per bucket comes back. */
    @Query(value = """
        SELECT b.bucket, COUNT(*) FROM (
            SELECT CASE WHEN COUNT(*) <= 5  THEN '1-5'
                        WHEN COUNT(*) <= 15 THEN '6-15'
                        WHEN COUNT(*) <= 50 THEN '16-50'
                        ELSE '50+' END AS bucket
            FROM close_friends GROUP BY owner_id) b
        GROUP BY b.bucket ORDER BY b.bucket
        """, nativeQuery = true)
    java.util.List<Object[]> sizeHistogram();
}

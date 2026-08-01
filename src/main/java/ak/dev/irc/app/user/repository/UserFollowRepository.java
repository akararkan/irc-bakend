package ak.dev.irc.app.user.repository;

import ak.dev.irc.app.user.entity.UserFollow;
import ak.dev.irc.app.user.entity.UserFollowId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface UserFollowRepository extends JpaRepository<UserFollow, UserFollowId> {

    /**
     * All followers of a given user — User AND profile fetched in the same
     * query. The profile join matters: {@code User.profile} is a non-owning
     * (mappedBy) 1:1 that Hibernate cannot proxy, so without the fetch every
     * row on the followers page fires its own profile SELECT when the mapper
     * reads the avatar. Explicit countQuery: Spring Data cannot derive a
     * count from a fetch-join query.
     */
    @Query(value = """
        SELECT uf FROM UserFollow uf
        JOIN FETCH uf.follower f
        LEFT JOIN FETCH f.profile
        WHERE uf.following.id = :userId
          AND f.deletedAt IS NULL
        ORDER BY uf.followedAt DESC
        """,
        countQuery = """
        SELECT COUNT(uf) FROM UserFollow uf
        WHERE uf.following.id = :userId
          AND uf.follower.deletedAt IS NULL
        """)
    Page<UserFollow> findFollowers(@Param("userId") UUID userId, Pageable pageable);

    /** All users that a given user follows — User + profile fetched (see findFollowers). */
    @Query(value = """
        SELECT uf FROM UserFollow uf
        JOIN FETCH uf.following f
        LEFT JOIN FETCH f.profile
        WHERE uf.follower.id = :userId
          AND f.deletedAt IS NULL
        ORDER BY uf.followedAt DESC
        """,
        countQuery = """
        SELECT COUNT(uf) FROM UserFollow uf
        WHERE uf.follower.id = :userId
          AND uf.following.deletedAt IS NULL
        """)
    Page<UserFollow> findFollowing(@Param("userId") UUID userId, Pageable pageable);

    long countByFollowingId(UUID userId);

    long countByFollowerId(UUID userId);

    @Query("""
        SELECT COUNT(uf) > 0 FROM UserFollow uf
        WHERE uf.follower.id  = :followerId
          AND uf.following.id = :followingId
        """)
    boolean isFollowing(@Param("followerId")  UUID followerId,
                        @Param("followingId") UUID followingId);

    /**
     * Returns just the follower UUIDs for a given user — used by the
     * RabbitMQ fan-out consumer to notify followers without loading full entities.
     * Pageable lets the consumer work in batches (e.g. first 500 followers).
     */
    @Query("""
        SELECT uf.follower.id FROM UserFollow uf
        WHERE uf.following.id = :userId
          AND uf.follower.deletedAt IS NULL
        ORDER BY uf.followedAt DESC, uf.follower.id
        """)
    List<UUID> findFollowerIds(@Param("userId") UUID userId, Pageable pageable);

    /**
     * Returns the UUIDs of all users that a given user follows — used by
     * following-based feeds to fetch content from followed users.
     */
    @Query("""
        SELECT uf.following.id FROM UserFollow uf
        WHERE uf.follower.id = :userId
          AND uf.following.deletedAt IS NULL
        """)
    List<UUID> findFollowingIds(@Param("userId") UUID userId);

    /**
     * All follower IDs for story fan-out — capped to avoid OOM on viral accounts.
     * The SSE fan-out uses this; late followers will see the story on next tray load.
     */
    @Query("""
        SELECT uf.follower.id FROM UserFollow uf
        WHERE uf.following.id = :userId
          AND uf.follower.deletedAt IS NULL
        ORDER BY uf.followedAt DESC
        """)
    List<UUID> findAllFollowerIds(@Param("userId") UUID userId, Pageable pageable);

    /**
     * Keyset-paginated follower scan for home-feed fan-out. Returns the next
     * page of follower IDs strictly greater than {@code afterId} (pass
     * {@code null} for the first page), ordered by {@code follower.id ASC}.
     *
     * <p>Why not {@link #findAllFollowerIds(UUID, Pageable)}? That call uses
     * offset pagination ({@code OFFSET N LIMIT M}). At page 100 it scans
     * 50,000 rows just to skip them. Keyset on the indexed follower id is
     * constant-time per page regardless of depth, so a viral account's
     * fan-out doesn't quadratically slow down as we walk through followers.</p>
     *
     * <p>Order does not match {@link #findAllFollowerIds} (this orders by id,
     * the other by followedAt DESC). For fan-out the visit order doesn't
     * matter — we just need every follower exactly once.</p>
     */
    @Query("""
        SELECT uf.follower.id FROM UserFollow uf
        WHERE uf.following.id = :userId
          AND uf.follower.deletedAt IS NULL
          AND (:afterId IS NULL OR uf.follower.id > :afterId)
        ORDER BY uf.follower.id ASC
        """)
    List<UUID> findFollowerIdsAfter(@Param("userId") UUID userId,
                                    @Param("afterId") UUID afterId,
                                    Pageable pageable);

    /**
     * "Which of these users follow me?" — bulk mutual-follow probe for the
     * feed ranker. One round-trip for a whole page of authors; intersect the
     * result with the viewer's following set to get true mutuals.
     */
    @Query("""
        SELECT uf.follower.id FROM UserFollow uf
        WHERE uf.following.id = :userId
          AND uf.follower.id IN :candidateIds
        """)
    List<UUID> findFollowerIdsAmong(@Param("userId") UUID userId,
                                    @Param("candidateIds") java.util.Collection<UUID> candidateIds);

    /** Delete all follow rows between two users in both directions */
    @Modifying
    @Query("""
        DELETE FROM UserFollow uf
        WHERE (uf.follower.id = :a AND uf.following.id = :b)
           OR (uf.follower.id = :b AND uf.following.id = :a)
        """)
    void deleteAllBetween(@Param("a") UUID a, @Param("b") UUID b);
}

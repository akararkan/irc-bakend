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

    /** All followers of a given user (with their User entity fetched) */
    @Query("""
        SELECT uf FROM UserFollow uf
        JOIN FETCH uf.follower f
        WHERE uf.following.id = :userId
          AND f.deletedAt IS NULL
        ORDER BY uf.followedAt DESC
        """)
    Page<UserFollow> findFollowers(@Param("userId") UUID userId, Pageable pageable);

    /** All users that a given user follows (with their User entity fetched) */
    @Query("""
        SELECT uf FROM UserFollow uf
        JOIN FETCH uf.following f
        WHERE uf.follower.id = :userId
          AND f.deletedAt IS NULL
        ORDER BY uf.followedAt DESC
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

    /** Delete all follow rows between two users in both directions */
    @Modifying
    @Query("""
        DELETE FROM UserFollow uf
        WHERE (uf.follower.id = :a AND uf.following.id = :b)
           OR (uf.follower.id = :b AND uf.following.id = :a)
        """)
    void deleteAllBetween(@Param("a") UUID a, @Param("b") UUID b);
}

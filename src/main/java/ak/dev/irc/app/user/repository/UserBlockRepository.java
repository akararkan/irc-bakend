package ak.dev.irc.app.user.repository;

import ak.dev.irc.app.user.entity.UserBlock;
import ak.dev.irc.app.user.entity.UserBlockId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface UserBlockRepository extends JpaRepository<UserBlock, UserBlockId> {

    @Query("""
        SELECT COUNT(ub) > 0 FROM UserBlock ub
        WHERE ub.blocker.id = :blockerId
          AND ub.blocked.id = :blockedId
        """)
    boolean isBlocking(@Param("blockerId") UUID blockerId,
                       @Param("blockedId") UUID blockedId);

    /** True if either user has blocked the other */
    @Query("""
        SELECT COUNT(ub) > 0 FROM UserBlock ub
        WHERE (ub.blocker.id = :a AND ub.blocked.id = :b)
           OR (ub.blocker.id = :b AND ub.blocked.id = :a)
        """)
    boolean isBlockedBetween(@Param("a") UUID a, @Param("b") UUID b);

    @Query("""
        SELECT ub FROM UserBlock ub
        JOIN FETCH ub.blocked u
        WHERE ub.blocker.id = :userId
          AND u.deletedAt IS NULL
        """)
    Page<UserBlock> findBlockedUsers(@Param("userId") UUID userId, Pageable pageable);

    /**
     * Returns the IDs of every user that has any block relationship with :userId
     * (either direction). Single round-trip — caller can filter feed queries
     * without per-row block checks.
     */
    @Query("""
        SELECT CASE WHEN ub.blocker.id = :userId THEN ub.blocked.id ELSE ub.blocker.id END
        FROM UserBlock ub
        WHERE ub.blocker.id = :userId OR ub.blocked.id = :userId
        """)
    List<UUID> findAllRelatedBlockedIds(@Param("userId") UUID userId);

    /**
     * Returns the subset of :candidateIds that are in any block relationship with :userId.
     * Used to filter a known set of follow-target IDs in one DB call.
     */
    @Query("""
        SELECT CASE WHEN ub.blocker.id = :userId THEN ub.blocked.id ELSE ub.blocker.id END
        FROM UserBlock ub
        WHERE (ub.blocker.id = :userId AND ub.blocked.id IN :candidateIds)
           OR (ub.blocked.id = :userId AND ub.blocker.id IN :candidateIds)
        """)
    List<UUID> findBlockedAmong(@Param("userId") UUID userId,
                                @Param("candidateIds") Collection<UUID> candidateIds);
}

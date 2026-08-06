package ak.dev.irc.app.user.repository;

import ak.dev.irc.app.user.entity.UserRestriction;
import ak.dev.irc.app.user.entity.UserRestrictionId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface UserRestrictionRepository
        extends JpaRepository<UserRestriction, UserRestrictionId> {

    @Query("""
        SELECT COUNT(ur) > 0 FROM UserRestriction ur
        WHERE ur.restrictor.id = :restrictorId
          AND ur.restricted.id  = :restrictedId
        """)
    boolean isRestricting(@Param("restrictorId") UUID restrictorId,
                          @Param("restrictedId")  UUID restrictedId);

    @Query(value = """
        SELECT ur FROM UserRestriction ur
        JOIN FETCH ur.restricted u
        LEFT JOIN FETCH u.profile
        WHERE ur.restrictor.id = :userId
          AND u.deletedAt IS NULL
        """,
        countQuery = """
        SELECT COUNT(ur) FROM UserRestriction ur
        WHERE ur.restrictor.id = :userId
          AND ur.restricted.deletedAt IS NULL
        """)
    Page<UserRestriction> findRestrictedUsers(@Param("userId") UUID userId, Pageable pageable);

    /**
     * The subset of :candidateIds with a restriction relationship (either
     * direction) with :userId — bulk negative-signal filter for friend
     * suggestions, mirroring {@code UserBlockRepository.findBlockedAmong}.
     */
    @Query("""
        SELECT CASE WHEN ur.restrictor.id = :userId THEN ur.restricted.id ELSE ur.restrictor.id END
        FROM UserRestriction ur
        WHERE (ur.restrictor.id = :userId AND ur.restricted.id IN :candidateIds)
           OR (ur.restricted.id = :userId AND ur.restrictor.id IN :candidateIds)
        """)
    java.util.List<UUID> findRestrictedAmong(@Param("userId") UUID userId,
                                             @Param("candidateIds") java.util.Collection<UUID> candidateIds);

    @Query(value = "SELECT COUNT(*) FROM user_restrictions", nativeQuery = true)
    long countAllRestrictions();

    @Query(value = """
            SELECT CAST(date_trunc('day', r.restricted_at) AS date), COUNT(*)
            FROM user_restrictions r WHERE r.restricted_at >= :from
            GROUP BY 1 ORDER BY 1
            """, nativeQuery = true)
    java.util.List<Object[]> restrictionsPerDay(@Param("from") java.time.LocalDateTime from);
}

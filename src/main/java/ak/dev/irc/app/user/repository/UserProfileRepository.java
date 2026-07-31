package ak.dev.irc.app.user.repository;

import ak.dev.irc.app.user.entity.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {

    @Query("SELECT p FROM UserProfile p WHERE p.user.id = :userId")
    Optional<UserProfile> findByUserId(@Param("userId") UUID userId);

    @Query("SELECT p FROM UserProfile p LEFT JOIN FETCH p.specializations s LEFT JOIN FETCH s.topic WHERE p.user.id = :userId")
    Optional<UserProfile> findByUserIdWithSpecializations(@Param("userId") UUID userId);

    /** Bulk-load profiles by user IDs — used by suggestion hydration to avoid N+1. */
    @Query("SELECT p FROM UserProfile p WHERE p.user.id IN :userIds")
    List<UserProfile> findAllByUserIdIn(@Param("userIds") java.util.Collection<UUID> userIds);

    /**
     * Bulk profile load with specializations + topics fetched — used by the
     * friend-suggestion scorer for interest-overlap without N+1 lazy loads.
     */
    @Query("""
        SELECT DISTINCT p FROM UserProfile p
        LEFT JOIN FETCH p.specializations s
        LEFT JOIN FETCH s.topic
        WHERE p.user.id IN :userIds
        """)
    List<UserProfile> findAllByUserIdInWithSpecializations(
            @Param("userIds") java.util.Collection<UUID> userIds);

    /**
     * Colleagues: active users sharing the caller's institution — the
     * workplace/education candidate source for friend suggestions.
     */
    @Query("""
        SELECT p.user.id FROM UserProfile p
        WHERE p.user.id <> :me
          AND p.user.deletedAt IS NULL
          AND p.institutionName IS NOT NULL
          AND LOWER(p.institutionName) = LOWER(:institution)
        """)
    List<UUID> findUserIdsByInstitution(@Param("institution") String institution,
                                        @Param("me") UUID me,
                                        org.springframework.data.domain.Pageable pageable);
}

package ak.dev.irc.app.user.repository;

import ak.dev.irc.app.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    Optional<User> findByUsername(String username);

    /** Finds a non-deleted user by email — use for profile lookups and auth. */
    Optional<User> findByEmailAndDeletedAtIsNull(String email);

    /** Finds a non-deleted user by username — use for profile lookups and auth. */
    Optional<User> findByUsernameAndDeletedAtIsNull(String username);

    boolean existsByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByUsernameAndDeletedAtIsNull(String username);

    @Query("""
        SELECT u FROM User u
        WHERE u.deletedAt IS NULL
          AND (LOWER(u.fname)    LIKE LOWER(CONCAT('%', :q, '%'))
            OR LOWER(u.lname)    LIKE LOWER(CONCAT('%', :q, '%'))
            OR LOWER(u.username) LIKE LOWER(CONCAT('%', :q, '%'))
            OR LOWER(u.email)    LIKE LOWER(CONCAT('%', :q, '%')))
        """)
    Page<User> searchUsers(@Param("q") String query, Pageable pageable);

    /**
     * Indexed full-text search across username + fname + lname + bio. Uses
     * the GIN function index installed by {@code SearchInfrastructureInitializer}.
     */
    @Query(value = """
        SELECT u.id, ts_rank_cd(to_tsvector('simple',
                  coalesce(u.username,'') || ' ' || coalesce(u.fname,'') || ' ' ||
                  coalesce(u.lname,'')    || ' ' || coalesce(u.profile_bio,'')),
                websearch_to_tsquery('simple', :q)) AS score
        FROM users u
        WHERE u.deleted_at IS NULL
          AND to_tsvector('simple',
                  coalesce(u.username,'') || ' ' || coalesce(u.fname,'') || ' ' ||
                  coalesce(u.lname,'')    || ' ' || coalesce(u.profile_bio,''))
              @@ websearch_to_tsquery('simple', :q)
        ORDER BY score DESC, u.created_at DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> searchUsersFts(@Param("q") String q, @Param("limit") int limit);

    /**
     * Trigram fuzzy fallback — typo-tolerant. Uses {@code gin_trgm_ops}
     * indexes so partial / misspelled names still come back fast.
     */
    @Query(value = """
        SELECT u.id, GREATEST(similarity(coalesce(u.username,''), :q),
                              similarity(coalesce(u.fname,''),    :q),
                              similarity(coalesce(u.lname,''),    :q)) AS score
        FROM users u
        WHERE u.deleted_at IS NULL
          AND (coalesce(u.username,'') %% :q
            OR coalesce(u.fname,'')    %% :q
            OR coalesce(u.lname,'')    %% :q)
        ORDER BY score DESC, u.created_at DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> searchUsersTrgm(@Param("q") String q, @Param("limit") int limit);

    /**
     * Mention-picker autocomplete — optimised for keystroke-by-keystroke
     * typing. Prefix matches on username win first (Twitter / Slack style),
     * then prefix matches on first/last name, and finally trigram-similar
     * fallbacks pick up typos. Trigram + LIKE both ride the existing GIN
     * indexes from {@code SearchInfrastructureInitializer}.
     */
    @Query(value = """
        SELECT u.id,
               CASE WHEN LOWER(u.username) LIKE LOWER(:q || '%') THEN 3.0
                    WHEN LOWER(u.fname)    LIKE LOWER(:q || '%') THEN 2.0
                    WHEN LOWER(u.lname)    LIKE LOWER(:q || '%') THEN 2.0
                    ELSE GREATEST(similarity(coalesce(u.username,''), :q),
                                  similarity(coalesce(u.fname,''),    :q),
                                  similarity(coalesce(u.lname,''),    :q))
               END AS score
        FROM users u
        WHERE u.deleted_at IS NULL
          AND (LOWER(u.username) LIKE LOWER(:q || '%')
            OR LOWER(u.fname)    LIKE LOWER(:q || '%')
            OR LOWER(u.lname)    LIKE LOWER(:q || '%')
            OR coalesce(u.username,'') %% :q
            OR coalesce(u.fname,'')    %% :q
            OR coalesce(u.lname,'')    %% :q)
        ORDER BY score DESC, length(u.username) ASC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findMentionCandidates(@Param("q") String q, @Param("limit") int limit);

    @Query("SELECT u FROM User u WHERE u.deletedAt IS NULL")
    Page<User> findAllActive(Pageable pageable);

        @Query("""
                SELECT u FROM User u
                WHERE u.deletedAt IS NULL
                    AND u.role IN :roles
                ORDER BY u.createdAt DESC, u.id ASC
                """)
        Page<User> findActiveByRoles(@Param("roles") Collection<ak.dev.irc.app.user.enums.Role> roles,
                                                                 Pageable pageable);

    @Query("SELECT u FROM User u WHERE u.id = :id AND u.deletedAt IS NULL")
    Optional<User> findActiveById(@Param("id") UUID id);

    /**
     * Batch resolve a set of usernames to {id, username} tuples.
     * Used by the mention extractor to turn @-handles into recipients in one
     * round trip. Usernames are case-insensitive — extractor lower-cases them
     * before passing in.
     */
    @Query("""
        SELECT u FROM User u
        WHERE u.deletedAt IS NULL
          AND LOWER(u.username) IN :usernames
        """)
    List<User> findActiveByUsernameIn(@Param("usernames") Collection<String> usernames);

    /** Batch fetch active users by id — single round trip for the consumer side. */
    @Query("""
        SELECT u FROM User u
        WHERE u.id IN :ids
          AND u.deletedAt IS NULL
        """)
    List<User> findActiveByIdIn(@Param("ids") Collection<UUID> ids);

    /**
     * Slim projection for the email pipeline — just enough fields to decide
     * whether to send and where. Avoids loading the full User entity (with
     * all its {@code @ManyToOne}/{@code @OneToMany} fields and the audit
     * envelope) on every notification.
     */
    @Query("""
        SELECT new ak.dev.irc.app.email.UserEmailContext(
            u.id, u.email,
            CONCAT(COALESCE(u.fname, ''), ' ', COALESCE(u.lname, '')),
            u.emailNotificationsEnabled,
            u.emailSocialEnabled,
            u.emailMentionsEnabled,
            u.emailSystemEnabled)
        FROM User u
        WHERE u.id = :id AND u.deletedAt IS NULL
        """)
    Optional<ak.dev.irc.app.email.UserEmailContext> findEmailContextById(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE User u SET u.lastLoginAt = CURRENT_TIMESTAMP WHERE u.id = :id")
    void updateLastLogin(@Param("id") UUID id);

    @Modifying
    @Query("""
        UPDATE User u
        SET u.deletedAt = CURRENT_TIMESTAMP, u.isEnabled = false
        WHERE u.id = :id
        """)
    void softDelete(@Param("id") UUID id);
}

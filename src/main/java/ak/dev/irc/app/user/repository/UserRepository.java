package ak.dev.irc.app.user.repository;

import ak.dev.irc.app.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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

package ak.dev.irc.app.admin.analytics;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.UUID;

public interface UserFirstEventsRepository extends JpaRepository<UserFirstEvents, UUID> {

    @Query("""
        SELECT COUNT(f) FROM UserFirstEvents f
        WHERE f.userId IN :cohort AND f.firstSeen IS NOT NULL
        """)
    long countFirstSeen(@Param("cohort") Collection<UUID> cohort);

    @Query("""
        SELECT COUNT(f) FROM UserFirstEvents f
        WHERE f.userId IN :cohort AND f.profileCompletedAt IS NOT NULL
        """)
    long countProfileCompleted(@Param("cohort") Collection<UUID> cohort);

    @Query("""
        SELECT COUNT(f) FROM UserFirstEvents f
        WHERE f.userId IN :cohort AND f.firstFollowAt IS NOT NULL
        """)
    long countFirstFollow(@Param("cohort") Collection<UUID> cohort);

    @Query("""
        SELECT COUNT(f) FROM UserFirstEvents f
        WHERE f.userId IN :cohort AND f.firstContentAt IS NOT NULL
        """)
    long countFirstContent(@Param("cohort") Collection<UUID> cohort);
}

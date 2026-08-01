package ak.dev.irc.app.security.login.repository;

import ak.dev.irc.app.security.login.entity.LoginEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LoginEventRepository extends JpaRepository<LoginEvent, UUID> {

    Page<LoginEvent> findByUserIdOrderByTsDesc(UUID userId, Pageable pageable);

    /** Distinct IPs this user has succeeded from — used to spot a new device/location. */
    @Query("""
        SELECT DISTINCT e.ip FROM LoginEvent e
        WHERE e.userId = :userId AND e.outcome = 'SUCCESS' AND e.ip IS NOT NULL
        """)
    List<String> distinctSuccessfulIps(@Param("userId") UUID userId);
}

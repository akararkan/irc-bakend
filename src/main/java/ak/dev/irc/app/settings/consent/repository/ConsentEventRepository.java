package ak.dev.irc.app.settings.consent.repository;

import ak.dev.irc.app.settings.consent.entity.ConsentEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConsentEventRepository extends JpaRepository<ConsentEvent, UUID> {

    Page<ConsentEvent> findByUserIdOrderByOccurredAtDesc(UUID userId, Pageable pageable);

    Optional<ConsentEvent> findFirstByUserIdAndScopeOrderByOccurredAtDesc(UUID userId, String scope);
}

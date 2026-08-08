package ak.dev.irc.app.moderation.repository;

import ak.dev.irc.app.moderation.entity.ModerationGoldenCase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ModerationGoldenCaseRepository extends JpaRepository<ModerationGoldenCase, UUID> {

    Optional<ModerationGoldenCase> findByTextHash(String textHash);

    Page<ModerationGoldenCase> findAllByOrderByAddedAtDesc(Pageable pageable);
}

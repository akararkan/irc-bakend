package ak.dev.irc.app.admin.moderation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlatformKeywordRepository extends JpaRepository<PlatformKeyword, UUID> {

    Optional<PlatformKeyword> findByKeywordNormalized(String keywordNormalized);

    List<PlatformKeyword> findBySeverity(PlatformKeyword.Severity severity);
}

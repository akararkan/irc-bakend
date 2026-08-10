package ak.dev.irc.app.admin.moderation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlatformKeywordRepository extends JpaRepository<PlatformKeyword, UUID> {

    /** Exact lookup by the normalized (lowercased, trimmed) form. */
    @Query("SELECT k FROM PlatformKeyword k WHERE k.keywordNormalized = :normalized")
    Optional<PlatformKeyword> byNormalized(@Param("normalized") String normalized);

    @Query("SELECT k FROM PlatformKeyword k WHERE k.severity = :severity")
    List<PlatformKeyword> bySeverity(@Param("severity") PlatformKeyword.Severity severity);
}

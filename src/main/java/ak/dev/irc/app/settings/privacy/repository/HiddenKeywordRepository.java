package ak.dev.irc.app.settings.privacy.repository;

import ak.dev.irc.app.settings.privacy.entity.HiddenKeyword;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface HiddenKeywordRepository extends JpaRepository<HiddenKeyword, UUID> {

    List<HiddenKeyword> findByUserIdOrderByCreatedAtDesc(UUID userId);

    boolean existsByUserIdAndKeywordNormalized(UUID userId, String keywordNormalized);

    @Query("SELECT k.keywordNormalized FROM HiddenKeyword k WHERE k.userId = :userId")
    List<String> findNormalizedByUser(@Param("userId") UUID userId);

    long deleteByIdAndUserId(UUID id, UUID userId);
}

package ak.dev.irc.app.common.tag.repository;

import ak.dev.irc.app.common.tag.entity.TrendingTagOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TrendingTagOverrideRepository extends JpaRepository<TrendingTagOverride, UUID> {

    @Query("SELECT o FROM TrendingTagOverride o WHERE o.revokedAt IS NULL")
    List<TrendingTagOverride> findByRevokedAtIsNull();

    List<TrendingTagOverride> findByTagNormalizedAndRevokedAtIsNull(String tagNormalized);
}

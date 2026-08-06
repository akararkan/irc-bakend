package ak.dev.irc.app.common.tag.repository;

import ak.dev.irc.app.common.tag.entity.TrendingTagOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TrendingTagOverrideRepository extends JpaRepository<TrendingTagOverride, UUID> {

    List<TrendingTagOverride> findByRevokedAtIsNull();

    List<TrendingTagOverride> findByTagNormalizedAndRevokedAtIsNull(String tagNormalized);
}

package ak.dev.irc.app.research.repository;

import ak.dev.irc.app.research.entity.ResearchTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ResearchTagRepository extends JpaRepository<ResearchTag, UUID> {

    void deleteAllByResearchId(UUID researchId);

    /** Trending tags: most used in published researches */
    @Query("""
        SELECT t.tagName, COUNT(t) AS cnt
        FROM ResearchTag t
        JOIN t.research r
        WHERE r.status = 'PUBLISHED' AND r.deletedAt IS NULL
        GROUP BY t.tagName
        ORDER BY cnt DESC
    """)
    List<Object[]> findTrendingTags(org.springframework.data.domain.Pageable pageable);
}

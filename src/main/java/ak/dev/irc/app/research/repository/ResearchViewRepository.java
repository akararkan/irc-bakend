package ak.dev.irc.app.research.repository;

import ak.dev.irc.app.research.entity.ResearchView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Repository
public interface ResearchViewRepository extends JpaRepository<ResearchView, ResearchView.ResearchViewId> {

    /**
     * Atomic claim-this-pair-or-skip primitive — see
     * {@code PostViewRepository.tryRecord} for the contract.
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO research_views (research_id, user_id, first_viewed_at)
        VALUES (:researchId, :userId, NOW())
        ON CONFLICT (research_id, user_id) DO NOTHING
        """, nativeQuery = true)
    int tryRecord(@Param("researchId") UUID researchId, @Param("userId") UUID userId);

    @Query("SELECT COUNT(v) FROM ResearchView v WHERE v.id.researchId = :researchId")
    long countByResearchId(@Param("researchId") UUID researchId);

    boolean existsByIdResearchIdAndIdUserId(UUID researchId, UUID userId);
}

package ak.dev.irc.app.research.repository;

import ak.dev.irc.app.research.entity.ResearchContributor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ResearchContributorRepository
        extends JpaRepository<ResearchContributor, UUID> {

    List<ResearchContributor> findByResearchIdOrderByDisplayOrderAsc(UUID researchId);

    Optional<ResearchContributor> findByResearchIdAndUserId(UUID researchId, UUID userId);

    boolean existsByResearchIdAndUserId(UUID researchId, UUID userId);

    long countByResearchId(UUID researchId);

    @Modifying
    @Query("DELETE FROM ResearchContributor c WHERE c.research.id = :researchId")
    void deleteAllByResearchId(@Param("researchId") UUID researchId);

    @Modifying
    @Query("DELETE FROM ResearchContributor c WHERE c.research.id = :researchId AND c.user.id = :userId")
    int deleteByResearchIdAndUserId(@Param("researchId") UUID researchId,
                                    @Param("userId") UUID userId);

    /** All researches a given user is listed as a contributor on. */
    @Query("SELECT c FROM ResearchContributor c WHERE c.user.id = :userId ORDER BY c.createdAt DESC")
    List<ResearchContributor> findByUserId(@Param("userId") UUID userId);
}

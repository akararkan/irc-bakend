package ak.dev.irc.app.research.repository;

import ak.dev.irc.app.research.entity.ResearchSave;
import ak.dev.irc.app.research.entity.ResearchSaveId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Modifying;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public interface ResearchSaveRepository extends JpaRepository<ResearchSave, ResearchSaveId> {

    boolean existsById(ResearchSaveId id);

    /** All saved researches for a user (their "library") */
    Page<ResearchSave> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /** Saved researches filtered by collection name */
    Page<ResearchSave> findByUserIdAndCollectionNameOrderByCreatedAtDesc(
            UUID userId, String collectionName, Pageable pageable);

    /** All distinct collection names for a user — for building the collections sidebar */
    @Query("""
        SELECT DISTINCT s.collectionName FROM ResearchSave s
        WHERE s.user.id = :userId
        ORDER BY s.collectionName ASC
    """)
    List<String> findDistinctCollectionNamesByUserId(@Param("userId") UUID userId);

    /** Batch lookup so feed renders can mark currentUserSaved per research without N+1 round trips. */
    @Query("""
        SELECT s.id.researchId FROM ResearchSave s
        WHERE s.user.id = :userId AND s.id.researchId IN :researchIds
    """)
    Set<UUID> findSavedResearchIds(@Param("userId") UUID userId,
                                   @Param("researchIds") List<UUID> researchIds);

    long countByResearchId(UUID researchId);

    @Modifying
    @Query("""
        UPDATE ResearchSave s SET s.collectionName = :newName
        WHERE s.user.id = :userId AND s.collectionName = :oldName
        """)
    int renameCollection(@Param("userId") UUID userId,
                         @Param("oldName") String oldName,
                         @Param("newName") String newName);

    /** Cascade purge — used when the parent research is hard-deleted. */
    @Modifying
    @Query("DELETE FROM ResearchSave s WHERE s.id.researchId = :researchId")
    int deleteAllByResearchId(@Param("researchId") UUID researchId);

    /** All user IDs that have saved this research — needed to clean their Cassandra save lists. */
    @Query("SELECT s.user.id FROM ResearchSave s WHERE s.id.researchId = :researchId")
    List<UUID> findUserIdsByResearchId(@Param("researchId") UUID researchId);
}

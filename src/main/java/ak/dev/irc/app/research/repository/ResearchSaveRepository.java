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

    long countByResearchId(UUID researchId);

    @Modifying
    @Query("""
        UPDATE ResearchSave s SET s.collectionName = :newName
        WHERE s.user.id = :userId AND s.collectionName = :oldName
        """)
    int renameCollection(@Param("userId") UUID userId,
                         @Param("oldName") String oldName,
                         @Param("newName") String newName);
}

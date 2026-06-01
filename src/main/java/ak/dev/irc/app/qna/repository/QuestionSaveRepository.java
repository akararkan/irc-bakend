package ak.dev.irc.app.qna.repository;

import ak.dev.irc.app.qna.entity.QuestionSave;
import ak.dev.irc.app.qna.entity.QuestionSaveId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public interface QuestionSaveRepository extends JpaRepository<QuestionSave, QuestionSaveId> {

    boolean existsById(QuestionSaveId id);

    Page<QuestionSave> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<QuestionSave> findByUserIdAndCollectionNameOrderByCreatedAtDesc(
            UUID userId, String collectionName, Pageable pageable);

    @Query("""
        SELECT DISTINCT s.collectionName FROM QuestionSave s
        WHERE s.user.id = :userId
        ORDER BY s.collectionName ASC
    """)
    List<String> findDistinctCollectionNamesByUserId(@Param("userId") UUID userId);

    /** Batch lookup so feed renders can mark isSaved per question without N+1 round trips. */
    @Query("""
        SELECT s.id.questionId FROM QuestionSave s
        WHERE s.user.id = :userId AND s.id.questionId IN :questionIds
    """)
    Set<UUID> findSavedQuestionIds(@Param("userId") UUID userId,
                                   @Param("questionIds") List<UUID> questionIds);

    @Modifying
    @Query("""
        UPDATE QuestionSave s SET s.collectionName = :newName
        WHERE s.user.id = :userId AND s.collectionName = :oldName
    """)
    int renameCollection(@Param("userId") UUID userId,
                         @Param("oldName") String oldName,
                         @Param("newName") String newName);

    /** Source-of-truth count for the reconciler to rebuild {@code question.saveCount}. */
    @Query("SELECT COUNT(s) FROM QuestionSave s WHERE s.id.questionId = :questionId")
    long countByQuestionId(@Param("questionId") UUID questionId);

    /** Cascade purge — used when the parent question is hard-deleted. */
    @Modifying
    @Query("DELETE FROM QuestionSave s WHERE s.id.questionId = :questionId")
    int deleteAllByQuestionId(@Param("questionId") UUID questionId);
}

package ak.dev.irc.app.qna.repository;

import ak.dev.irc.app.qna.entity.QuestionView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Repository
public interface QuestionViewRepository extends JpaRepository<QuestionView, QuestionView.QuestionViewId> {

    /**
     * Atomic claim-this-pair-or-skip primitive — see
     * {@code PostViewRepository.tryRecord} for the contract.
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO question_views (question_id, user_id, first_viewed_at)
        VALUES (:questionId, :userId, NOW())
        ON CONFLICT (question_id, user_id) DO NOTHING
        """, nativeQuery = true)
    int tryRecord(@Param("questionId") UUID questionId, @Param("userId") UUID userId);

    @Query("SELECT COUNT(v) FROM QuestionView v WHERE v.id.questionId = :questionId")
    long countByQuestionId(@Param("questionId") UUID questionId);

    boolean existsByIdQuestionIdAndIdUserId(UUID questionId, UUID userId);

    /** Cascade purge — used when the parent question is hard-deleted. */
    @Modifying
    @Query("DELETE FROM QuestionView v WHERE v.id.questionId = :questionId")
    int deleteAllByQuestionId(@Param("questionId") UUID questionId);
}

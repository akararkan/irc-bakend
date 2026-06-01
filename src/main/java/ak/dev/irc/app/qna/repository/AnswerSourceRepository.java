package ak.dev.irc.app.qna.repository;

import ak.dev.irc.app.qna.entity.AnswerSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AnswerSourceRepository extends JpaRepository<AnswerSource, UUID> {

    List<AnswerSource> findByAnswerIdOrderByDisplayOrderAsc(UUID answerId);

    void deleteAllByAnswerId(UUID answerId);

    /** Cascade purge — used when the parent question is hard-deleted. */
    @Modifying
    @Query("DELETE FROM AnswerSource s WHERE s.answer.question.id = :questionId")
    int deleteAllByQuestionId(@Param("questionId") UUID questionId);

    /** S3 keys for cascade-cleanup before the rows themselves are deleted. */
    @Query("""
        SELECT s.s3Key FROM AnswerSource s
        WHERE s.answer.question.id = :questionId AND s.s3Key IS NOT NULL
        """)
    List<String> findS3KeysByQuestionId(@Param("questionId") UUID questionId);
}

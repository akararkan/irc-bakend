package ak.dev.irc.app.qna.repository;

import ak.dev.irc.app.qna.entity.AnswerAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AnswerAttachmentRepository extends JpaRepository<AnswerAttachment, UUID> {

    List<AnswerAttachment> findByAnswerIdOrderByDisplayOrderAsc(UUID answerId);

    void deleteAllByAnswerId(UUID answerId);

    /** S3 keys for cascade-cleanup before the rows themselves are deleted. */
    @Query("""
        SELECT a.s3Key FROM AnswerAttachment a
        WHERE a.answer.question.id = :questionId
        """)
    List<String> findS3KeysByQuestionId(@Param("questionId") UUID questionId);

    /** Cascade purge — used when the parent question is hard-deleted. */
    @Modifying
    @Query("DELETE FROM AnswerAttachment a WHERE a.answer.question.id = :questionId")
    int deleteAllByQuestionId(@Param("questionId") UUID questionId);
}

package ak.dev.irc.app.qna.repository;

import ak.dev.irc.app.qna.entity.QuestionAnswer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface QuestionAnswerRepository extends JpaRepository<QuestionAnswer, UUID> {

    Page<QuestionAnswer> findByQuestionIdAndDeletedAtIsNullOrderByCreatedAtAsc(UUID questionId, Pageable pageable);

    Optional<QuestionAnswer> findByIdAndQuestionIdAndDeletedAtIsNull(UUID answerId, UUID questionId);

    Optional<QuestionAnswer> findByIdAndDeletedAtIsNull(UUID answerId);
}
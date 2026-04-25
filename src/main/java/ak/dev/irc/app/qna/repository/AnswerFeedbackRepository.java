package ak.dev.irc.app.qna.repository;

import ak.dev.irc.app.qna.entity.AnswerFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AnswerFeedbackRepository extends JpaRepository<AnswerFeedback, UUID> {

    List<AnswerFeedback> findByAnswerIdOrderByCreatedAtAsc(UUID answerId);

    Optional<AnswerFeedback> findByAnswerIdAndAuthorId(UUID answerId, UUID authorId);

    boolean existsByAnswerIdAndAuthorId(UUID answerId, UUID authorId);

    long countByAnswerId(UUID answerId);
}
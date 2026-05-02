package ak.dev.irc.app.qna.repository;

import ak.dev.irc.app.qna.entity.AnswerReaction;
import ak.dev.irc.app.qna.entity.AnswerReactionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AnswerReactionRepository extends JpaRepository<AnswerReaction, AnswerReactionId> {

    Optional<AnswerReaction> findByAnswerIdAndUserId(UUID answerId, UUID userId);

    boolean existsByAnswerIdAndUserId(UUID answerId, UUID userId);

    long countByAnswerId(UUID answerId);
}

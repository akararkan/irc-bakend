package ak.dev.irc.app.qna.repository;

import ak.dev.irc.app.qna.entity.AnswerReaction;
import ak.dev.irc.app.qna.entity.AnswerReactionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AnswerReactionRepository extends JpaRepository<AnswerReaction, AnswerReactionId> {

    Optional<AnswerReaction> findByAnswerIdAndUserId(UUID answerId, UUID userId);

    boolean existsByAnswerIdAndUserId(UUID answerId, UUID userId);

    long countByAnswerId(UUID answerId);

    /**
     * Bulk-purge every reaction on an answer. Called when the answer is soft-deleted
     * so the parent's denormalised counters and the user-facing "I reacted to this"
     * state both go back to clean baselines.
     */
    @Modifying
    @Query("DELETE FROM AnswerReaction r WHERE r.id.answerId = :answerId")
    int deleteAllByAnswerId(@Param("answerId") UUID answerId);
}

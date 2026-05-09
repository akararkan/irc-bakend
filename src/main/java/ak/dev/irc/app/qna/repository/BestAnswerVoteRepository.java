package ak.dev.irc.app.qna.repository;

import ak.dev.irc.app.qna.entity.BestAnswerVote;
import ak.dev.irc.app.qna.entity.BestAnswerVoteId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BestAnswerVoteRepository extends JpaRepository<BestAnswerVote, BestAnswerVoteId> {

    Optional<BestAnswerVote> findByAnswerIdAndVoterId(UUID answerId, UUID voterId);

    long countByAnswerId(UUID answerId);

    boolean existsByAnswerIdAndVoterId(UUID answerId, UUID voterId);

    @Modifying
    @Query("DELETE FROM BestAnswerVote v WHERE v.id.answerId = :answerId AND v.id.voterId = :voterId")
    int deleteByAnswerIdAndVoterId(@Param("answerId") UUID answerId, @Param("voterId") UUID voterId);

    /** Purge every best-answer vote for an answer when it's soft-deleted. */
    @Modifying
    @Query("DELETE FROM BestAnswerVote v WHERE v.id.answerId = :answerId")
    int deleteAllByAnswerId(@Param("answerId") UUID answerId);

    /** Bulk lookup of {@code answerId → didIVote} for a viewer over a page of answers. */
    @Query("""
        SELECT v.id.answerId
        FROM BestAnswerVote v
        WHERE v.id.voterId = :voterId
          AND v.id.answerId IN :answerIds
        """)
    List<UUID> findVotedAnswerIds(@Param("voterId") UUID voterId,
                                   @Param("answerIds") List<UUID> answerIds);
}

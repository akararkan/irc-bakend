package ak.dev.irc.app.research.repository;

import ak.dev.irc.app.research.entity.ResearchCommentReaction;
import ak.dev.irc.app.research.entity.ResearchCommentReactionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ResearchCommentReactionRepository
        extends JpaRepository<ResearchCommentReaction, ResearchCommentReactionId> {

    Optional<ResearchCommentReaction> findByCommentIdAndUserId(UUID commentId, UUID userId);

    boolean existsByCommentIdAndUserId(UUID commentId, UUID userId);

    /** Bulk-purge reactions when the comment is soft-deleted — mirrors PostCommentReactionRepository.deleteAllByCommentId. */
    @Modifying
    @Query("DELETE FROM ResearchCommentReaction r WHERE r.id.commentId = :commentId")
    int deleteAllByCommentId(@Param("commentId") UUID commentId);
}

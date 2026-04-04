package ak.dev.irc.app.post.repository;


import ak.dev.irc.app.post.entity.PostCommentReaction;
import ak.dev.irc.app.post.entity.PostCommentReactionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PostCommentReactionRepository extends JpaRepository<PostCommentReaction, PostCommentReactionId> {

    Optional<PostCommentReaction> findByCommentIdAndUserId(UUID commentId, UUID userId);

    boolean existsByCommentIdAndUserId(UUID commentId, UUID userId);
}
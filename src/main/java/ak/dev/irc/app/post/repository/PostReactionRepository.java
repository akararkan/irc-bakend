package ak.dev.irc.app.post.repository;


import ak.dev.irc.app.post.entity.PostReaction;
import ak.dev.irc.app.post.entity.PostReactionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PostReactionRepository extends JpaRepository<PostReaction, PostReactionId> {

    Optional<PostReaction> findByPostIdAndUserId(UUID postId, UUID userId);

    boolean existsByPostIdAndUserId(UUID postId, UUID userId);

    /** Count breakdown per type */
    @Query("SELECT r.reactionType, COUNT(r) FROM PostReaction r WHERE r.post.id = :postId GROUP BY r.reactionType")
    List<Object[]> countByTypeForPost(@Param("postId") UUID postId);

    List<PostReaction> findTop5ByPostIdOrderByCreatedAtDesc(UUID postId);
}
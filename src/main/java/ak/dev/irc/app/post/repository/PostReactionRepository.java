package ak.dev.irc.app.post.repository;


import ak.dev.irc.app.post.entity.PostReaction;
import ak.dev.irc.app.post.entity.PostReactionId;
import ak.dev.irc.app.post.enums.PostReactionType;
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

    /** Source-of-truth count for the reconciler to rebuild {@code post.reactionCount}. */
    long countByPostId(UUID postId);

    /**
     * Batch lookup so feed renders can mark {@code myReaction} per post without
     * an N+1 round trip. Returns {@code [postId, reactionType]} pairs for every
     * post in {@code postIds} that the viewer has reacted to.
     */
    @Query("""
        SELECT r.post.id, r.reactionType FROM PostReaction r
        WHERE r.user.id = :userId AND r.post.id IN :postIds
    """)
    List<Object[]> findViewerReactions(@Param("userId") UUID userId,
                                       @Param("postIds") List<UUID> postIds);
}
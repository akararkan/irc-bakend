package ak.dev.irc.app.post.repository;

import ak.dev.irc.app.post.entity.Post;
import ak.dev.irc.app.post.enums.PostStatus;
import ak.dev.irc.app.post.enums.PostType;
import ak.dev.irc.app.post.enums.PostVisibility;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PostRepository extends JpaRepository<Post, UUID> {

    // Feed: all visible published posts by a specific author
    Page<Post> findByAuthorIdAndStatusAndVisibility(
            UUID authorId, PostStatus status, PostVisibility visibility, Pageable pageable);

    // Public feed (latest)
    Page<Post> findByStatusAndVisibilityOrderByCreatedAtDesc(
            PostStatus status, PostVisibility visibility, Pageable pageable);

    // By type (e.g. REEL feed)
    Page<Post> findByPostTypeAndStatusAndVisibilityOrderByCreatedAtDesc(
            PostType postType, PostStatus status, PostVisibility visibility, Pageable pageable);

    // Share link lookup
    Optional<Post> findByShareLink(String shareLink);

    // Counter updates (direct DB, avoids optimistic lock conflicts)
    @Modifying
    @Query("UPDATE Post p SET p.reactionCount = p.reactionCount + :delta WHERE p.id = :id")
    void updateReactionCount(@Param("id") UUID id, @Param("delta") long delta);

    @Modifying
    @Query("UPDATE Post p SET p.commentCount = p.commentCount + :delta WHERE p.id = :id")
    void updateCommentCount(@Param("id") UUID id, @Param("delta") long delta);

    @Modifying
    @Query("UPDATE Post p SET p.shareCount = p.shareCount + 1 WHERE p.id = :id")
    void incrementShareCount(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE Post p SET p.viewCount = p.viewCount + 1 WHERE p.id = :id")
    void incrementViewCount(@Param("id") UUID id);

    // Following feed: posts from followed users (PUBLIC + FOLLOWERS_ONLY)
    @Query("""
        SELECT p FROM Post p
        WHERE p.author.id IN :authorIds
          AND p.status = 'PUBLISHED'
          AND p.visibility IN ('PUBLIC', 'FOLLOWERS_ONLY')
        ORDER BY p.createdAt DESC
        """)
    Page<Post> findFollowingFeed(@Param("authorIds") List<UUID> authorIds, Pageable pageable);

    // Following reel feed: reels from followed users
    @Query("""
        SELECT p FROM Post p
        WHERE p.author.id IN :authorIds
          AND p.postType = 'REEL'
          AND p.status = 'PUBLISHED'
          AND p.visibility IN ('PUBLIC', 'FOLLOWERS_ONLY')
        ORDER BY p.createdAt DESC
        """)
    Page<Post> findFollowingReelFeed(@Param("authorIds") List<UUID> authorIds, Pageable pageable);

    // Find a user's repost of a specific original post
    @Query("""
        SELECT p FROM Post p
        WHERE p.author.id = :authorId
          AND p.sharedPost.id = :originalPostId
          AND p.postType = 'REPOST'
          AND p.status = 'PUBLISHED'
        """)
    Optional<Post> findRepostByAuthorAndOriginal(@Param("authorId") UUID authorId,
                                                  @Param("originalPostId") UUID originalPostId);

    // Search (voice transcript removed from posts — search only by text content)
    @Query("SELECT p FROM Post p WHERE p.status = 'PUBLISHED' AND p.visibility = 'PUBLIC' " +
            "AND LOWER(p.textContent) LIKE LOWER(CONCAT('%',:q,'%'))")
    Page<Post> search(@Param("q") String query, Pageable pageable);
}
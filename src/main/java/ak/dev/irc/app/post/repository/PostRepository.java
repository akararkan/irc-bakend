package ak.dev.irc.app.post.repository;

import ak.dev.irc.app.post.entity.Post;
import ak.dev.irc.app.post.enums.PostStatus;
import ak.dev.irc.app.post.enums.PostType;
import ak.dev.irc.app.post.enums.PostVisibility;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PostRepository extends JpaRepository<Post, UUID> {

    // Feed: all visible published posts by a specific author
    @EntityGraph(attributePaths = {"author", "mediaList", "sharedPost", "sharedPost.author"})
    Page<Post> findByAuthorIdAndStatusAndVisibility(
            UUID authorId, PostStatus status, PostVisibility visibility, Pageable pageable);

    // Public feed (latest) — anonymous viewers (no block filter applied).
    @EntityGraph(attributePaths = {"author", "mediaList", "sharedPost", "sharedPost.author"})
    Page<Post> findByStatusAndVisibilityOrderByCreatedAtDesc(
            PostStatus status, PostVisibility visibility, Pageable pageable);

    // Public feed excluding any author the requester is in a block-relationship with.
    // One covering filter per query — no per-row isBlockedBetween scans.
    @EntityGraph(attributePaths = {"author", "mediaList", "sharedPost", "sharedPost.author"})
    @Query("""
        SELECT p FROM Post p
        WHERE p.status = 'PUBLISHED'
          AND p.visibility = 'PUBLIC'
          AND p.author.id NOT IN :blockedIds
        ORDER BY p.createdAt DESC
        """)
    Page<Post> findPublicFeedExcluding(@Param("blockedIds") List<UUID> blockedIds, Pageable pageable);

    // By type (e.g. REEL feed) — anonymous variant.
    @EntityGraph(attributePaths = {"author", "mediaList", "sharedPost", "sharedPost.author"})
    Page<Post> findByPostTypeAndStatusAndVisibilityOrderByCreatedAtDesc(
            PostType postType, PostStatus status, PostVisibility visibility, Pageable pageable);

    // Reel feed excluding blocked authors.
    @EntityGraph(attributePaths = {"author", "mediaList", "sharedPost", "sharedPost.author"})
    @Query("""
        SELECT p FROM Post p
        WHERE p.postType = 'REEL'
          AND p.status = 'PUBLISHED'
          AND p.visibility = 'PUBLIC'
          AND p.author.id NOT IN :blockedIds
        ORDER BY p.createdAt DESC
        """)
    Page<Post> findReelFeedExcluding(@Param("blockedIds") List<UUID> blockedIds, Pageable pageable);

    // Cursor pagination: O(log n) deep paging.
    // Split into two methods so the cursor parameter has a concrete type bound
    // on Postgres (a single nullable :cursor in both branches of an OR confuses
    // the JDBC driver: "could not determine data type of parameter $1").
    @EntityGraph(attributePaths = {"author", "mediaList", "sharedPost", "sharedPost.author"})
    @Query("""
        SELECT p FROM Post p
        WHERE p.status = 'PUBLISHED'
          AND p.visibility = 'PUBLIC'
        ORDER BY p.createdAt DESC
        """)
    List<Post> findPublicFeedFirstPage(Pageable pageable);

    @EntityGraph(attributePaths = {"author", "mediaList", "sharedPost", "sharedPost.author"})
    @Query("""
        SELECT p FROM Post p
        WHERE p.status = 'PUBLISHED'
          AND p.visibility = 'PUBLIC'
          AND p.createdAt < :cursor
        ORDER BY p.createdAt DESC
        """)
    List<Post> findPublicFeedAfter(@Param("cursor") LocalDateTime cursor, Pageable pageable);

    // Block-aware cursor pagination — same shape as the anonymous variants but
    // excludes any post whose author is in a block-relationship with the viewer.
    @EntityGraph(attributePaths = {"author", "mediaList", "sharedPost", "sharedPost.author"})
    @Query("""
        SELECT p FROM Post p
        WHERE p.status = 'PUBLISHED'
          AND p.visibility = 'PUBLIC'
          AND p.author.id NOT IN :blockedIds
        ORDER BY p.createdAt DESC
        """)
    List<Post> findPublicFeedFirstPageExcluding(@Param("blockedIds") List<UUID> blockedIds, Pageable pageable);

    @EntityGraph(attributePaths = {"author", "mediaList", "sharedPost", "sharedPost.author"})
    @Query("""
        SELECT p FROM Post p
        WHERE p.status = 'PUBLISHED'
          AND p.visibility = 'PUBLIC'
          AND p.createdAt < :cursor
          AND p.author.id NOT IN :blockedIds
        ORDER BY p.createdAt DESC
        """)
    List<Post> findPublicFeedAfterExcluding(@Param("cursor") LocalDateTime cursor,
                                            @Param("blockedIds") List<UUID> blockedIds,
                                            Pageable pageable);

    // Share link lookup
    Optional<Post> findByShareLink(String shareLink);

    // Counter updates (direct DB, avoids optimistic lock conflicts).
    // Clamp at 0 so concurrent decrements cannot push counters negative.
    @Modifying
    @Query("UPDATE Post p SET p.reactionCount = CASE WHEN p.reactionCount + :delta < 0 THEN 0 ELSE p.reactionCount + :delta END WHERE p.id = :id")
    void updateReactionCount(@Param("id") UUID id, @Param("delta") long delta);

    @Modifying
    @Query("UPDATE Post p SET p.commentCount = CASE WHEN p.commentCount + :delta < 0 THEN 0 ELSE p.commentCount + :delta END WHERE p.id = :id")
    void updateCommentCount(@Param("id") UUID id, @Param("delta") long delta);

    @Modifying
    @Query("UPDATE Post p SET p.shareCount = p.shareCount + 1 WHERE p.id = :id")
    void incrementShareCount(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE Post p SET p.shareCount = CASE WHEN p.shareCount > 0 THEN p.shareCount - 1 ELSE 0 END WHERE p.id = :id")
    void decrementShareCount(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE Post p SET p.viewCount = p.viewCount + 1 WHERE p.id = :id")
    void incrementViewCount(@Param("id") UUID id);

    boolean existsByShareLink(String shareLink);

    // Following feed: posts from followed users (PUBLIC + FOLLOWERS_ONLY)
    @EntityGraph(attributePaths = {"author", "mediaList", "sharedPost", "sharedPost.author"})
    @Query("""
        SELECT p FROM Post p
        WHERE p.author.id IN :authorIds
          AND p.status = 'PUBLISHED'
          AND p.visibility IN ('PUBLIC', 'FOLLOWERS_ONLY')
        ORDER BY p.createdAt DESC
        """)
    Page<Post> findFollowingFeed(@Param("authorIds") List<UUID> authorIds, Pageable pageable);

    // Following reel feed: reels from followed users
    @EntityGraph(attributePaths = {"author", "mediaList", "sharedPost", "sharedPost.author"})
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

    // ── Postgres FTS — sub-millisecond on millions of rows with the GIN index.
    // Returns (id, ts_rank_cd) tuples; the service hydrates entities in one batch.
    // websearch_to_tsquery accepts user input as-is ("foo bar", "foo OR bar", -exclude).
    // {@code reelsOnly}: null = any post type, true = only REEL, false = exclude REEL.
    @Query(value = """
        SELECT p.id, ts_rank_cd(to_tsvector('simple', coalesce(p.text_content, '')),
                                websearch_to_tsquery('simple', :q)) AS score
        FROM posts p
        WHERE p.status = 'PUBLISHED'
          AND p.visibility = 'PUBLIC'
          AND to_tsvector('simple', coalesce(p.text_content, '')) @@ websearch_to_tsquery('simple', :q)
          AND (:reelsOnly IS NULL
               OR (:reelsOnly = TRUE  AND p.post_type = 'REEL')
               OR (:reelsOnly = FALSE AND p.post_type <> 'REEL'))
          AND (CAST(:blockedIds AS uuid[]) IS NULL
               OR p.author_id <> ALL(CAST(:blockedIds AS uuid[])))
        ORDER BY score DESC, p.created_at DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> searchFts(@Param("q") String q,
                              @Param("reelsOnly") Boolean reelsOnly,
                              @Param("blockedIds") java.util.UUID[] blockedIds,
                              @Param("limit") int limit);

    /**
     * Tag search — finds posts that contain a {@code #tag} literal in their
     * text content. Uses the trigram GIN index for sub-millisecond lookup
     * and is anchored on the {@code #} prefix so partial matches in normal
     * text don't pollute the result.
     */
    @Query(value = """
        SELECT p.id, ts_rank_cd(to_tsvector('simple', coalesce(p.text_content, '')),
                                websearch_to_tsquery('simple', :tag)) AS score
        FROM posts p
        WHERE p.status = 'PUBLISHED'
          AND p.visibility = 'PUBLIC'
          AND p.text_content ILIKE CONCAT('%#', :tag, '%')
          AND (CAST(:blockedIds AS uuid[]) IS NULL
               OR p.author_id <> ALL(CAST(:blockedIds AS uuid[])))
        ORDER BY score DESC, p.created_at DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> searchByHashtag(@Param("tag") String tag,
                                    @Param("blockedIds") java.util.UUID[] blockedIds,
                                    @Param("limit") int limit);

    // Trigram fallback for typo tolerance — uses the % operator + similarity().
    @Query(value = """
        SELECT p.id, similarity(coalesce(p.text_content, ''), :q) AS score
        FROM posts p
        WHERE p.status = 'PUBLISHED'
          AND p.visibility = 'PUBLIC'
          AND coalesce(p.text_content, '') %% :q
          AND (:reelsOnly IS NULL
               OR (:reelsOnly = TRUE  AND p.post_type = 'REEL')
               OR (:reelsOnly = FALSE AND p.post_type <> 'REEL'))
          AND (CAST(:blockedIds AS uuid[]) IS NULL
               OR p.author_id <> ALL(CAST(:blockedIds AS uuid[])))
        ORDER BY score DESC, p.created_at DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> searchTrgm(@Param("q") String q,
                               @Param("reelsOnly") Boolean reelsOnly,
                               @Param("blockedIds") java.util.UUID[] blockedIds,
                               @Param("limit") int limit);

    // Block-aware search — drops blocked authors in the same query.
    @Query("""
        SELECT p FROM Post p
        WHERE p.status = 'PUBLISHED'
          AND p.visibility = 'PUBLIC'
          AND p.author.id NOT IN :blockedIds
          AND LOWER(p.textContent) LIKE LOWER(CONCAT('%', :q, '%'))
        """)
    Page<Post> searchExcluding(@Param("q") String query,
                               @Param("blockedIds") List<UUID> blockedIds,
                               Pageable pageable);
}
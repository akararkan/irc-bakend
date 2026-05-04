package ak.dev.irc.app.post.repository;


import ak.dev.irc.app.post.entity.PostComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PostCommentRepository extends JpaRepository<PostComment, UUID> {

    /** Top-level comments for a post (no parent) */
    Page<PostComment> findByPostIdAndParentIsNullAndIsDeletedFalseOrderByCreatedAtDesc(
            UUID postId, Pageable pageable);

    /** Replies to a specific comment */
    Page<PostComment> findByParentIdAndIsDeletedFalseOrderByCreatedAtAsc(
            UUID parentId, Pageable pageable);

    /**
     * Restriction-aware top-level comment listing (IG-style).
     *
     * <p>A comment authored by a user that the post author has restricted is
     * hidden from everyone <em>except</em> the post author and the comment
     * author themselves.</p>
     *
     * <p>{@code requesterId} may be {@code null} for anonymous viewers — the
     * existence sub-query then enforces full restriction.</p>
     */
    @Query("""
        SELECT c FROM PostComment c
        WHERE c.post.id = :postId
          AND c.parent IS NULL
          AND c.isDeleted = false
          AND (
            (:requesterId IS NOT NULL AND c.post.author.id = :requesterId)
            OR (:requesterId IS NOT NULL AND c.author.id = :requesterId)
            OR NOT EXISTS (
              SELECT 1 FROM UserRestriction r
              WHERE r.restrictor.id = c.post.author.id
                AND r.restricted.id = c.author.id
            )
          )
        ORDER BY c.createdAt DESC
        """)
    Page<PostComment> findVisibleTopLevelComments(@Param("postId") UUID postId,
                                                  @Param("requesterId") UUID requesterId,
                                                  Pageable pageable);

    @Query("""
        SELECT c FROM PostComment c
        WHERE c.parent.id = :parentId
          AND c.isDeleted = false
          AND (
            (:requesterId IS NOT NULL AND c.post.author.id = :requesterId)
            OR (:requesterId IS NOT NULL AND c.author.id = :requesterId)
            OR NOT EXISTS (
              SELECT 1 FROM UserRestriction r
              WHERE r.restrictor.id = c.post.author.id
                AND r.restricted.id = c.author.id
            )
          )
        ORDER BY c.createdAt ASC
        """)
    Page<PostComment> findVisibleReplies(@Param("parentId") UUID parentId,
                                         @Param("requesterId") UUID requesterId,
                                         Pageable pageable);

    @Modifying
    @Query("UPDATE PostComment c SET c.reactionCount = CASE WHEN c.reactionCount + :delta < 0 THEN 0 ELSE c.reactionCount + :delta END WHERE c.id = :id")
    void updateReactionCount(@Param("id") UUID id, @Param("delta") long delta);

    @Modifying
    @Query("UPDATE PostComment c SET c.replyCount = CASE WHEN c.replyCount + :delta < 0 THEN 0 ELSE c.replyCount + :delta END WHERE c.id = :id")
    void updateReplyCount(@Param("id") UUID id, @Param("delta") long delta);
}
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

    @Modifying
    @Query("UPDATE PostComment c SET c.reactionCount = c.reactionCount + :delta WHERE c.id = :id")
    void updateReactionCount(@Param("id") UUID id, @Param("delta") long delta);

    @Modifying
    @Query("UPDATE PostComment c SET c.replyCount = c.replyCount + :delta WHERE c.id = :id")
    void updateReplyCount(@Param("id") UUID id, @Param("delta") long delta);
}
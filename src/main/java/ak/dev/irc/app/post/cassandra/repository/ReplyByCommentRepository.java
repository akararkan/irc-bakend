package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.ReplyByCommentEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface ReplyByCommentRepository extends CassandraRepository<ReplyByCommentEntity, MapId> {

    @Query("SELECT * FROM replies_by_comment WHERE parent_id = :parentId LIMIT :pageSize")
    List<ReplyByCommentEntity> firstPage(@Param("parentId") UUID parentId,
                                         @Param("pageSize") int pageSize);

    /** NEWEST replies first — see {@link CommentByPostRepository#newestPage}. */
    @Query("SELECT * FROM replies_by_comment WHERE parent_id = :parentId " +
           "ORDER BY created_at DESC LIMIT :pageSize")
    List<ReplyByCommentEntity> newestPage(@Param("parentId") UUID parentId,
                                          @Param("pageSize") int pageSize);

    /** Full-key point read — pre-edit text for mention delta scans. */
    @Query("SELECT * FROM replies_by_comment " +
           "WHERE parent_id = :parentId AND created_at = :createdAt AND reply_id = :replyId")
    java.util.Optional<ReplyByCommentEntity> findRow(@Param("parentId") UUID parentId,
                                                     @Param("createdAt") Instant createdAt,
                                                     @Param("replyId") UUID replyId);

    @Query("UPDATE replies_by_comment SET text_content = :text, is_edited = true " +
           "WHERE parent_id = :parentId AND created_at = :createdAt AND reply_id = :replyId")
    void editText(@Param("parentId") UUID parentId,
                  @Param("createdAt") Instant createdAt,
                  @Param("replyId") UUID replyId,
                  @Param("text") String text);

    /**
     * Hard delete the reply row. Same rationale as
     * {@link CommentByPostRepository#hardDelete}: a single row tombstone
     * cleared after {@code gc_grace_seconds}, no accumulating soft-deleted
     * rows under the parent's clustering range.
     */
    @Query("DELETE FROM replies_by_comment " +
           "WHERE parent_id = :parentId AND created_at = :createdAt AND reply_id = :replyId")
    void hardDelete(@Param("parentId") UUID parentId,
                    @Param("createdAt") Instant createdAt,
                    @Param("replyId") UUID replyId);

    /**
     * Range-delete every reply under a hard-deleted parent. Single range
     * tombstone — used when a top-level comment is removed and we want to
     * sweep its reply partition rather than leaving orphan replies behind.
     */
    @Query("DELETE FROM replies_by_comment WHERE parent_id = :parentId")
    void deleteAllUnder(@Param("parentId") UUID parentId);
}

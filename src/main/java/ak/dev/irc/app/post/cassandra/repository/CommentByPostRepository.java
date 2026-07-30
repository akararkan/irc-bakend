package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.CommentByPostEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface CommentByPostRepository extends CassandraRepository<CommentByPostEntity, MapId> {

    @Query("SELECT * FROM comments_by_post WHERE post_id = :postId LIMIT :pageSize")
    List<CommentByPostEntity> firstPage(@Param("postId") UUID postId,
                                        @Param("pageSize") int pageSize);

    /** Full-key point read — pre-edit text for mention delta scans. */
    @Query("SELECT * FROM comments_by_post " +
           "WHERE post_id = :postId AND created_at = :createdAt AND comment_id = :commentId")
    java.util.Optional<CommentByPostEntity> findRow(@Param("postId") UUID postId,
                                                    @Param("createdAt") Instant createdAt,
                                                    @Param("commentId") UUID commentId);

    /**
     * NEWEST rows first. The table clusters ASCENDING on created_at, so a
     * plain LIMIT returns the OLDEST comments — the dedup guard needs the
     * newest ones (a just-created duplicate is by definition the newest row).
     */
    @Query("SELECT * FROM comments_by_post WHERE post_id = :postId " +
           "ORDER BY created_at DESC LIMIT :pageSize")
    List<CommentByPostEntity> newestPage(@Param("postId") UUID postId,
                                         @Param("pageSize") int pageSize);

    @Query("SELECT * FROM comments_by_post WHERE post_id = :postId " +
           "AND created_at > :cursor LIMIT :pageSize")
    List<CommentByPostEntity> nextPage(@Param("postId") UUID postId,
                                       @Param("cursor") Instant cursor,
                                       @Param("pageSize") int pageSize);

    @Query("UPDATE comments_by_post SET text_content = :text, is_edited = true " +
           "WHERE post_id = :postId AND created_at = :createdAt AND comment_id = :commentId")
    void editText(@Param("postId") UUID postId,
                  @Param("createdAt") Instant createdAt,
                  @Param("commentId") UUID commentId,
                  @Param("text") String text);

    /**
     * Hard delete the comment row. Replaces the legacy {@code softDelete}
     * pattern (UPDATE … SET is_deleted=true), which left the row in place
     * forever and caused thread-partition bloat. A DELETE writes one row
     * tombstone; the tombstone is GC'd after the table's
     * {@code gc_grace_seconds} (default 10d, can be tuned per table) and
     * disappears entirely, so long-lived threads stay scan-cheap.
     */
    @Query("DELETE FROM comments_by_post " +
           "WHERE post_id = :postId AND created_at = :createdAt AND comment_id = :commentId")
    void hardDelete(@Param("postId") UUID postId,
                    @Param("createdAt") Instant createdAt,
                    @Param("commentId") UUID commentId);

    /** Partition-level delete — wipes every comment for a post. */
    @Query("DELETE FROM comments_by_post WHERE post_id = :postId")
    void deleteAllForPost(@Param("postId") UUID postId);

    /** Used to drive comment_reactions cleanup before the comments themselves are dropped. */
    @Query("SELECT * FROM comments_by_post WHERE post_id = :postId")
    List<CommentByPostEntity> findAllForPost(@Param("postId") UUID postId);
}

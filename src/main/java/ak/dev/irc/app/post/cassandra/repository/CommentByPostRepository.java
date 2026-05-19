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

    @Query("UPDATE comments_by_post SET is_deleted = true, text_content = null, " +
           "media_url = null, media_type = null " +
           "WHERE post_id = :postId AND created_at = :createdAt AND comment_id = :commentId")
    void softDelete(@Param("postId") UUID postId,
                    @Param("createdAt") Instant createdAt,
                    @Param("commentId") UUID commentId);
}

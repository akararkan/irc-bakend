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

    @Query("UPDATE replies_by_comment SET text_content = :text, is_edited = true " +
           "WHERE parent_id = :parentId AND created_at = :createdAt AND reply_id = :replyId")
    void editText(@Param("parentId") UUID parentId,
                  @Param("createdAt") Instant createdAt,
                  @Param("replyId") UUID replyId,
                  @Param("text") String text);

    @Query("UPDATE replies_by_comment SET is_deleted = true, text_content = null, " +
           "media_url = null " +
           "WHERE parent_id = :parentId AND created_at = :createdAt AND reply_id = :replyId")
    void softDelete(@Param("parentId") UUID parentId,
                    @Param("createdAt") Instant createdAt,
                    @Param("replyId") UUID replyId);
}

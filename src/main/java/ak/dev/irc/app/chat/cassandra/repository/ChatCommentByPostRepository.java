package ak.dev.irc.app.chat.cassandra.repository;

import ak.dev.irc.app.chat.cassandra.entity.ChatCommentByPostEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Single-partition comment pages per channel post, newest first. */
@Repository
public interface ChatCommentByPostRepository
        extends CassandraRepository<ChatCommentByPostEntity, MapId> {

    @Query("SELECT * FROM chat_comments_by_post WHERE post_message_id = :postId LIMIT :limit")
    List<ChatCommentByPostEntity> firstPage(@Param("postId") long postMessageId, @Param("limit") int limit);

    @Query("""
        SELECT * FROM chat_comments_by_post
        WHERE post_message_id = :postId AND comment_message_id < :before LIMIT :limit
        """)
    List<ChatCommentByPostEntity> pageBefore(@Param("postId") long postMessageId,
                                             @Param("before") long beforeCommentId,
                                             @Param("limit") int limit);

    @Query("DELETE FROM chat_comments_by_post WHERE post_message_id = :postId AND comment_message_id = :commentId")
    void deleteOne(@Param("postId") long postMessageId, @Param("commentId") long commentMessageId);

    @Query("DELETE FROM chat_comments_by_post WHERE post_message_id = :postId")
    void deleteAllForPost(@Param("postId") long postMessageId);
}

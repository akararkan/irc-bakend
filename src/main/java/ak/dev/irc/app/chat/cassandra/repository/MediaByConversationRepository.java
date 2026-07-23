package ak.dev.irc.app.chat.cassandra.repository;

import ak.dev.irc.app.chat.cassandra.entity.MediaByConversationEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Single-partition gallery pages per (conversation, media kind), newest first. */
@Repository
public interface MediaByConversationRepository
        extends CassandraRepository<MediaByConversationEntity, MapId> {

    @Query("SELECT * FROM media_by_conversation WHERE conversation_id = :cid AND kind = :kind LIMIT :limit")
    List<MediaByConversationEntity> firstPage(@Param("cid") UUID conversationId,
                                              @Param("kind") String kind,
                                              @Param("limit") int limit);

    @Query("""
        SELECT * FROM media_by_conversation
        WHERE conversation_id = :cid AND kind = :kind AND message_id < :before LIMIT :limit
        """)
    List<MediaByConversationEntity> pageBefore(@Param("cid") UUID conversationId,
                                               @Param("kind") String kind,
                                               @Param("before") long beforeMessageId,
                                               @Param("limit") int limit);

    @Query("DELETE FROM media_by_conversation WHERE conversation_id = :cid AND kind = :kind AND message_id = :messageId")
    void deleteForMessage(@Param("cid") UUID conversationId,
                          @Param("kind") String kind,
                          @Param("messageId") long messageId);
}

package ak.dev.irc.app.chat.cassandra.repository;

import ak.dev.irc.app.chat.cassandra.entity.ReactionByMessageEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Per-message reaction rows (one per user). Add/change is an upsert, remove is a
 * delete; aggregate counts are maintained in a Redis hash and recomputed lazily
 * from this table if the cache is cold.
 */
@Repository
public interface ReactionByMessageRepository
        extends CassandraRepository<ReactionByMessageEntity, MapId> {

    @Query("SELECT * FROM reactions_by_message WHERE message_id = :messageId")
    List<ReactionByMessageEntity> findByMessage(@Param("messageId") long messageId);

    @Query("SELECT * FROM reactions_by_message WHERE message_id = :messageId AND user_id = :userId")
    ReactionByMessageEntity findOne(@Param("messageId") long messageId, @Param("userId") UUID userId);

    @Query("DELETE FROM reactions_by_message WHERE message_id = :messageId AND user_id = :userId")
    void deleteOne(@Param("messageId") long messageId, @Param("userId") UUID userId);

    @Query("DELETE FROM reactions_by_message WHERE message_id = :messageId")
    void deleteAllForMessage(@Param("messageId") long messageId);
}

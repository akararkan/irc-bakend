package ak.dev.irc.app.chat.cassandra.repository;

import ak.dev.irc.app.chat.cassandra.entity.MessageByIdEntity;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Point lookups of a message by its Snowflake id — replies, forwards, jump-to,
 * and the id-only edit/delete path. Also serves batch hydration (reply previews)
 * via a single {@code IN} load.
 */
@Repository
public interface MessageByIdRepository extends CassandraRepository<MessageByIdEntity, Long> {

    /**
     * Bulk id load for hydrating reply previews. Derived method (not {@code @Query})
     * so Spring Data binds the collection as the correct CQL {@code list} rather
     * than mis-binding a {@code @Query} prepared statement.
     */
    List<MessageByIdEntity> findAllByMessageIdIn(Collection<Long> messageIds);

    @Query("UPDATE message_by_id SET body = :body, edited_at = :editedAt WHERE message_id = :messageId")
    void editBody(@Param("messageId") long messageId,
                  @Param("body") String body,
                  @Param("editedAt") Instant editedAt);

    /** Edit under a Cassandra TTL (disappearing conversations) — see the twin on
     *  {@code MessageByConversationRepository}. */
    @Query("UPDATE message_by_id USING TTL :ttl SET body = :body, edited_at = :editedAt WHERE message_id = :messageId")
    void editBodyWithTtl(@Param("messageId") long messageId,
                         @Param("body") String body,
                         @Param("editedAt") Instant editedAt,
                         @Param("ttl") int ttl);

    @Query("UPDATE message_by_id SET deleted = true, body = null, media = null WHERE message_id = :messageId")
    void tombstone(@Param("messageId") long messageId);
}

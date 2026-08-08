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

    /** Re-write the extracted hashtags after a body edit. */
    @Query("UPDATE message_by_id SET tags = :tags WHERE message_id = :messageId")
    void updateTags(@Param("messageId") long messageId, @Param("tags") java.util.Set<String> tags);

    /** Re-write the poll payload (close, tally metadata). */
    @Query("UPDATE message_by_id SET poll = :poll WHERE message_id = :messageId")
    void updatePoll(@Param("messageId") long messageId, @Param("poll") String poll);

    /** Twin of {@code MessageByConversationRepository.setModerationStatus} — see
     *  there for why an approval writes null instead of {@code "APPROVED"}. */
    @Query("UPDATE message_by_id SET moderation_status = :status WHERE message_id = :messageId")
    void setModerationStatus(@Param("messageId") long messageId, @Param("status") String status);

    /** Stamped once the message has actually been fanned out — see the entity field. */
    @Query("UPDATE message_by_id SET delivered = :delivered WHERE message_id = :messageId")
    void setDelivered(@Param("messageId") long messageId, @Param("delivered") boolean delivered);

    @Query("UPDATE message_by_id SET deleted = true, body = null, media = null WHERE message_id = :messageId")
    void tombstone(@Param("messageId") long messageId);
}

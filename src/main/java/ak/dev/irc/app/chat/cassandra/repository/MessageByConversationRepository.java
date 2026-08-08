package ak.dev.irc.app.chat.cassandra.repository;

import ak.dev.irc.app.chat.cassandra.entity.MessageByConversationEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The hot read/write path for the message log. Every query binds the full
 * partition key {@code (conversation_id, bucket)} so it touches exactly one
 * partition — any read that spans partitions would be a design bug.
 */
@Repository
public interface MessageByConversationRepository
        extends CassandraRepository<MessageByConversationEntity, MapId> {

    /** Newest page within a bucket (clustering is {@code message_id DESC}). */
    @Query("SELECT * FROM messages_by_conversation " +
           "WHERE conversation_id = :cid AND bucket = :bucket LIMIT :limit")
    List<MessageByConversationEntity> firstPage(@Param("cid") UUID conversationId,
                                                @Param("bucket") int bucket,
                                                @Param("limit") int limit);

    /** Older page within a bucket — everything strictly before the cursor id. */
    @Query("SELECT * FROM messages_by_conversation " +
           "WHERE conversation_id = :cid AND bucket = :bucket AND message_id < :cursor LIMIT :limit")
    List<MessageByConversationEntity> pageBefore(@Param("cid") UUID conversationId,
                                                 @Param("bucket") int bucket,
                                                 @Param("cursor") long cursor,
                                                 @Param("limit") int limit);

    /**
     * Gap-sync within a bucket — the OLDEST rows strictly newer than the high-water
     * id. {@code ORDER BY message_id ASC} (reversing the table's default DESC
     * clustering) is essential: without it the LIMIT would return the newest rows
     * and silently skip the middle of a large gap.
     */
    @Query("SELECT * FROM messages_by_conversation " +
           "WHERE conversation_id = :cid AND bucket = :bucket AND message_id > :after " +
           "ORDER BY message_id ASC LIMIT :limit")
    List<MessageByConversationEntity> pageAfter(@Param("cid") UUID conversationId,
                                                @Param("bucket") int bucket,
                                                @Param("after") long after,
                                                @Param("limit") int limit);

    /** Stamped once the message has actually been fanned out — see the entity field. */
    @Query("UPDATE messages_by_conversation SET delivered = :delivered " +
           "WHERE conversation_id = :cid AND bucket = :bucket AND message_id = :messageId")
    void setDelivered(@Param("cid") UUID conversationId,
                      @Param("bucket") int bucket,
                      @Param("messageId") long messageId,
                      @Param("delivered") boolean delivered);

    /** Edit: rewrite the body + stamp {@code edited_at}. */
    @Query("UPDATE messages_by_conversation SET body = :body, edited_at = :editedAt " +
           "WHERE conversation_id = :cid AND bucket = :bucket AND message_id = :messageId")
    void editBody(@Param("cid") UUID conversationId,
                  @Param("bucket") int bucket,
                  @Param("messageId") long messageId,
                  @Param("body") String body,
                  @Param("editedAt") Instant editedAt);

    /** Edit under a Cassandra TTL — for a disappearing conversation, so the edited
     *  body/edited_at expire with the rest of the row instead of living forever
     *  (a plain UPDATE writes cells with no TTL, resurrecting a self-destruct msg). */
    @Query("UPDATE messages_by_conversation USING TTL :ttl SET body = :body, edited_at = :editedAt " +
           "WHERE conversation_id = :cid AND bucket = :bucket AND message_id = :messageId")
    void editBodyWithTtl(@Param("cid") UUID conversationId,
                         @Param("bucket") int bucket,
                         @Param("messageId") long messageId,
                         @Param("body") String body,
                         @Param("editedAt") Instant editedAt,
                         @Param("ttl") int ttl);

    /** Re-write the extracted hashtags after a body edit. */
    @Query("UPDATE messages_by_conversation SET tags = :tags " +
           "WHERE conversation_id = :cid AND bucket = :bucket AND message_id = :messageId")
    void updateTags(@Param("cid") UUID conversationId,
                    @Param("bucket") int bucket,
                    @Param("messageId") long messageId,
                    @Param("tags") java.util.Set<String> tags);

    /** Re-write the poll payload (close, tally metadata). */
    @Query("UPDATE messages_by_conversation SET poll = :poll " +
           "WHERE conversation_id = :cid AND bucket = :bucket AND message_id = :messageId")
    void updatePoll(@Param("cid") UUID conversationId,
                    @Param("bucket") int bucket,
                    @Param("messageId") long messageId,
                    @Param("poll") String poll);

    /**
     * Flip the automated-moderation flag. An approval passes {@code null} rather
     * than {@code "APPROVED"}: writing null deletes the cell, so a message living
     * under a disappearing-messages TTL is not resurrected as a row carrying one
     * live, never-expiring column after the rest of it has gone.
     */
    @Query("UPDATE messages_by_conversation SET moderation_status = :status " +
           "WHERE conversation_id = :cid AND bucket = :bucket AND message_id = :messageId")
    void setModerationStatus(@Param("cid") UUID conversationId,
                             @Param("bucket") int bucket,
                             @Param("messageId") long messageId,
                             @Param("status") String status);

    /** Soft delete: tombstone the row and null its content, keeping ordering intact. */
    @Query("UPDATE messages_by_conversation SET deleted = true, body = null, media = null " +
           "WHERE conversation_id = :cid AND bucket = :bucket AND message_id = :messageId")
    void tombstone(@Param("cid") UUID conversationId,
                   @Param("bucket") int bucket,
                   @Param("messageId") long messageId);

    /** Hard-remove a single row (used only by whole-conversation purge). */
    @Query("DELETE FROM messages_by_conversation " +
           "WHERE conversation_id = :cid AND bucket = :bucket AND message_id = :messageId")
    void deleteRow(@Param("cid") UUID conversationId,
                   @Param("bucket") int bucket,
                   @Param("messageId") long messageId);

    /** Drop an entire bucket partition in one tombstone (whole-conversation purge). */
    @Query("DELETE FROM messages_by_conversation WHERE conversation_id = :cid AND bucket = :bucket")
    void deleteBucket(@Param("cid") UUID conversationId, @Param("bucket") int bucket);
}

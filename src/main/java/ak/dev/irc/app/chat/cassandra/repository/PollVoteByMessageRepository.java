package ak.dev.irc.app.chat.cassandra.repository;

import ak.dev.irc.app.chat.cassandra.entity.PollVoteByMessageEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Per-poll vote rows (one per voter). See {@link ReactionByMessageRepository}
 *  for the twin pattern this mirrors. */
@Repository
public interface PollVoteByMessageRepository
        extends CassandraRepository<PollVoteByMessageEntity, MapId> {

    @Query("SELECT * FROM poll_votes_by_message WHERE message_id = :messageId")
    List<PollVoteByMessageEntity> findByMessage(@Param("messageId") long messageId);

    @Query("SELECT * FROM poll_votes_by_message WHERE message_id = :messageId AND user_id = :userId")
    PollVoteByMessageEntity findOne(@Param("messageId") long messageId, @Param("userId") UUID userId);

    @Query("DELETE FROM poll_votes_by_message WHERE message_id = :messageId AND user_id = :userId")
    void deleteOne(@Param("messageId") long messageId, @Param("userId") UUID userId);

    @Query("DELETE FROM poll_votes_by_message WHERE message_id = :messageId")
    void deleteAllForMessage(@Param("messageId") long messageId);

    /** The viewer's own vote rows among a page of poll messages — one query to
     *  populate {@code myVotes} during hydration. Derived so the IN binds as a list. */
    List<PollVoteByMessageEntity> findByMessageIdInAndUserId(Collection<Long> messageIds, UUID userId);
}

package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.FriendSuggestionEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface FriendSuggestionRepository extends CassandraRepository<FriendSuggestionEntity, MapId> {

    /** Top-N suggestions for a user — already sorted by score DESC at the table level. */
    @Query("SELECT * FROM friend_suggestions_by_user WHERE user_id = :userId LIMIT :limit")
    List<FriendSuggestionEntity> topSuggestions(@Param("userId") UUID userId,
                                                @Param("limit") int limit);

    /** Wipe a user's prior suggestions before writing a freshly computed set. */
    @Query("DELETE FROM friend_suggestions_by_user WHERE user_id = :userId")
    void clearForUser(@Param("userId") UUID userId);

    /**
     * Remove a single suggestion (dismiss action). The full primary key —
     * partition + BOTH clustering columns (score, candidate_id) — is
     * required: Cassandra rejects a DELETE that skips a clustering column,
     * so the caller reads the row first to learn its score.
     */
    @Query("DELETE FROM friend_suggestions_by_user " +
           "WHERE user_id = :userId AND score = :score AND candidate_id = :candidateId")
    void deleteSuggestion(@Param("userId")      UUID userId,
                          @Param("score")       int  score,
                          @Param("candidateId") UUID candidateId);
}

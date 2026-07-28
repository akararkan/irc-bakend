package ak.dev.irc.app.chat.repository;

import ak.dev.irc.app.chat.entity.StreamGiftTally;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StreamGiftTallyRepository extends JpaRepository<StreamGiftTally, UUID> {

    Optional<StreamGiftTally> findByStreamIdAndUserId(UUID streamId, UUID userId);

    /** Atomically add to an existing sender's running total — the increment happens
     *  in the database ({@code coins = coins + :coins}), so concurrent gifts from the
     *  same sender can't lose an update (unlike read-modify-write). Returns the number
     *  of rows updated: 1 if the sender already had a tally, 0 if this is their first
     *  gift (caller then inserts the row). */
    @Modifying
    @Query("UPDATE StreamGiftTally t SET t.coins = t.coins + :coins, " +
           "t.giftCount = t.giftCount + 1, t.lastGiftAt = :now " +
           "WHERE t.streamId = :sid AND t.userId = :uid")
    int addCoins(@Param("sid") UUID sid, @Param("uid") UUID uid,
                 @Param("coins") long coins, @Param("now") Instant now);

    /** The stream's leaderboard: biggest supporters first, then most recent. */
    List<StreamGiftTally> findByStreamIdOrderByCoinsDescLastGiftAtDesc(UUID streamId, Pageable page);

    /** Purge every tally for a stream — used when the stream is deleted. */
    @Modifying
    @Query("DELETE FROM StreamGiftTally t WHERE t.streamId = :sid")
    int deleteByStreamId(@Param("sid") UUID streamId);
}

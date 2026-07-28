package ak.dev.irc.app.chat.repository;

import ak.dev.irc.app.chat.entity.StreamGuest;
import ak.dev.irc.app.chat.enums.StreamGuestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StreamGuestRepository extends JpaRepository<StreamGuest, UUID> {

    Optional<StreamGuest> findByStreamIdAndUserId(UUID streamId, UUID userId);

    /** The guests in a given state for a stream, oldest-first (stable stage order). */
    List<StreamGuest> findByStreamIdAndStatusOrderByJoinedAtAsc(UUID streamId, StreamGuestStatus status);

    /** Pending hand-raises, oldest-first — the host's request queue. */
    List<StreamGuest> findByStreamIdAndStatusOrderByRequestedAtAsc(UUID streamId, StreamGuestStatus status);

    /** How many guests are currently up — enforces the stage limit. */
    long countByStreamIdAndStatus(UUID streamId, StreamGuestStatus status);

    /** Media-auth lookup: resolve a guest by their own publish path (a media path
     *  that is NOT a stream id). Used to authorize guest publish / playback. */
    Optional<StreamGuest> findByPublishPath(String publishPath);

    /** Take every currently-active guest off the stage (the whole stream ended).
     *  Clears their publish credentials so no stale key can re-publish. */
    @Modifying
    @Query("UPDATE StreamGuest g SET g.status = ak.dev.irc.app.chat.enums.StreamGuestStatus.REMOVED, " +
           "g.publishPath = null, g.publishKey = null, g.muted = false, g.leftAt = CURRENT_TIMESTAMP " +
           "WHERE g.streamId = :sid AND g.status = ak.dev.irc.app.chat.enums.StreamGuestStatus.ACTIVE")
    int removeAllActive(@Param("sid") UUID streamId);

    /** Purge every guest row for a stream — used when the stream is deleted. */
    @Modifying
    @Query("DELETE FROM StreamGuest g WHERE g.streamId = :sid")
    int deleteByStreamId(@Param("sid") UUID streamId);
}

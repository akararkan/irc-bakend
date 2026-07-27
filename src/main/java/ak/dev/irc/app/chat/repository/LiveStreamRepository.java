package ak.dev.irc.app.chat.repository;

import ak.dev.irc.app.chat.entity.LiveStream;
import ak.dev.irc.app.chat.enums.LiveStreamStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface LiveStreamRepository extends JpaRepository<LiveStream, UUID> {

    /** Discovery: live streams, most-watched first. */
    List<LiveStream> findByStatusOrderByViewerCountDesc(LiveStreamStatus status);

    /**
     * The "following is live" row: live streams whose host is one of the users
     * the viewer follows, most-recently-started first (the story-tray recency
     * feel — whoever just went live leads). The caller must skip an empty
     * {@code hostIds} set to avoid an {@code IN ()} that never matches.
     */
    List<LiveStream> findByStatusAndHostIdInOrderByStartedAtDesc(LiveStreamStatus status,
                                                                 Collection<UUID> hostIds);

    /**
     * A host's own streams (LIVE + ENDED), newest-first — the management list.
     * Served off {@code idx_stream_host}; with a clamped page this is
     * O(log N + pageSize), independent of history depth.
     */
    Page<LiveStream> findByHostIdOrderByStartedAtDesc(UUID hostId, Pageable pageable);
}

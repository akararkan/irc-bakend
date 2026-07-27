package ak.dev.irc.app.chat.repository;

import ak.dev.irc.app.chat.entity.StreamViewer;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StreamViewerRepository extends JpaRepository<StreamViewer, UUID> {

    Optional<StreamViewer> findByStreamIdAndUserId(UUID streamId, UUID userId);

    long countByStreamIdAndActiveTrue(UUID streamId);

    boolean existsByStreamIdAndUserIdAndActiveTrue(UUID streamId, UUID userId);

    /** The user ids currently watching — the broadcast set for live chat / viewer events. */
    @Query("SELECT v.userId FROM StreamViewer v WHERE v.streamId = :sid AND v.active = true")
    List<UUID> findActiveViewerIds(@Param("sid") UUID streamId);

    /** Mark every active viewer inactive (stream ended). */
    @Modifying
    @Query("UPDATE StreamViewer v SET v.active = false, v.leftAt = CURRENT_TIMESTAMP " +
           "WHERE v.streamId = :sid AND v.active = true")
    int deactivateAll(@Param("sid") UUID streamId);

    /** Purge every presence row for a stream — used when the stream is deleted.
     *  Indexed by stream_id, so O(rows-for-this-stream). */
    @Modifying
    @Query("DELETE FROM StreamViewer v WHERE v.streamId = :sid")
    int deleteByStreamId(@Param("sid") UUID streamId);
}

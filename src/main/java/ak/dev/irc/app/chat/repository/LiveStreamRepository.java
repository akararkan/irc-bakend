package ak.dev.irc.app.chat.repository;

import ak.dev.irc.app.chat.entity.LiveStream;
import ak.dev.irc.app.chat.enums.LiveStreamStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LiveStreamRepository extends JpaRepository<LiveStream, UUID> {

    /** Discovery: live streams, most-watched first. */
    List<LiveStream> findByStatusOrderByViewerCountDesc(LiveStreamStatus status);
}

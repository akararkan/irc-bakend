package ak.dev.irc.app.activity.service;

import ak.dev.irc.app.activity.dto.ReelViewResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface ReelViewService {

    ReelViewResponse recordWatch(UUID userId, UUID postId, Integer watchedSeconds);

    Page<ReelViewResponse> listMyWatched(UUID userId, Pageable pageable);

    void deleteOne(UUID userId, UUID reelViewId);

    int deleteAll(UUID userId);
}

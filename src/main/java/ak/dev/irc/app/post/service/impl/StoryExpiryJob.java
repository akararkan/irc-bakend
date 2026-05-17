package ak.dev.irc.app.post.service.impl;

import ak.dev.irc.app.post.service.StoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StoryExpiryJob {

    private final StoryService storyService;

    /**
     * Runs every hour. Soft-deletes stories where expires_at < now()
     * and the story is NOT attached to a highlight.
     * Highlighted stories are kept but inaccessible in the story tray —
     * they remain visible only within the highlight.
     */
    @Scheduled(fixedDelay = 3_600_000)
    public void expireStories() {
        log.debug("StoryExpiryJob: running expiry sweep");
        try {
            int count = storyService.expireStories();
            if (count > 0) log.info("StoryExpiryJob: expired {} stories", count);
        } catch (Exception e) {
            log.error("StoryExpiryJob: expiry sweep failed — {}", e.getMessage(), e);
        }
    }
}

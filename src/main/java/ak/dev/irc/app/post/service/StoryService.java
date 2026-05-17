package ak.dev.irc.app.post.service;

import ak.dev.irc.app.post.dto.story.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public interface StoryService {

    StoryResponse createTextStory(CreateTextStoryRequest req, UUID authorId);

    StoryResponse createMediaStory(CreateMediaStoryRequest req, MultipartFile media, UUID authorId);

    /** Share existing content (post, reel, QnA, research) as a story card. */
    StoryResponse shareToStory(ShareToStoryRequest req, UUID authorId);

    /** Feed story tray — grouped by author, unseen stories first. */
    List<StoryTrayGroup> getStoryTray(UUID viewerId);

    List<StoryResponse> getStoriesByAuthor(UUID authorId, UUID viewerId);

    void recordView(UUID storyId, UUID viewerId, int watchDurationMs);

    void reactToStory(UUID storyId, UUID viewerId, String emoji);

    void replyToStory(UUID storyId, UUID viewerId, String text);

    void voteOnPoll(UUID storyId, UUID voterId, String choice);

    void deleteStory(UUID storyId, UUID requesterId);

    /** Soft-deletes expired stories — called by StoryExpiryJob. */
    int expireStories();

    // ── Highlights ────────────────────────────────────────────────────────────
    StoryHighlightResponse createHighlight(CreateHighlightRequest req, UUID authorId);
    StoryHighlightResponse updateHighlight(UUID highlightId, UpdateHighlightRequest req, UUID authorId);
    StoryHighlightResponse addStoryToHighlight(UUID highlightId, UUID storyId, UUID authorId);
    void removeStoryFromHighlight(UUID highlightId, UUID storyId, UUID authorId);
    void deleteHighlight(UUID highlightId, UUID authorId);
    List<StoryHighlightResponse> getHighlights(UUID authorId);
    Page<StoryResponse> getHighlightStories(UUID highlightId, Pageable pageable);

    // ── Viewers (owner only) ──────────────────────────────────────────────────
    Page<StoryViewerResponse> getViewers(UUID storyId, UUID requesterId, Pageable pageable);
}

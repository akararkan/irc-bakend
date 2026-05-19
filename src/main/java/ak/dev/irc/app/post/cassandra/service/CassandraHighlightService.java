package ak.dev.irc.app.post.cassandra.service;

import ak.dev.irc.app.post.cassandra.entity.HighlightByAuthorEntity;
import ak.dev.irc.app.post.cassandra.entity.StoryByAuthorEntity;
import ak.dev.irc.app.post.cassandra.entity.StoryInHighlightEntity;
import ak.dev.irc.app.post.cassandra.entity.StoryLookupEntity;
import ak.dev.irc.app.post.cassandra.repository.HighlightByAuthorRepository;
import ak.dev.irc.app.post.cassandra.repository.StoryByAuthorRepository;
import ak.dev.irc.app.post.cassandra.repository.StoryInHighlightRepository;
import ak.dev.irc.app.post.cassandra.repository.StoryLookupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Story highlights — permanent archives of stories.
 *
 * Add-to-highlight semantics:
 *   The story is COPIED (snapshot) into stories_in_highlight, not referenced.
 *   This matters because the source row in stories_by_author will be TTL'd
 *   away in ≤24h, but the highlight must survive forever.
 *
 * Write ordering: the snapshot's created_at preserves the ORIGINAL story's
 * timestamp, so the highlight reads in true chronological order.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CassandraHighlightService {

    private final HighlightByAuthorRepository highlightRepo;
    private final StoryInHighlightRepository  storyInHighlightRepo;
    private final StoryLookupRepository       storyLookupRepo;
    private final StoryByAuthorRepository     storyByAuthorRepo;

    // ── Highlights ──────────────────────────────────────────────────────────

    public HighlightByAuthorEntity createHighlight(UUID authorId, String title,
                                                   String coverUrl, int displayOrder) {
        UUID id = UUID.randomUUID();
        HighlightByAuthorEntity h = HighlightByAuthorEntity.builder()
                .authorId(authorId).displayOrder(displayOrder).highlightId(id)
                .title(title).coverUrl(coverUrl).createdAt(Instant.now())
                .build();
        highlightRepo.save(h);
        return h;
    }

    public List<HighlightByAuthorEntity> listFor(UUID authorId) {
        return highlightRepo.listFor(authorId);
    }

    public void deleteHighlight(HighlightByAuthorEntity h) {
        highlightRepo.delete(h.getAuthorId(), h.getDisplayOrder(), h.getHighlightId());
        // Stories inside are intentionally left to TTL via their own table (none here),
        // but we wipe them too for clean removal.
        for (StoryInHighlightEntity s : storyInHighlightRepo.listFor(h.getHighlightId())) {
            storyInHighlightRepo.delete(s.getHighlightId(), s.getCreatedAt(), s.getStoryId());
        }
    }

    // ── Stories in highlight ────────────────────────────────────────────────

    /**
     * Snapshot an existing story into a highlight. Looks up the source row via
     * story_lookup → stories_by_author to capture the full content (because
     * the original may be gone soon). Author check is enforced.
     */
    public Optional<StoryInHighlightEntity> addStoryToHighlight(UUID highlightId,
                                                                UUID storyId,
                                                                UUID requesterId) {
        StoryLookupEntity meta = storyLookupRepo.findById(storyId).orElse(null);
        if (meta == null) return Optional.empty();
        if (!meta.getAuthorId().equals(requesterId)) {
            throw new SecurityException("Not the story author");
        }

        // We need to find the actual story content. The author's partition is
        // tight so this is cheap — and most stories archived to a highlight
        // are still inside the 24h window.
        StoryByAuthorEntity source = null;
        for (StoryByAuthorEntity s : storyByAuthorRepo.activeStories(meta.getAuthorId())) {
            if (s.getStoryId().equals(storyId)) { source = s; break; }
        }
        if (source == null) {
            log.warn("[HIGHLIGHT] story {} no longer exists — already expired?", storyId);
            return Optional.empty();
        }

        StoryInHighlightEntity row = StoryInHighlightEntity.builder()
                .highlightId(highlightId)
                .createdAt(source.getCreatedAt())
                .storyId(storyId)
                .authorId(source.getAuthorId())
                .storyType(source.getStoryType())
                .mediaUrl(source.getMediaUrl())
                .thumbnailUrl(source.getThumbnailUrl())
                .textContent(source.getTextContent())
                .build();
        storyInHighlightRepo.save(row);
        return Optional.of(row);
    }

    public void removeStoryFromHighlight(UUID highlightId, Instant createdAt, UUID storyId) {
        storyInHighlightRepo.delete(highlightId, createdAt, storyId);
    }

    public List<StoryInHighlightEntity> storiesIn(UUID highlightId) {
        return storyInHighlightRepo.listFor(highlightId);
    }
}

package ak.dev.irc.app.post.cassandra.service;

import ak.dev.irc.app.common.messages.PostMessages;
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

    /**
     * Re-order an author's highlights to match the supplied id sequence.
     *
     * <p>{@code display_order} is part of the {@code highlights_by_author}
     * clustering key, so Cassandra can't UPDATE it in place — we DELETE
     * each row at its current key and re-INSERT under the new order. The
     * authorId check defends against a hostile caller passing somebody
     * else's highlight ids in the list.</p>
     *
     * <p>Idempotent: re-running with the same order is a no-op net effect
     * (every row ends up at the same position it was already at).</p>
     *
     * @param authorId           highlight owner (must match every row's authorId)
     * @param orderedHighlightIds the desired left-to-right order on the profile rail
     * @return the rewritten rows, newest order first
     */
    public List<HighlightByAuthorEntity> reorderHighlights(UUID authorId,
                                                           List<UUID> orderedHighlightIds) {
        if (orderedHighlightIds == null || orderedHighlightIds.isEmpty()) {
            return List.of();
        }
        // Snapshot the author's current rows (we need title + coverUrl + createdAt
        // to re-insert, since they live on the same row as the clustering key).
        List<HighlightByAuthorEntity> current = highlightRepo.listFor(authorId);
        java.util.Map<UUID, HighlightByAuthorEntity> byId = new java.util.HashMap<>(current.size());
        for (HighlightByAuthorEntity h : current) {
            if (!authorId.equals(h.getAuthorId())) continue; // belt-and-braces
            byId.put(h.getHighlightId(), h);
        }

        List<HighlightByAuthorEntity> rewritten = new java.util.ArrayList<>(orderedHighlightIds.size());
        int newOrder = 0;
        for (UUID id : orderedHighlightIds) {
            HighlightByAuthorEntity row = byId.get(id);
            if (row == null) continue; // foreign id — silently skip
            if (row.getDisplayOrder() != null && row.getDisplayOrder() == newOrder) {
                rewritten.add(row); // already in the right slot, nothing to do
                newOrder++;
                continue;
            }
            // DELETE old clustering-key row.
            highlightRepo.delete(authorId, row.getDisplayOrder(), row.getHighlightId());
            // INSERT new row with same payload + new order. Other fields
            // (title / coverUrl / createdAt) survive untouched.
            HighlightByAuthorEntity moved = HighlightByAuthorEntity.builder()
                    .authorId(authorId).displayOrder(newOrder).highlightId(id)
                    .title(row.getTitle()).coverUrl(row.getCoverUrl())
                    .createdAt(row.getCreatedAt())
                    .build();
            highlightRepo.save(moved);
            rewritten.add(moved);
            newOrder++;
        }
        return rewritten;
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
            throw new SecurityException(PostMessages.NOT_STORY_AUTHOR_MSG);
        }
        // The target highlight must also belong to the requester — otherwise a
        // caller could inject their story into somebody else's highlight rail.
        boolean ownsHighlight = highlightRepo.listFor(requesterId).stream()
                .anyMatch(h -> h.getHighlightId().equals(highlightId));
        if (!ownsHighlight) {
            throw new SecurityException(PostMessages.NOT_HIGHLIGHT_OWNER_MSG);
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

    /**
     * Remove a snapshotted story from a highlight — owner only. The row
     * carries the story author (== highlight owner for self-curated rails),
     * so ownership is verified against the stored row before the delete.
     */
    public void removeStoryFromHighlight(UUID highlightId, Instant createdAt,
                                         UUID storyId, UUID requesterId) {
        boolean owned = storyInHighlightRepo.listFor(highlightId).stream()
                .anyMatch(r -> r.getStoryId().equals(storyId)
                            && r.getAuthorId() != null
                            && r.getAuthorId().equals(requesterId));
        if (!owned) {
            throw new SecurityException(PostMessages.NOT_HIGHLIGHT_OWNER_MSG);
        }
        storyInHighlightRepo.delete(highlightId, createdAt, storyId);
    }

    public List<StoryInHighlightEntity> storiesIn(UUID highlightId) {
        return storyInHighlightRepo.listFor(highlightId);
    }
}

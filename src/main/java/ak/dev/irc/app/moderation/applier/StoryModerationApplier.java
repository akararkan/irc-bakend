package ak.dev.irc.app.moderation.applier;

import ak.dev.irc.app.moderation.entity.ModerationCase;
import ak.dev.irc.app.moderation.enums.ModeratedEntityType;
import ak.dev.irc.app.moderation.service.ModerationApplier;
import ak.dev.irc.app.post.cassandra.entity.StoryByAuthorEntity;
import ak.dev.irc.app.post.cassandra.entity.StoryLookupEntity;
import ak.dev.irc.app.post.cassandra.repository.StoryByAuthorRepository;
import ak.dev.irc.app.post.cassandra.repository.StoryLookupRepository;
import ak.dev.irc.app.post.cassandra.service.CassandraStoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Releases or removes a story whose verdict arrived after creation.
 *
 * <p>The awkward part is TTL. Story rows are written with a per-row
 * {@code USING TTL} so they vanish at 8h/16h/24h without a sweeper, and a plain
 * re-save would reset that TTL to the table default — silently resurrecting a
 * story that should already have expired. Every write here therefore recomputes
 * the <em>remaining</em> TTL from {@code expiresAt}, which is the same trick
 * {@code CassandraStoryPollService} uses for derived rows.</p>
 *
 * <p>If the hold outlived the story, there is nothing to release: the row is
 * already gone and the case is simply marked applied.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoryModerationApplier implements ModerationApplier {

    private final StoryLookupRepository lookupRepository;
    private final StoryByAuthorRepository storyRepository;
    private final CassandraStoryService storyService;
    private final CassandraOperations cassandraOps;

    @Override
    public ModeratedEntityType supports() {
        return ModeratedEntityType.STORY;
    }

    @Override
    public void onApproved(ModerationCase moderationCase) {
        StoryLookupEntity meta = lookup(moderationCase);
        if (meta == null) return;
        if (meta.getModerationStatus() == null) return;   // already released

        StoryByAuthorEntity story = storyRepository.activeStories(meta.getAuthorId()).stream()
                .filter(s -> s.getStoryId().equals(meta.getStoryId()))
                .findFirst().orElse(null);
        if (story == null) {
            log.debug("[MODERATION] story {} expired before its verdict landed", meta.getStoryId());
            return;
        }

        int ttl = remainingTtlSeconds(story.getExpiresAt());
        if (ttl <= 0) return;
        InsertOptions ttlOpts = InsertOptions.builder().ttl(ttl).build();

        story.setModerationStatus(null);
        cassandraOps.insert(story, ttlOpts);
        meta.setModerationStatus(null);
        cassandraOps.insert(meta, ttlOpts);

        log.info("[MODERATION] story {} approved and visible", meta.getStoryId());
    }

    @Override
    public void onRejected(ModerationCase moderationCase) {
        StoryLookupEntity meta = lookup(moderationCase);
        if (meta == null) return;
        try {
            // The author-delete path also tears down the attached poll and pushes
            // STORY_REMOVED to every tray whose ring this story lit.
            storyService.deleteStory(meta.getStoryId(), meta.getAuthorId());
            log.info("[MODERATION] story {} rejected and removed", meta.getStoryId());
        } catch (Exception alreadyGone) {
            log.debug("[MODERATION] story {} already removed: {}",
                    meta.getStoryId(), alreadyGone.getMessage());
        }
    }

    /**
     * Seconds left before the story's own expiry. Re-saving with the table
     * default instead would extend a story past the lifetime its author chose.
     */
    private static int remainingTtlSeconds(Instant expiresAt) {
        if (expiresAt == null) return (int) Duration.ofHours(24).toSeconds();
        long remaining = Duration.between(Instant.now(), expiresAt).toSeconds();
        return (int) Math.max(0, remaining);
    }

    private StoryLookupEntity lookup(ModerationCase moderationCase) {
        try {
            UUID storyId = UUID.fromString(moderationCase.getEntityRef());
            return lookupRepository.findById(storyId).orElse(null);
        } catch (IllegalArgumentException badRef) {
            log.warn("[MODERATION] case {} has a non-UUID story ref '{}'",
                    moderationCase.getId(), moderationCase.getEntityRef());
            return null;
        }
    }
}

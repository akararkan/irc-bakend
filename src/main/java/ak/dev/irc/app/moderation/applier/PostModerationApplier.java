package ak.dev.irc.app.moderation.applier;

import ak.dev.irc.app.moderation.entity.ModerationCase;
import ak.dev.irc.app.moderation.enums.ModeratedEntityType;
import ak.dev.irc.app.moderation.service.ModerationApplier;
import ak.dev.irc.app.post.cassandra.entity.PostByIdEntity;
import ak.dev.irc.app.post.cassandra.repository.PostByIdRepository;
import ak.dev.irc.app.post.cassandra.service.CassandraPostService;
import ak.dev.irc.app.post.search.service.PostSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes or hides a post whose verdict arrived after the create request had
 * already returned (MODERATION_ROADMAP.md §5.2 step 4).
 *
 * <p>Approval here is not just a status flip: it has to run the publication side
 * effects {@code createPost} deliberately skipped — follower fan-out, search
 * indexing, tag/mention extraction, activity feed. Those live in
 * {@link CassandraPostService#publishSideEffects} so there is exactly one
 * implementation of a 50k-follower fan-out rather than two that can drift.</p>
 *
 * <p>Idempotent by status check: the sweeper re-drives cases whose first apply
 * failed, and a second fan-out would notify every follower twice.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostModerationApplier implements ModerationApplier {

    private final PostByIdRepository postRepository;
    private final CassandraPostService postService;
    private final PostSearchService postSearchService;
    private final ak.dev.irc.app.common.tag.service.ContentTagService contentTagService;

    @Override
    public ModeratedEntityType supports() {
        return ModeratedEntityType.POST;
    }

    @Override
    public void onApproved(ModerationCase moderationCase) {
        PostByIdEntity post = load(moderationCase);
        if (post == null) return;
        if ("PUBLISHED".equalsIgnoreCase(post.getStatus())) {
            return;   // already published — a re-drive, not a new approval
        }

        post.setStatus("PUBLISHED");
        post.setUpdatedAt(Instant.now());
        postRepository.save(post);

        String coverMedia = post.getMediaUrls() == null || post.getMediaUrls().isEmpty()
                ? null : post.getMediaUrls().get(0);

        if (moderationCase.getRevision() > 1) {
            // This case is an EDIT of a post that already published once, so the
            // follower fan-out has run and every follower already holds a feed row.
            // Re-running publishSideEffects would copy the post into up to 50,000
            // feed_by_user rows a second time and fire a POST_NEW notification for
            // each — the loudest possible way to fail. Only the surfaces that store
            // a copy of the text need refreshing.
            postService.republishEditedText(post, coverMedia);
            log.info("[MODERATION] post {} edit approved and re-indexed", post.getId());
            return;
        }

        postService.publishSideEffects(post, preview(post.getTextContent()), coverMedia);
        log.info("[MODERATION] post {} approved and published", post.getId());
    }

    @Override
    public void onRejected(ModerationCase moderationCase) {
        PostByIdEntity post = load(moderationCase);
        if (post == null) return;
        if ("REJECTED".equalsIgnoreCase(post.getStatus())) return;

        post.setStatus("REJECTED");
        post.setUpdatedAt(Instant.now());
        postRepository.save(post);

        // A post can reach here already published — an SLA fail-open, or an admin
        // reversing an inline approval — so the two surfaces that keep their own
        // copy of the text have to be swept, exactly as AdminContentService's
        // manual takedown does. Neither is covered by the read-path status gate:
        // Elasticsearch stores the body, and the hashtag/tag feeds store a
        // denormalised preview that no hydrator ever re-checks.
        postSearchService.deleteAsync(post.getId());
        try {
            contentTagService.untag(post.getId());
        } catch (Exception ex) {
            log.warn("[MODERATION] untag failed for rejected post {}: {}",
                    post.getId(), ex.getMessage());
        }

        log.info("[MODERATION] post {} rejected and hidden", post.getId());
    }

    private PostByIdEntity load(ModerationCase moderationCase) {
        try {
            UUID postId = UUID.fromString(moderationCase.getEntityRef());
            PostByIdEntity post = postRepository.findById(postId).orElse(null);
            if (post == null) {
                // Hard-deleted by its author while held. Nothing to apply, and the
                // case row keeps the evidence — that is the point of storing the
                // text in moderation_case_fields rather than pointing at the post.
                log.debug("[MODERATION] post {} gone before verdict applied", postId);
            }
            return post;
        } catch (IllegalArgumentException badRef) {
            log.warn("[MODERATION] case {} has a non-UUID post ref '{}'",
                    moderationCase.getId(), moderationCase.getEntityRef());
            return null;
        }
    }

    /** Mirrors {@code CassandraPostService.preview} — the 280-char denorm cut. */
    private static String preview(String text) {
        if (text == null) return null;
        return text.length() > 280 ? text.substring(0, 280) : text;
    }
}

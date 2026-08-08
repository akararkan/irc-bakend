package ak.dev.irc.app.moderation.applier;

import ak.dev.irc.app.moderation.entity.ModerationCase;
import ak.dev.irc.app.moderation.enums.ModeratedEntityType;
import ak.dev.irc.app.moderation.service.ModerationApplier;
import ak.dev.irc.app.post.cassandra.entity.CommentByPostEntity;
import ak.dev.irc.app.post.cassandra.entity.CommentLookupEntity;
import ak.dev.irc.app.post.cassandra.entity.ReplyByCommentEntity;
import ak.dev.irc.app.post.cassandra.repository.CommentByPostRepository;
import ak.dev.irc.app.post.cassandra.repository.CommentLookupRepository;
import ak.dev.irc.app.post.cassandra.repository.ReplyByCommentRepository;
import ak.dev.irc.app.post.cassandra.service.CassandraCommentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Releases or hides a post comment/reply whose verdict landed after the create
 * request returned.
 *
 * <p>Everything routes through {@code comment_lookup} — it is the only table
 * keyed by comment id alone, and without it there is no way to find the row in
 * either {@code comments_by_post} (partitioned by post) or
 * {@code replies_by_comment} (partitioned by parent). That is why the lookup row
 * carries a mirror of the moderation status.</p>
 *
 * <p>Rejection uses the same hard delete the author's own delete does. Comments
 * have no soft-delete state to fall back on — the {@code is_deleted} column is
 * vestigial and no read path filters on it — so leaving a rejected row in place
 * would mean relying entirely on the hydrator gate, with the raw text still
 * sitting in the partition. Deleting it is both the honest outcome and the one
 * the existing admin takedown already performs.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostCommentModerationApplier implements ModerationApplier {

    private final CommentLookupRepository lookupRepository;
    private final CommentByPostRepository commentRepository;
    private final ReplyByCommentRepository replyRepository;
    private final CassandraCommentService commentService;

    @Override
    public ModeratedEntityType supports() {
        return ModeratedEntityType.POST_COMMENT;
    }

    @Override
    public void onApproved(ModerationCase moderationCase) {
        CommentLookupEntity meta = lookup(moderationCase);
        if (meta == null) return;
        if (meta.getModerationStatus() == null) return;   // already released

        String text;
        String mediaUrl;
        String mediaType = null;
        if (Boolean.TRUE.equals(meta.getReply())) {
            ReplyByCommentEntity row = replyRepository
                    .findRow(meta.getParentId(), meta.getCreatedAt(), meta.getCommentId())
                    .orElse(null);
            if (row == null) return;
            text = row.getTextContent();
            mediaUrl = row.getMediaUrl();
            replyRepository.setModerationStatus(meta.getParentId(), meta.getCreatedAt(),
                    meta.getCommentId(), null);
        } else {
            CommentByPostEntity row = commentRepository
                    .findRow(meta.getPostId(), meta.getCreatedAt(), meta.getCommentId())
                    .orElse(null);
            if (row == null) return;
            text = row.getTextContent();
            mediaUrl = row.getMediaUrl();
            mediaType = row.getMediaType();
            commentRepository.setModerationStatus(meta.getPostId(), meta.getCreatedAt(),
                    meta.getCommentId(), null);
        }

        meta.setModerationStatus(null);
        lookupRepository.save(meta);

        // Only now does the text reach other users: realtime broadcast, the
        // "X commented on your post" bell, and @mention pings.
        commentService.publishComment(meta.getPostId(), meta.getCommentId(), meta.getParentId(),
                meta.getAuthorId(), text, mediaUrl, mediaType);
        log.info("[MODERATION] comment {} approved and published", meta.getCommentId());
    }

    @Override
    public void onRejected(ModerationCase moderationCase) {
        CommentLookupEntity meta = lookup(moderationCase);
        if (meta == null) return;

        try {
            // Reuse the author-delete path so counters, the reply partition and
            // the lookup row are all cleaned up exactly as they would be for a
            // manual delete — reimplementing that cascade here is how counters
            // drift.
            commentService.deleteComment(meta.getCommentId(), meta.getAuthorId());
            log.info("[MODERATION] comment {} rejected and removed", meta.getCommentId());
        } catch (IllegalArgumentException alreadyGone) {
            log.debug("[MODERATION] comment {} already removed", meta.getCommentId());
        }
    }

    private CommentLookupEntity lookup(ModerationCase moderationCase) {
        try {
            UUID commentId = UUID.fromString(moderationCase.getEntityRef());
            return lookupRepository.findById(commentId).orElse(null);
        } catch (IllegalArgumentException badRef) {
            log.warn("[MODERATION] case {} has a non-UUID comment ref '{}'",
                    moderationCase.getId(), moderationCase.getEntityRef());
            return null;
        }
    }
}

package ak.dev.irc.app.moderation.service;

import ak.dev.irc.app.common.messages.ModerationMessages;
import ak.dev.irc.app.moderation.entity.ModerationCase;
import ak.dev.irc.app.moderation.enums.ModeratedEntityType;
import ak.dev.irc.app.moderation.enums.ModerationStatus;
import ak.dev.irc.app.user.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;

/**
 * Tells an author what happened to their content.
 *
 * <p>Silence would be the worst option here: §5.1 recommends letting the author
 * see their own held content in a visibly pending state rather than hiding it,
 * and a rejection the author is never told about is indistinguishable from the
 * platform losing their post.</p>
 *
 * <p>Deliberately reuses {@code NotificationService.sendSystemNotification},
 * the same path admin takedowns already use, rather than minting a new
 * {@code NotificationKind}. Adding a kind means editing two enums that are joined
 * by name plus an exhaustive email-template switch, for no user-visible gain —
 * these read as system messages either way.</p>
 *
 * <p>Never throws. A notification failure must not roll back a moderation
 * verdict.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModerationNotifier {

    private final NotificationService notificationService;

    private static final Map<ModeratedEntityType, String> LABELS =
            new EnumMap<>(ModeratedEntityType.class);

    static {
        LABELS.put(ModeratedEntityType.POST, "post");
        LABELS.put(ModeratedEntityType.POST_COMMENT, "comment");
        LABELS.put(ModeratedEntityType.STORY, "story");
        LABELS.put(ModeratedEntityType.STORY_POLL, "story poll");
        LABELS.put(ModeratedEntityType.RESEARCH, "research paper");
        LABELS.put(ModeratedEntityType.RESEARCH_COMMENT, "comment");
        LABELS.put(ModeratedEntityType.QNA_QUESTION, "question");
        LABELS.put(ModeratedEntityType.QNA_ANSWER, "answer");
        LABELS.put(ModeratedEntityType.CHAT_MESSAGE, "message");
        LABELS.put(ModeratedEntityType.CHANNEL, "channel details");
        LABELS.put(ModeratedEntityType.STREAM_META, "stream details");
        LABELS.put(ModeratedEntityType.LIVE_CHAT, "live chat message");
        LABELS.put(ModeratedEntityType.CONTENT_ANNOTATION, "content");
    }

    /**
     * One notification per terminal or review transition.
     *
     * <p>Live chat is skipped: those lines are ephemeral by design and a bell for
     * every dropped chat message would be its own kind of abuse.</p>
     */
    public void notifyAuthor(ModerationCase moderationCase) {
        if (moderationCase == null || moderationCase.getAuthorId() == null) return;
        if (moderationCase.getEntityType().ephemeral()) return;

        String label = LABELS.getOrDefault(moderationCase.getEntityType(), "content");
        try {
            switch (moderationCase.getStatus()) {
                case REJECTED -> notificationService.sendSystemNotification(
                        moderationCase.getAuthorId(),
                        ModerationMessages.NOTIF_CONTENT_REJECTED_TITLE,
                        ModerationMessages.NOTIF_CONTENT_REJECTED_BODY.formatted(label));
                case IN_REVIEW -> notificationService.sendSystemNotification(
                        moderationCase.getAuthorId(),
                        ModerationMessages.NOTIF_CONTENT_UNDER_REVIEW_TITLE,
                        ModerationMessages.NOTIF_CONTENT_UNDER_REVIEW_BODY.formatted(label));
                case APPROVED -> notifyApproved(moderationCase, label);
                case PENDING -> {
                    // Still inside the hold window — nothing has happened yet.
                }
            }
        } catch (Exception ex) {
            log.debug("[MODERATION] author notification skipped for case {}: {}",
                    moderationCase.getId(), ex.getMessage());
        }
    }

    /**
     * Only the <em>deferred</em> approval is worth a bell. Content that cleared
     * inline never left the author's sight, so "your post is live" would be noise
     * — the applier is the only caller that reaches this with an approval.
     */
    private void notifyApproved(ModerationCase moderationCase, String label) {
        boolean wasHeldForAWhile = moderationCase.isSlaBreached()
                || moderationCase.getAttempts() > 0
                || ModerationOutcome.REASON_ADMIN.equals(moderationCase.getReasonCode());
        if (!wasHeldForAWhile) return;
        notificationService.sendSystemNotification(
                moderationCase.getAuthorId(),
                ModerationMessages.NOTIF_CONTENT_APPROVED_TITLE,
                ModerationMessages.NOTIF_CONTENT_APPROVED_BODY.formatted(label));
    }

    /** Human-readable entity label, reused by the admin queue DTOs. */
    public static String label(ModeratedEntityType type) {
        return LABELS.getOrDefault(type, "content");
    }

    /** True when the author should be shown the "held for review" banner. */
    public static boolean showsHeldBanner(ModerationStatus status) {
        return status == ModerationStatus.PENDING || status == ModerationStatus.IN_REVIEW;
    }
}

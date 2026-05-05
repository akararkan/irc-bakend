package ak.dev.irc.app.user.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * Coarse category grouping that the inbox UI uses for tabs / filters.
 *
 * <p>Derived from {@link NotificationType} at response time so storage stays
 * untouched. Categories also drive the {@code mark-by-category} bulk endpoint.</p>
 */
public enum NotificationCategory {

    /** Posts: reactions, comments, replies, shares, lifecycle, fan-out. */
    POSTS,
    /** Q&A: questions, answers, accepts, feedback, reactions. */
    QNA,
    /** Research: publications, comments, reactions, citations. */
    RESEARCH,
    /** Mentions across any source (post / comment / question / answer / research). */
    MENTIONS,
    /** Social: follows, blocks, unblocks, restrictions, connection requests. */
    SOCIAL,
    /** System / account / announcements. */
    SYSTEM;

    private static final Set<NotificationType> POST_TYPES = EnumSet.of(
            NotificationType.POST_NEW,
            NotificationType.POST_REACTED,
            NotificationType.POST_COMMENTED,
            NotificationType.POST_COMMENT_REPLIED,
            NotificationType.POST_COMMENT_REACTED,
            NotificationType.POST_SHARED,
            NotificationType.POST_MENTIONED
    );

    private static final Set<NotificationType> QNA_TYPES = EnumSet.of(
            NotificationType.QUESTION_NEW,
            NotificationType.QUESTION_ANSWERED,
            NotificationType.ANSWER_REPLIED,
            NotificationType.ANSWER_REACTED,
            NotificationType.ANSWER_ACCEPTED,
            NotificationType.ANSWER_FEEDBACK_RECEIVED
    );

    private static final Set<NotificationType> RESEARCH_TYPES = EnumSet.of(
            NotificationType.PUBLICATION_LIKED,
            NotificationType.PUBLICATION_COMMENTED,
            NotificationType.PUBLICATION_CITED
    );

    private static final Set<NotificationType> SOCIAL_TYPES = EnumSet.of(
            NotificationType.NEW_FOLLOWER,
            NotificationType.UNFOLLOWED,
            NotificationType.BLOCKED,
            NotificationType.UNBLOCKED,
            NotificationType.RESTRICTED,
            NotificationType.CONNECTION_REQUEST,
            NotificationType.CONNECTION_ACCEPTED
    );

    private static final Set<NotificationType> SYSTEM_TYPES = EnumSet.of(
            NotificationType.SYSTEM_MESSAGE,
            NotificationType.SYSTEM_ANNOUNCEMENT,
            NotificationType.ACCOUNT_WARNING
    );

    /**
     * Resolve the high-level inbox tab for a notification.
     *
     * <p>{@code USER_MENTIONED} always lands in {@link #MENTIONS} regardless of
     * source; {@code POST_NEW} for a research publication still lands in
     * {@link #POSTS} (the type is reused intentionally).</p>
     */
    public static NotificationCategory of(NotificationType type) {
        if (type == null) return SYSTEM;
        if (type == NotificationType.USER_MENTIONED) return MENTIONS;
        if (POST_TYPES.contains(type))     return POSTS;
        if (QNA_TYPES.contains(type))      return QNA;
        if (RESEARCH_TYPES.contains(type)) return RESEARCH;
        if (SOCIAL_TYPES.contains(type))   return SOCIAL;
        if (SYSTEM_TYPES.contains(type))   return SYSTEM;
        return SYSTEM;
    }

    public Set<NotificationType> types() {
        return switch (this) {
            case POSTS    -> POST_TYPES;
            case QNA      -> QNA_TYPES;
            case RESEARCH -> RESEARCH_TYPES;
            case MENTIONS -> EnumSet.of(NotificationType.USER_MENTIONED);
            case SOCIAL   -> SOCIAL_TYPES;
            case SYSTEM   -> SYSTEM_TYPES;
        };
    }
}

package ak.dev.irc.app.rabbitmq.event.user;

/**
 * Where a mention was detected. The consumer uses this to set
 * {@code Notification.resourceType} and to render an appropriate body.
 */
public enum MentionSource {
    POST,
    POST_COMMENT,
    RESEARCH,
    RESEARCH_COMMENT,
    QUESTION,
    QUESTION_ANSWER
}

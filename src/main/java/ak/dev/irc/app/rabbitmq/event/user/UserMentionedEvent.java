package ak.dev.irc.app.rabbitmq.event.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.Set;
import java.util.UUID;

/**
 * Fired whenever a user authors content that contains {@code @username} mentions
 * or the special {@code @followers} token. Routing key {@code user.social.mentioned}.
 *
 * <p>Resolution semantics:
 * <ul>
 *   <li>{@link #mentionedUserIds} is the already-resolved, deduped set of users
 *       to notify directly. The publisher resolves and filters (skip self,
 *       skip blocked).</li>
 *   <li>{@link #notifyFollowers} expands at consumer time via the existing
 *       {@code fanOutToFollowers} helper. Followers who are also in
 *       {@link #mentionedUserIds} get only one notification.</li>
 * </ul>
 *
 * Mutable fields with no-arg constructor + setters are required for
 * Jackson deserialisation by Spring AMQP's typed message converter.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserMentionedEvent implements Serializable {

    /** Author of the content — never receives a self-notification. */
    private UUID mentionerId;
    private String mentionerUsername;

    /** What kind of resource the mention lives in. Determines resourceType + copy. */
    private MentionSource sourceType;

    /** Primary id of the resource (post id, comment id, research id, ...). */
    private UUID sourceId;

    /**
     * Optional parent id for nested resources — e.g. a comment's post id, an
     * answer's question id, a research-comment's research id. Used so the
     * client can deep-link to the parent rather than the nested resource.
     */
    private UUID sourceParentId;

    /** Pre-resolved, deduped, blocks-filtered, self-excluded recipients. */
    private Set<UUID> mentionedUserIds;

    /** True only when the author used {@code @followers} on a creation event. */
    private boolean notifyFollowers;

    /** Short preview of the text — used in notification body for context. */
    private String snippet;
}

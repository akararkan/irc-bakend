package ak.dev.irc.app.user.mapper;

import ak.dev.irc.app.user.dto.response.NotificationResponse;
import ak.dev.irc.app.user.entity.Notification;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.enums.NotificationCategory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class NotificationMapper {

    public NotificationResponse toResponse(Notification n) {
        // Primary actor: prefer the freshest contributor on aggregated rows.
        User primary = n.getLastActor() != null ? n.getLastActor() : n.getActor();
        UUID   actorId    = primary != null ? primary.getId()           : null;
        String actorUser  = primary != null ? primary.getUsername()     : null;
        String actorName  = primary != null ? primary.getFullName()     : null;
        String actorImage = primary != null ? primary.getProfileImage() : null;

        UUID   lastActorId   = n.getLastActor() != null ? n.getLastActor().getId()       : actorId;
        String lastActorUser = n.getLastActor() != null ? n.getLastActor().getUsername() : actorUser;

        long count = n.getAggregateCount() != null ? n.getAggregateCount() : 1L;

        String deepLink = buildDeepLink(n.getResourceType(), n.getResourceId());

        return new NotificationResponse(
                n.getId(),
                n.getType(),
                NotificationCategory.of(n.getType()),
                n.getTitle(),
                n.getBody(),
                actorId,
                actorUser,
                actorName,
                actorImage,
                count,
                lastActorId,
                lastActorUser,
                n.getResourceId(),
                n.getResourceType(),
                deepLink,
                n.isRead(),
                n.getReadAt(),
                n.getCreatedAt()
        );
    }

    /**
     * Build a client-side deep-link from {@code (resourceType, resourceId)}.
     * Returns null when the resource is opaque or unknown — clients then fall
     * back to type-based routing.
     */
    private static String buildDeepLink(String resourceType, UUID resourceId) {
        if (resourceType == null || resourceId == null) return null;
        return switch (resourceType) {
            case "Post"            -> "/posts/" + resourceId;
            case "PostComment"     -> "/posts/" + resourceId; // resourceId is the post id for comments
            case "Question"        -> "/questions/" + resourceId;
            case "QuestionAnswer"  -> "/questions/" + resourceId;
            case "Research"        -> "/research/" + resourceId;
            case "ResearchComment" -> "/research/" + resourceId;
            case "User"            -> "/users/" + resourceId;
            default                -> null;
        };
    }
}

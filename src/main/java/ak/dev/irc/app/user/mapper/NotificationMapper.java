package ak.dev.irc.app.user.mapper;

import ak.dev.irc.app.user.dto.response.NotificationResponse;
import ak.dev.irc.app.user.entity.Notification;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class NotificationMapper {

    public NotificationResponse toResponse(Notification n) {
        UUID   actorId    = n.getActor() != null ? n.getActor().getId()           : null;
        String actorUser  = n.getActor() != null ? n.getActor().getUsername()     : null;
        String actorImage = n.getActor() != null ? n.getActor().getProfileImage() : null;

        return new NotificationResponse(
                n.getId(),
                n.getType(),
                n.getTitle(),
                n.getBody(),
                actorId,
                actorUser,
                actorImage,
                n.getResourceId(),
                n.getResourceType(),
                n.isRead(),
                n.getReadAt(),
                n.getCreatedAt()
        );
    }
}

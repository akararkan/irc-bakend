package ak.dev.irc.app.user.mapper;

import ak.dev.irc.app.user.dto.response.UserAttachmentResponse;
import ak.dev.irc.app.user.dto.response.UserContactResponse;
import ak.dev.irc.app.user.dto.response.UserLinkResponse;
import ak.dev.irc.app.user.dto.response.UserResponse;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.entity.UserAttachment;
import ak.dev.irc.app.user.entity.UserContact;
import ak.dev.irc.app.user.entity.UserLink;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    public UserResponse toResponse(User u, long followers, long following) {
        return new UserResponse(
                u.getId(),
                u.getFname(),
                u.getLname(),
                u.getUsername(),
                u.getEmail(),
                u.getLocation(),
                u.getProfileImage(),
                u.getProfileBio(),
                u.getSelfDescriber(),
                u.getRole(),
                u.isProfileLocked(),
                u.isEmailVerified(),
                followers,
                following,
                u.getLinks().stream()
                        .filter(UserLink::isPublic)
                        .map(this::toLinkResponse)
                        .toList(),
                u.getContacts().stream()
                        .filter(UserContact::isPublic)
                        .map(this::toContactResponse)
                        .toList(),
                u.getAttachments().stream()
                        .map(this::toAttachmentResponse)
                        .toList(),
                u.getCreatedAt()
        );
    }

    public UserLinkResponse toLinkResponse(UserLink l) {
        return new UserLinkResponse(
                l.getId(),
                l.getPlatform(),
                l.getDescription(),
                l.getUrl(),
                l.isPublic(),
                l.getDisplayOrder()
        );
    }

    public UserContactResponse toContactResponse(UserContact c) {
        return new UserContactResponse(
                c.getId(),
                c.getPlatform(),
                c.getValue(),
                c.isPublic()
        );
    }

    public UserAttachmentResponse toAttachmentResponse(UserAttachment a) {
        return new UserAttachmentResponse(
                a.getId(),
                a.getFileUrl(),
                a.getFileName(),
                a.getFileType(),
                a.getFileSize(),
                a.getDescription(),
                a.getCreatedAt()
        );
    }
}

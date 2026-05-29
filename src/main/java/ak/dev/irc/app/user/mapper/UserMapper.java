package ak.dev.irc.app.user.mapper;

import ak.dev.irc.app.user.dto.response.*;
import ak.dev.irc.app.user.entity.*;
import ak.dev.irc.app.user.enums.BadgeType;
import ak.dev.irc.app.user.enums.Role;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class UserMapper {

    /**
     * Full mapping including public profile data.
     * Call this for any user response — the profile is always loaded within the transaction.
     */
    public UserResponse toResponse(User u) {
        return toResponse(u, false);
    }

    /**
     * @param includePrivateFields true for the owner's own profile (includes profileViews etc.)
     */
    public UserResponse toResponse(User u, boolean includePrivateFields) {
        UserProfile p = u.getProfile();
        return new UserResponse(
            u.getId(),
            u.getFname(),
            u.getLname(),
            u.getUsername(),
            u.getEmail(),
            u.getRole(),
            resolveBadges(u),
            u.isEmailVerified(),
            p != null ? toProfileResponse(p, includePrivateFields) : null,
            u.getCreatedAt()
        );
    }

    public ProfileResponse toProfileResponse(UserProfile p, boolean includePrivateFields) {
        return new ProfileResponse(
            p.getDisplayName(),
            p.getAvatarUrl(),
            p.getCoverImageUrl(),
            p.getProfileBio(),
            p.getSelfDescriber(),
            p.getLocation(),
            p.getAcademicTitle(),
            p.getInstitutionName(),
            p.getMadhhab() != null ? p.getMadhhab().getId() : null,
            p.getMadhhab() != null ? p.getMadhhab().getNameEn() : null,
            p.getWebsiteUrl(),
            p.getSpecializations().stream().map(this::toTopicDto).toList(),
            p.getFollowerCount(),
            p.getFollowingCount(),
            p.getResearchCount(),
            p.getFatwaCount(),
            p.isForHire(),
            p.isProfileLocked(),
            p.getContentLanguage() != null ? p.getContentLanguage().name() : null,
            includePrivateFields ? p.getProfileViews() : 0L,
            p.getLinks().stream()
                .filter(UserLink::isPublic)
                .map(this::toLinkResponse)
                .toList(),
            p.getContacts().stream()
                .filter(UserContact::isPublic)
                .map(this::toContactResponse)
                .toList(),
            p.getAttachments().stream()
                .map(this::toAttachmentResponse)
                .toList()
        );
    }

    /**
     * Badges follow directly from {@link User#getRole()} — no separate
     * verification workflow. SCHOLAR carries researcher privileges already, so
     * a scholar receives only the (higher) Scholar badge rather than both.
     */
    public List<BadgeDto> resolveBadges(User user) {
        List<BadgeDto> badges = new ArrayList<>(1);
        if (user.getRole() == Role.SCHOLAR) {
            badges.add(new BadgeDto(
                BadgeType.VERIFIED_SCHOLAR, "Scholar", "teal", "ti-certificate", 1));
        } else if (user.getRole() == Role.RESEARCHER) {
            badges.add(new BadgeDto(
                BadgeType.VERIFIED_RESEARCHER, "Researcher", "purple", "ti-flask", 2));
        }
        return badges;
    }

    public TopicDto toTopicDto(UserTopicSpecialization s) {
        return new TopicDto(
            s.getTopic().getId(),
            s.getTopic().getNameEn(),
            s.getTopic().getNameAr(),
            s.getTopic().getNameCkb()
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

    /** Returns all links (public + private) — for the owner's own profile view. */
    public UserLinkResponse toLinkResponseFull(UserLink l) {
        return toLinkResponse(l);
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

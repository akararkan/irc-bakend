package ak.dev.irc.app.user.dto.response;


import ak.dev.irc.app.user.enums.Role;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record UserResponse(
        UUID   id,
        String fname,
        String lname,
        String username,
        String email,
        String location,
        String profileImage,
        String profileBio,
        String selfDescriber,
        Role role,
        boolean isProfileLocked,
        boolean isEmailVerified,
        long followerCount,
        long followingCount,
        List<UserLinkResponse>       links,
        List<UserContactResponse>    contacts,
        List<UserAttachmentResponse> attachments,
        LocalDateTime createdAt
) {}

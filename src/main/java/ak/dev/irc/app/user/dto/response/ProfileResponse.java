package ak.dev.irc.app.user.dto.response;

import java.util.List;

public record ProfileResponse(
    String  displayName,
    String  avatarUrl,
    String  coverImageUrl,
    String  profileBio,
    String  selfDescriber,
    String  location,
    String  academicTitle,
    String  institutionName,
    Integer madhhabId,
    String  madhhabName,
    String  websiteUrl,
    List<TopicDto>               specializations,
    long    followerCount,
    long    followingCount,
    int     researchCount,
    int     fatwaCount,
    boolean isForHire,
    boolean isProfileLocked,
    String  contentLanguage,
    long    profileViews,
    List<UserLinkResponse>       links,
    List<UserContactResponse>    contacts,
    List<UserAttachmentResponse> attachments
) {}

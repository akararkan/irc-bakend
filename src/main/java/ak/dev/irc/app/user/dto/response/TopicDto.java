package ak.dev.irc.app.user.dto.response;

public record TopicDto(
    Integer topicId,
    String  nameEn,
    String  nameAr,
    String  nameCkb
) {}

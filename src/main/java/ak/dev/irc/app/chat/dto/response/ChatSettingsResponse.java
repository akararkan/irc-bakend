package ak.dev.irc.app.chat.dto.response;

/** The caller's chat privacy settings. */
public record ChatSettingsResponse(
        boolean readReceiptsEnabled,
        boolean lastSeenVisible,
        boolean typingIndicatorsEnabled
) {}

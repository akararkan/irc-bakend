package ak.dev.irc.app.chat.dto.response;

/** Result of using an invite link: joined immediately, or a join request was
 *  filed and awaits admin approval ({@code conversation} is null then). */
public record JoinByTokenResponse(
        /** JOINED | PENDING_APPROVAL */
        String status,
        ConversationResponse conversation
) {}

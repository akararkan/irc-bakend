package ak.dev.irc.app.chat.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** A single message rendered for the client. */
public record MessageResponse(
        long messageId,
        UUID conversationId,
        UUID senderId,
        String senderUsername,
        String senderFullName,
        String type,
        String body,
        List<MediaRefResponse> media,
        Long replyToId,
        ReplyPreview replyTo,
        UUID forwardedFrom,
        Set<UUID> mentions,
        List<ReactionSummary> reactions,
        Instant editedAt,
        boolean deleted,
        String systemEvent,
        Instant createdAt,
        /** Viewer-relative: has the caller starred/bookmarked this message. */
        boolean starred,
        /** Lowercased #hashtags extracted from the body/caption. */
        Set<String> tags,
        /** Posting admin's label on channel posts when "sign messages" is on. */
        String authorSignature,
        /** Channel posts only: unique-viewer count (null elsewhere). */
        Long views,
        /** Channel posts only: times this post was forwarded (null elsewhere). */
        Long forwards,
        /** Channel posts only: discussion-group comment count (null elsewhere). */
        Long comments,
        /** Poll payload + live results for {@code POLL} messages. */
        PollResponse poll,
        /** Geo payload for {@code LOCATION} messages. */
        ak.dev.irc.app.chat.dto.request.LocationDto location,
        /** Contact card for {@code CONTACT} messages. */
        ak.dev.irc.app.chat.dto.request.ContactDto contact
) {}

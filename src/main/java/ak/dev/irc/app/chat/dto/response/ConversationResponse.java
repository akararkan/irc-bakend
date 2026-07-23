package ak.dev.irc.app.chat.dto.response;

import ak.dev.irc.app.chat.dto.GroupSettings;

import java.time.LocalDateTime;
import java.util.UUID;

/** Conversation metadata + the caller's own member state, for the inbox and header. */
public record ConversationResponse(
        UUID id,
        String type,
        String title,
        String description,
        String avatarKey,
        String avatarUrl,
        UUID ownerId,
        int memberCount,
        Long lastMessageId,
        LocalDateTime lastMessageAt,
        String lastMessagePreview,
        GroupSettings groupSettings,
        /** Disappearing-messages timer in seconds; 0 = off. */
        int disappearingSeconds,
        // ── the caller's member state ──
        String myRole,
        String myStatus,
        long lastReadMessageId,
        long lastDeliveredMessageId,
        int unreadCount,
        // True when there are messages past my read marker, or I've marked the chat
        // unread. The ONLY unread signal for large groups where the exact count is
        // not maintained (approximate "new messages" dot).
        boolean hasUnread,
        boolean markedUnread,
        LocalDateTime mutedUntil,
        boolean pinned,
        boolean archived,
        // ── DIRECT only: the other participant + their receipt markers (null when
        //    the pair don't both share read receipts) ──
        ParticipantSummary peer,
        Long peerLastReadMessageId,
        Long peerLastDeliveredMessageId,
        LocalDateTime createdAt
) {}

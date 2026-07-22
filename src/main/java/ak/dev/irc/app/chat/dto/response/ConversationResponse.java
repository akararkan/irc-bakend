package ak.dev.irc.app.chat.dto.response;

import ak.dev.irc.app.chat.dto.GroupSettings;

import java.time.LocalDateTime;
import java.util.UUID;

/** Conversation metadata + the caller's own member state, for the inbox and header. */
public record ConversationResponse(
        UUID id,
        String type,
        String title,
        String avatarKey,
        String avatarUrl,
        UUID ownerId,
        int memberCount,
        Long lastMessageId,
        LocalDateTime lastMessageAt,
        String lastMessagePreview,
        GroupSettings groupSettings,
        // ── the caller's member state ──
        String myRole,
        String myStatus,
        long lastReadMessageId,
        int unreadCount,
        // True when there are messages past my read marker. Always meaningful,
        // and the ONLY unread signal for large groups where the exact count is
        // not maintained (approximate "new messages" dot).
        boolean hasUnread,
        LocalDateTime mutedUntil,
        boolean pinned,
        boolean archived,
        // ── DIRECT only: the other participant ──
        ParticipantSummary peer,
        LocalDateTime createdAt
) {}

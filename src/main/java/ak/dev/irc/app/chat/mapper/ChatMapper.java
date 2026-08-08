package ak.dev.irc.app.chat.mapper;

import ak.dev.irc.app.chat.cassandra.entity.MediaRef;
import ak.dev.irc.app.chat.cassandra.entity.MessageByConversationEntity;
import ak.dev.irc.app.chat.cassandra.entity.MessageByIdEntity;
import ak.dev.irc.app.chat.dto.response.*;
import ak.dev.irc.app.chat.entity.Conversation;
import ak.dev.irc.app.chat.entity.ConversationMember;
import ak.dev.irc.app.chat.entity.MessageRequest;
import ak.dev.irc.app.chat.util.ChatModeration;
import ak.dev.irc.app.research.service.S3StorageService;
import ak.dev.irc.app.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hand-written entity → response mapper (no MapStruct in this codebase). Media
 * proxy URLs and group-avatar URLs are resolved from their storage keys here so
 * callers never touch storage internals.
 */
@Component
@RequiredArgsConstructor
public class ChatMapper {

    private final S3StorageService storageService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private ak.dev.irc.app.chat.dto.request.LocationDto parseLocation(String json) {
        if (!StringUtils.hasText(json)) return null;
        try {
            return objectMapper.readValue(json, ak.dev.irc.app.chat.dto.request.LocationDto.class);
        } catch (Exception e) { return null; }
    }

    private ak.dev.irc.app.chat.dto.request.ContactDto parseContact(String json) {
        if (!StringUtils.hasText(json)) return null;
        try {
            return objectMapper.readValue(json, ak.dev.irc.app.chat.dto.request.ContactDto.class);
        } catch (Exception e) { return null; }
    }

    // ── Media ─────────────────────────────────────────────────────────────────

    public MediaRefResponse toMediaRef(MediaRef m) {
        if (m == null) return null;
        return new MediaRefResponse(
                m.getKind(),
                m.getStorageKey(),
                resolveUrl(m.getUrl(), m.getStorageKey()),
                m.getThumbnailKey(),
                resolveUrl(m.getThumbnailUrl(), m.getThumbnailKey()),
                m.getMime(),
                m.getBytes(),
                m.getWidth(),
                m.getHeight(),
                m.getDurationMs(),
                m.getWaveform(),
                m.getFileName(),
                m.getAltText());
    }

    private List<MediaRefResponse> toMediaList(List<MediaRef> media) {
        if (media == null || media.isEmpty()) return List.of();
        List<MediaRefResponse> out = new ArrayList<>(media.size());
        for (MediaRef m : media) out.add(toMediaRef(m));
        return out;
    }

    private String resolveUrl(String url, String key) {
        if (StringUtils.hasText(url)) return url;
        if (StringUtils.hasText(key)) {
            try { return storageService.getPublicUrl(key); } catch (Exception ignored) { /* fall through */ }
        }
        return null;
    }

    // ── Messages ────────────────────────────────────────────────────────────────

    /** Per-message hydration extras (channel view/forward/comment counters +
     *  poll state); every field nullable — plain conversations pass {@code null}. */
    public record MessageExtras(Long views, Long forwards, Long comments,
                                ak.dev.irc.app.chat.dto.response.PollResponse poll) {
        public static final MessageExtras NONE = new MessageExtras(null, null, null, null);
    }

    public MessageResponse toMessage(MessageByConversationEntity e,
                                     Map<UUID, User> users,
                                     List<ReactionSummary> reactions,
                                     ReplyPreview replyTo) {
        return toMessage(e, users, reactions, replyTo, false, null, null);
    }

    public MessageResponse toMessage(MessageByConversationEntity e,
                                     Map<UUID, User> users,
                                     List<ReactionSummary> reactions,
                                     ReplyPreview replyTo,
                                     boolean starred) {
        return toMessage(e, users, reactions, replyTo, starred, null, null);
    }

    public MessageResponse toMessage(MessageByConversationEntity e,
                                     Map<UUID, User> users,
                                     List<ReactionSummary> reactions,
                                     ReplyPreview replyTo,
                                     boolean starred,
                                     MessageExtras extras) {
        return toMessage(e, users, reactions, replyTo, starred, extras, null);
    }

    /**
     * {@code viewerId} drives the moderation redaction: a message still held for
     * automated review is blanked exactly like a deleted one for everyone except
     * its own sender. Omitting the viewer redacts — the safe default for the
     * handful of call sites that map without a reader in hand.
     */
    public MessageResponse toMessage(MessageByConversationEntity e,
                                     Map<UUID, User> users,
                                     List<ReactionSummary> reactions,
                                     ReplyPreview replyTo,
                                     boolean starred,
                                     MessageExtras extras,
                                     UUID viewerId) {
        User sender = users == null ? null : users.get(e.getSenderId());
        MessageExtras x = extras == null ? MessageExtras.NONE : extras;
        boolean deleted = Boolean.TRUE.equals(e.getDeleted());
        // Redaction and the tombstone flag are separate on purpose: a held message
        // is not deleted, and telling the client it was would be a lie the sender
        // could see contradicted the moment it clears.
        boolean redacted = deleted
                || ChatModeration.hiddenFrom(e.getModerationStatus(), e.getSenderId(), viewerId);
        return new MessageResponse(
                e.getMessageId(),
                e.getConversationId(),
                e.getSenderId(),
                sender != null ? sender.getUsername() : null,
                sender != null ? sender.getFullName() : null,
                e.getType(),
                redacted ? null : e.getBody(),
                redacted ? List.of() : toMediaList(e.getMedia()),
                e.getReplyToId(),
                replyTo,
                e.getForwardedFrom(),
                e.getMentions(),
                reactions == null ? List.of() : reactions,
                e.getEditedAt(),
                deleted,
                e.getSystemEvent(),
                e.getCreatedAt(),
                starred,
                redacted ? null : e.getTags(),
                e.getAuthorSignature(),
                x.views(),
                x.forwards(),
                x.comments(),
                redacted ? null : x.poll(),
                redacted ? null : parseLocation(e.getLocation()),
                redacted ? null : parseContact(e.getContact()));
    }

    public MessageResponse toMessage(MessageByIdEntity e,
                                     Map<UUID, User> users,
                                     List<ReactionSummary> reactions,
                                     ReplyPreview replyTo) {
        return toMessage(e, users, reactions, replyTo, false, null, null);
    }

    public MessageResponse toMessage(MessageByIdEntity e,
                                     Map<UUID, User> users,
                                     List<ReactionSummary> reactions,
                                     ReplyPreview replyTo,
                                     boolean starred) {
        return toMessage(e, users, reactions, replyTo, starred, null, null);
    }

    public MessageResponse toMessage(MessageByIdEntity e,
                                     Map<UUID, User> users,
                                     List<ReactionSummary> reactions,
                                     ReplyPreview replyTo,
                                     boolean starred,
                                     MessageExtras extras) {
        return toMessage(e, users, reactions, replyTo, starred, extras, null);
    }

    /** See the {@link MessageByConversationEntity} overload for {@code viewerId}. */
    public MessageResponse toMessage(MessageByIdEntity e,
                                     Map<UUID, User> users,
                                     List<ReactionSummary> reactions,
                                     ReplyPreview replyTo,
                                     boolean starred,
                                     MessageExtras extras,
                                     UUID viewerId) {
        User sender = users == null ? null : users.get(e.getSenderId());
        MessageExtras x = extras == null ? MessageExtras.NONE : extras;
        boolean deleted = Boolean.TRUE.equals(e.getDeleted());
        boolean redacted = deleted
                || ChatModeration.hiddenFrom(e.getModerationStatus(), e.getSenderId(), viewerId);
        return new MessageResponse(
                e.getMessageId(),
                e.getConversationId(),
                e.getSenderId(),
                sender != null ? sender.getUsername() : null,
                sender != null ? sender.getFullName() : null,
                e.getType(),
                redacted ? null : e.getBody(),
                redacted ? List.of() : toMediaList(e.getMedia()),
                e.getReplyToId(),
                replyTo,
                e.getForwardedFrom(),
                e.getMentions(),
                reactions == null ? List.of() : reactions,
                e.getEditedAt(),
                deleted,
                e.getSystemEvent(),
                e.getCreatedAt(),
                starred,
                redacted ? null : e.getTags(),
                e.getAuthorSignature(),
                x.views(),
                x.forwards(),
                x.comments(),
                redacted ? null : x.poll(),
                redacted ? null : parseLocation(e.getLocation()),
                redacted ? null : parseContact(e.getContact()));
    }

    /**
     * Reply previews quote someone else's body into a third party's timeline, so
     * a held message's snippet is dropped for every reader including the quoted
     * sender — the preview lives in someone else's message, and there is no
     * viewer-vs-author distinction that makes it safe to render.
     */
    public ReplyPreview toReplyPreview(MessageByIdEntity e) {
        if (e == null) return null;
        boolean deleted = Boolean.TRUE.equals(e.getDeleted());
        boolean redacted = deleted || ChatModeration.held(e.getModerationStatus());
        return new ReplyPreview(
                e.getMessageId(),
                e.getSenderId(),
                e.getType(),
                redacted ? null : snippet(e.getBody(), e.getType()),
                deleted);
    }

    private String snippet(String body, String type) {
        if (StringUtils.hasText(body)) {
            return body.length() <= 100 ? body : body.substring(0, 99) + "…";
        }
        return switch (type == null ? "" : type) {
            case "IMAGE" -> "📷 Photo";
            case "VIDEO" -> "🎥 Video";
            case "VOICE" -> "🎤 Voice message";
            case "AUDIO" -> "🎵 Audio";
            case "GIF" -> "GIF";
            case "STICKER" -> "Sticker";
            case "POLL" -> "📊 Poll";
            case "FILE"  -> "📎 File";
            default -> "";
        };
    }

    // ── Conversation ────────────────────────────────────────────────────────────

    public ConversationResponse toConversation(Conversation c, ConversationMember me, ParticipantSummary peer) {
        return toConversation(c, me, peer, null, null);
    }

    /** {@code peerLastRead}/{@code peerLastDelivered} are the DM peer's receipt
     *  markers (for the ✓✓ / blue-tick), or {@code null} when the pair don't both
     *  share read receipts. */
    public ConversationResponse toConversation(Conversation c, ConversationMember me, ParticipantSummary peer,
                                               Long peerLastRead, Long peerLastDelivered) {
        boolean marked = me != null && me.isMarkedUnread();
        boolean hasUnread = marked || (me != null && c.getLastMessageId() != null
                && c.getLastMessageId() > me.getLastReadMessageId());
        // A group's title/description is held for automated moderation exactly like
        // a message body. Unlike a channel — whose audience only ever arrives through
        // discovery, and so is fully gated by de-indexing — a group's members are
        // added directly by its creator and see the row in their inbox the instant
        // it exists. Suppressing the title in the invite notification is not enough:
        // this list is where they would read it. Its author still sees their own text.
        boolean redactInfo = ChatModeration.held(c.getModerationStatus())
                && (me == null || !me.isOwner());
        return new ConversationResponse(
                c.getId(),
                c.getType().name(),
                redactInfo ? null : c.getTitle(),
                redactInfo ? null : c.getDescription(),
                c.getAvatarKey(),
                resolveUrl(null, c.getAvatarKey()),
                c.getOwnerId(),
                c.getMemberCount(),
                c.getLastMessageId(),
                c.getLastMessageAt(),
                c.getLastMessagePreview(),
                c.getGroupSettings(),
                c.getDisappearingSeconds(),
                me != null ? me.getRole().name() : null,
                me != null ? me.getStatus().name() : null,
                me != null ? me.getLastReadMessageId() : 0L,
                me != null ? me.getLastDeliveredMessageId() : 0L,
                me != null ? me.getUnreadCount() : 0,
                hasUnread,
                marked,
                me != null ? me.getMutedUntil() : null,
                me != null && me.isPinned(),
                me != null && me.isArchived(),
                peer,
                peerLastRead,
                peerLastDelivered,
                c.getCreatedAt());
    }

    // ── Members / requests / participants ────────────────────────────────────────

    public MemberResponse toMember(ConversationMember m, User u) {
        return new MemberResponse(
                m.getId().getUserId(),
                u != null ? u.getUsername() : null,
                u != null ? u.getFullName() : null,
                m.getRole().name(),
                m.getStatus().name(),
                m.getJoinedAt());
    }

    public MessageRequestResponse toMessageRequest(MessageRequest r, User requester) {
        return new MessageRequestResponse(
                r.getId(),
                r.getConversationId(),
                r.getRequesterId(),
                requester != null ? requester.getUsername() : null,
                requester != null ? requester.getFullName() : null,
                r.getStatus().name(),
                r.getFirstMessageId(),
                r.getMessageCount(),
                r.getCreatedAt());
    }

    public ParticipantSummary toParticipant(User u) {
        if (u == null) return null;
        return new ParticipantSummary(u.getId(), u.getUsername(), u.getFullName());
    }
}

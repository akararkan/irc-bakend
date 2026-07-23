package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.dto.GroupSettings;
import ak.dev.irc.app.chat.dto.request.CreateChannelRequest;
import ak.dev.irc.app.chat.dto.response.ChannelResponse;
import ak.dev.irc.app.chat.entity.Conversation;
import ak.dev.irc.app.chat.entity.ConversationMember;
import ak.dev.irc.app.chat.enums.ConversationType;
import ak.dev.irc.app.chat.enums.MemberRole;
import ak.dev.irc.app.chat.enums.MemberScope;
import ak.dev.irc.app.chat.enums.MemberStatus;
import ak.dev.irc.app.chat.realtime.ChatRealtimeBroadcaster;
import ak.dev.irc.app.chat.realtime.ChatRealtimeEvent;
import ak.dev.irc.app.chat.realtime.ChatRealtimeEventType;
import ak.dev.irc.app.chat.repository.ConversationMemberRepository;
import ak.dev.irc.app.chat.repository.ConversationRepository;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Telegram-style broadcast channels. A channel is a {@code CHANNEL}-typed
 * conversation with an admins-only send mode: admins post, subscribers read.
 * Posting to and reading a channel reuse the normal conversation/message
 * endpoints (the channel id is the conversation id); this service adds channel
 * creation, public discovery, and self-subscribe/unsubscribe.
 */
@Service
@RequiredArgsConstructor
public class ChannelService {

    private static final int DISCOVER_MAX = 50;

    private final ConversationRepository conversationRepo;
    private final ConversationMemberRepository memberRepo;
    private final ChatRealtimeBroadcaster broadcaster;

    /** Web origin the share links point at (frontend routes /c/{handle}). */
    @org.springframework.beans.factory.annotation.Value("${irc.base-url:https://irc.example.com}")
    private String baseUrl;

    @Transactional
    public ChannelResponse create(UUID ownerId, CreateChannelRequest req) {
        if (!StringUtils.hasText(req.getTitle())) throw new BadRequestException("A channel requires a title.");
        String handle = normalizeHandle(req.getHandle());
        if (req.isPublicChannel()) {
            if (handle == null) throw new BadRequestException("A public channel requires a @handle.");
            if (conversationRepo.existsByHandle(handle)) throw new BadRequestException("That @handle is already taken.");
        }
        GroupSettings settings = GroupSettings.builder()
                .sendMode(MemberScope.ADMINS_ONLY)            // broadcast: only admins post
                .whoCanAddMembers(MemberScope.ADMINS_ONLY)
                .whoCanEditInfo(MemberScope.ADMINS_ONLY)
                .whoCanPin(MemberScope.ADMINS_ONLY)
                .historyVisibleToNewMembers(true)             // new subscribers see past posts
                .build();
        Conversation c = conversationRepo.save(Conversation.builder()
                .type(ConversationType.CHANNEL)
                .title(req.getTitle().trim())
                .description(StringUtils.hasText(req.getDescription()) ? req.getDescription().trim() : null)
                .handle(handle)
                .publicChannel(req.isPublicChannel())
                .ownerId(ownerId)
                .groupSettings(settings)
                .memberCount(1)
                .build());
        memberRepo.save(ConversationMember.of(c, ownerId, MemberRole.OWNER));
        return toResponse(c, true);
    }

    @Transactional
    public ChannelResponse subscribe(UUID channelId, UUID userId) {
        Conversation c = requireChannel(channelId);
        if (!c.isPublicChannel()) throw new ForbiddenException("This channel is private.", "ACCESS_FORBIDDEN");
        ConversationMember existing = memberRepo.findMember(channelId, userId).orElse(null);
        if (existing != null && existing.isActive()) return toResponse(c, true); // idempotent
        if (existing != null) {
            existing.setStatus(MemberStatus.ACTIVE);
            memberRepo.save(existing);
        } else {
            memberRepo.save(ConversationMember.of(c, userId, MemberRole.MEMBER));
        }
        conversationRepo.adjustMemberCount(channelId, +1);
        // Realtime subscriber count: every active member (including the new
        // subscriber's own tabs) gets the member change and applies +1 locally —
        // the platform's delta-not-counts model.
        broadcaster.broadcast(memberRepo.findActiveMemberIds(channelId), ChatRealtimeEvent.builder()
                .eventType(ChatRealtimeEventType.MEMBER_CHANGED)
                .conversationId(channelId).userId(userId)
                .memberChange("SUBSCRIBED").role(MemberRole.MEMBER.name())
                .build());
        return toResponse(conversationRepo.findById(channelId).orElse(c), true);
    }

    @Transactional
    public void unsubscribe(UUID channelId, UUID userId) {
        requireChannel(channelId);
        ConversationMember m = memberRepo.findMember(channelId, userId).orElse(null);
        if (m == null || !m.isActive()) return; // idempotent
        if (m.isOwner()) throw new ForbiddenException("The owner cannot unsubscribe from their own channel.", "ACCESS_FORBIDDEN");
        memberRepo.delete(m);
        conversationRepo.adjustMemberCount(channelId, -1);
        // Remaining members apply −1; the leaver's own tabs get it explicitly
        // (they are no longer in the active list).
        ChatRealtimeEvent evt = ChatRealtimeEvent.builder()
                .eventType(ChatRealtimeEventType.MEMBER_CHANGED)
                .conversationId(channelId).userId(userId)
                .memberChange("UNSUBSCRIBED")
                .build();
        broadcaster.broadcast(memberRepo.findActiveMemberIds(channelId), evt);
        broadcaster.broadcastTo(userId, evt);
    }

    @Transactional(readOnly = true)
    public List<ChannelResponse> discover(UUID userId, String q) {
        String needle = q == null ? "" : q.trim();
        var channels = conversationRepo.discoverChannels(
                ConversationType.CHANNEL, needle, PageRequest.of(0, DISCOVER_MAX));
        // One membership query for the whole result set (previously one per channel).
        java.util.Set<UUID> subscribed = subscribedAmong(
                channels.stream().map(Conversation::getId).toList(), userId);
        return channels.stream().map(c -> toResponse(c, subscribed.contains(c.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public ChannelResponse getByHandle(String handle, UUID userId) {
        String h = normalizeHandle(handle);
        Conversation c = (h == null ? Optional.<Conversation>empty() : conversationRepo.findByHandle(h))
                .filter(x -> x.getDeletedAt() == null && x.isChannel())
                .orElseThrow(() -> new ResourceNotFoundException("Channel", "handle", handle));
        return toResponse(c, isSubscribed(c.getId(), userId));
    }

    // ── internals ──────────────────────────────────────────────────────────────

    private boolean isSubscribed(UUID channelId, UUID userId) {
        return memberRepo.findMember(channelId, userId).filter(ConversationMember::isActive).isPresent();
    }

    private java.util.Set<UUID> subscribedAmong(List<UUID> channelIds, UUID userId) {
        if (channelIds.isEmpty() || userId == null) return java.util.Set.of();
        return memberRepo.findMyMembershipsIn(userId, channelIds).stream()
                .filter(ConversationMember::isActive)
                .map(m -> m.getId().getConversationId())
                .collect(java.util.stream.Collectors.toSet());
    }

    private Conversation requireChannel(UUID id) {
        return conversationRepo.findById(id)
                .filter(c -> c.getDeletedAt() == null && c.isChannel())
                .orElseThrow(() -> new ResourceNotFoundException("Channel", "id", id));
    }

    private ChannelResponse toResponse(Conversation c, boolean subscribed) {
        // A private channel has no public link — it is shared via an invite link
        // (POST /conversations/{id}/invite-link) instead.
        String shareUrl = c.isPublicChannel() && c.getHandle() != null
                ? ak.dev.irc.app.chat.util.ShareLinks.of(baseUrl, "/c/" + c.getHandle()) : null;
        return new ChannelResponse(c.getId(), c.getHandle(), c.getTitle(), c.getDescription(),
                c.isPublicChannel(), c.getMemberCount(), c.getOwnerId(), subscribed, c.getCreatedAt(),
                shareUrl);
    }

    private String normalizeHandle(String raw) {
        if (!StringUtils.hasText(raw)) return null;
        String h = raw.trim();
        if (h.startsWith("@")) h = h.substring(1);
        h = h.toLowerCase(Locale.ROOT);
        if (!h.matches("[a-z0-9_]{3,32}")) {
            throw new BadRequestException("Handle must be 3–32 characters of a–z, 0–9 or underscore.");
        }
        return h;
    }
}

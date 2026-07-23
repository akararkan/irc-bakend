package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.dto.AdminRights;
import ak.dev.irc.app.chat.dto.ChannelSettings;
import ak.dev.irc.app.chat.dto.GroupSettings;
import ak.dev.irc.app.chat.dto.request.ChannelAdminRequest;
import ak.dev.irc.app.chat.dto.request.CreateChannelRequest;
import ak.dev.irc.app.chat.dto.request.UpdateChannelRequest;
import ak.dev.irc.app.chat.dto.response.ChannelAdminResponse;
import ak.dev.irc.app.chat.dto.response.ChannelResponse;
import ak.dev.irc.app.chat.entity.Conversation;
import ak.dev.irc.app.chat.entity.ConversationMember;
import ak.dev.irc.app.chat.enums.ConversationType;
import ak.dev.irc.app.chat.enums.JoinRequestStatus;
import ak.dev.irc.app.chat.enums.MemberRole;
import ak.dev.irc.app.chat.enums.MemberScope;
import ak.dev.irc.app.chat.enums.MemberStatus;
import ak.dev.irc.app.chat.permission.ChannelRights;
import ak.dev.irc.app.chat.realtime.ChatRealtimeBroadcaster;
import ak.dev.irc.app.chat.realtime.ChatRealtimeEvent;
import ak.dev.irc.app.chat.realtime.ChatRealtimeEventType;
import ak.dev.irc.app.chat.repository.ConversationJoinRequestRepository;
import ak.dev.irc.app.chat.repository.ConversationMemberRepository;
import ak.dev.irc.app.chat.repository.ConversationRepository;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.research.service.S3StorageService;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Telegram-style broadcast channels. A channel is a {@code CHANNEL}-typed
 * conversation with an admins-only send mode: admins post, subscribers read.
 * Posting to and reading a channel reuse the normal conversation/message
 * endpoints (the channel id is the conversation id); this service adds channel
 * creation, profile/cover management, settings, verification, granular admin
 * management, public discovery, and self-subscribe/unsubscribe (including
 * join-by-request channels).
 */
@Service
@RequiredArgsConstructor
public class ChannelService {

    private static final int DISCOVER_MAX = 50;
    private static final String MEDIA_PREFIX = "chat/channel";

    private final ConversationRepository conversationRepo;
    private final ConversationMemberRepository memberRepo;
    private final ConversationJoinRequestRepository joinRequestRepo;
    private final ChannelJoinRequestService joinRequestService;
    private final ChatRealtimeBroadcaster broadcaster;
    private final S3StorageService storageService;
    private final UserRepository userRepository;

    /** Web origin the share links point at (frontend routes /c/{handle}). */
    @org.springframework.beans.factory.annotation.Value("${irc.base-url:https://irc.example.com}")
    private String baseUrl;

    // ── Create ───────────────────────────────────────────────────────────────────

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
                .category(normalizeCategory(req.getCategory()))
                .ownerId(ownerId)
                .groupSettings(settings)
                .channelSettings(req.getSettings() != null ? req.getSettings() : ChannelSettings.defaults())
                .memberCount(1)
                .build());
        memberRepo.save(ConversationMember.of(c, ownerId, MemberRole.OWNER, "OWNER"));
        return toResponse(c, memberRepo.findMember(c.getId(), ownerId).orElse(null), false);
    }

    // ── Update info / settings ───────────────────────────────────────────────────

    @Transactional
    public ChannelResponse update(UUID channelId, UUID actorId, UpdateChannelRequest req) {
        Conversation c = requireChannel(channelId);
        requireRight(channelId, actorId, AdminRights::isCanChangeInfo,
                "You cannot edit this channel's info.");

        if (req.getTitle() != null) {
            if (!StringUtils.hasText(req.getTitle())) throw new BadRequestException("Title cannot be blank.");
            c.setTitle(req.getTitle().trim());
        }
        if (req.getDescription() != null) {
            c.setDescription(StringUtils.hasText(req.getDescription()) ? req.getDescription().trim() : null);
        }
        if (req.getCategory() != null) {
            c.setCategory(normalizeCategory(req.getCategory()));
        }
        if (req.getPublicChannel() != null || req.getHandle() != null) {
            boolean toPublic = req.getPublicChannel() != null ? req.getPublicChannel() : c.isPublicChannel();
            if (toPublic) {
                String handle = req.getHandle() != null ? normalizeHandle(req.getHandle()) : c.getHandle();
                if (handle == null) throw new BadRequestException("A public channel requires a @handle.");
                if (!handle.equals(c.getHandle()) && conversationRepo.existsByHandle(handle)) {
                    throw new BadRequestException("That @handle is already taken.");
                }
                c.setHandle(handle);
                c.setPublicChannel(true);
            } else {
                // Turning private removes the public address (Telegram behavior).
                c.setPublicChannel(false);
                c.setHandle(null);
            }
        }
        if (req.getSettings() != null) {
            c.setChannelSettings(req.getSettings());
        }
        conversationRepo.save(c);
        broadcastChannelUpdated(channelId);
        return toResponse(c, memberRepo.findMember(channelId, actorId).orElse(null), false);
    }

    // ── Profile photo / cover ────────────────────────────────────────────────────

    @Transactional
    public ChannelResponse setPhoto(UUID channelId, UUID actorId, MultipartFile file) {
        return setImage(channelId, actorId, file, true);
    }

    @Transactional
    public ChannelResponse setCover(UUID channelId, UUID actorId, MultipartFile file) {
        return setImage(channelId, actorId, file, false);
    }

    @Transactional
    public void deletePhoto(UUID channelId, UUID actorId) {
        deleteImage(channelId, actorId, true);
    }

    @Transactional
    public void deleteCover(UUID channelId, UUID actorId) {
        deleteImage(channelId, actorId, false);
    }

    private ChannelResponse setImage(UUID channelId, UUID actorId, MultipartFile file, boolean avatar) {
        Conversation c = requireChannel(channelId);
        requireRight(channelId, actorId, AdminRights::isCanChangeInfo,
                "You cannot change this channel's " + (avatar ? "photo." : "cover."));
        if (file == null || file.isEmpty()) throw new BadRequestException("Provide an image file.");
        String mime = file.getContentType();
        if (mime == null || !mime.startsWith("image/")) {
            throw new BadRequestException("The channel " + (avatar ? "photo" : "cover") + " must be an image.");
        }
        String old = avatar ? c.getAvatarKey() : c.getCoverKey();
        String key = storageService.upload(file, MEDIA_PREFIX);
        if (avatar) c.setAvatarKey(key); else c.setCoverKey(key);
        conversationRepo.save(c);
        if (StringUtils.hasText(old)) {
            try { storageService.delete(old); } catch (Exception ignored) { /* best-effort */ }
        }
        broadcastChannelUpdated(channelId);
        return toResponse(c, memberRepo.findMember(channelId, actorId).orElse(null), false);
    }

    private void deleteImage(UUID channelId, UUID actorId, boolean avatar) {
        Conversation c = requireChannel(channelId);
        requireRight(channelId, actorId, AdminRights::isCanChangeInfo,
                "You cannot change this channel's " + (avatar ? "photo." : "cover."));
        String old = avatar ? c.getAvatarKey() : c.getCoverKey();
        if (!StringUtils.hasText(old)) return; // idempotent
        if (avatar) c.setAvatarKey(null); else c.setCoverKey(null);
        conversationRepo.save(c);
        try { storageService.delete(old); } catch (Exception ignored) { /* best-effort */ }
        broadcastChannelUpdated(channelId);
    }

    // ── Verified badge (platform admins) ─────────────────────────────────────────

    @Transactional
    public ChannelResponse setVerified(UUID channelId, boolean verified) {
        Conversation c = requireChannel(channelId);
        c.setVerified(verified);
        conversationRepo.save(c);
        broadcastChannelUpdated(channelId);
        return toResponse(c, null, false);
    }

    // ── Admin management ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ChannelAdminResponse> listAdmins(UUID channelId, UUID actorId) {
        requireChannel(channelId);
        requireMember(channelId, actorId); // any subscriber may see who runs the channel
        List<ConversationMember> staff = memberRepo.findAllByConversation(channelId).stream()
                .filter(m -> m.isActive() && m.isAdminOrOwner())
                .toList();
        Set<UUID> ids = staff.stream().map(m -> m.getId().getUserId()).collect(Collectors.toSet());
        Map<UUID, User> users = ids.isEmpty() ? Map.of()
                : userRepository.findActiveByIdIn(ids).stream().collect(Collectors.toMap(User::getId, u -> u));
        return staff.stream()
                .sorted((a, b) -> a.isOwner() ? -1 : b.isOwner() ? 1 : 0)
                .map(m -> {
                    User u = users.get(m.getId().getUserId());
                    return new ChannelAdminResponse(
                            m.getId().getUserId(),
                            u != null ? u.getUsername() : null,
                            u != null ? u.getFullName() : null,
                            m.getRole().name(),
                            m.isOwner() ? AdminRights.full()
                                    : m.getAdminRights() != null ? m.getAdminRights() : AdminRights.full(),
                            m.getJoinedAt());
                })
                .toList();
    }

    /** Promote a subscriber to admin, or update an existing admin's rights. */
    @Transactional
    public ChannelAdminResponse putAdmin(UUID channelId, UUID actorId, UUID targetId, ChannelAdminRequest req) {
        requireChannel(channelId);
        ConversationMember actor = requireRight(channelId, actorId, AdminRights::isCanAddAdmins,
                "You cannot manage admins in this channel.");
        ConversationMember target = memberRepo.findMember(channelId, targetId)
                .filter(ConversationMember::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Subscriber", "userId", targetId));
        if (target.isOwner()) throw new ForbiddenException("The owner's rights cannot be edited.", "NOT_OWNER");
        if (!actor.isOwner() && target.getRole() == MemberRole.ADMIN && !actorId.equals(targetId)) {
            // Admins with canAddAdmins may promote members and edit non-owner
            // admins; they can never touch the owner (handled above).
            // (Kept permissive by design — Telegram tracks promoter chains, we don't.)
        }
        target.setRole(MemberRole.ADMIN);
        target.setAdminRights(toRights(req));
        memberRepo.save(target);
        emitMemberChange(channelId, targetId, "PROMOTED", MemberRole.ADMIN);
        User u = userRepository.findById(targetId).orElse(null);
        return new ChannelAdminResponse(targetId,
                u != null ? u.getUsername() : null,
                u != null ? u.getFullName() : null,
                MemberRole.ADMIN.name(),
                target.getAdminRights() != null ? target.getAdminRights() : AdminRights.full(),
                target.getJoinedAt());
    }

    /** Demote an admin back to a plain subscriber. */
    @Transactional
    public void removeAdmin(UUID channelId, UUID actorId, UUID targetId) {
        requireChannel(channelId);
        requireRight(channelId, actorId, AdminRights::isCanAddAdmins,
                "You cannot manage admins in this channel.");
        ConversationMember target = memberRepo.findMember(channelId, targetId)
                .orElseThrow(() -> new ResourceNotFoundException("Member", "userId", targetId));
        if (target.isOwner()) throw new ForbiddenException("The owner cannot be demoted.", "NOT_OWNER");
        if (target.getRole() != MemberRole.ADMIN) return; // idempotent
        target.setRole(MemberRole.MEMBER);
        target.setAdminRights(null);
        memberRepo.save(target);
        emitMemberChange(channelId, targetId, "DEMOTED", MemberRole.MEMBER);
    }

    // ── Subscribe / unsubscribe ──────────────────────────────────────────────────

    @Transactional
    public ChannelResponse subscribe(UUID channelId, UUID userId) {
        Conversation c = requireChannel(channelId);
        if (!c.isPublicChannel()) throw new ForbiddenException("This channel is private.", "ACCESS_FORBIDDEN");
        ConversationMember existing = memberRepo.findMember(channelId, userId).orElse(null);
        if (existing != null && existing.isActive()) return toResponse(c, existing, false); // idempotent

        // Join-by-request channels file an approval request instead of joining.
        if (c.channelSettingsOrDefaults().isJoinByRequest()) {
            joinRequestService.file(c, userId, null);
            return toResponse(c, existing, true);
        }

        if (existing != null) {
            existing.setStatus(MemberStatus.ACTIVE);
            existing.setJoinSource("DISCOVERY");
            memberRepo.save(existing);
        } else {
            memberRepo.save(ConversationMember.of(c, userId, MemberRole.MEMBER, "DISCOVERY"));
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
        Conversation fresh = conversationRepo.findById(channelId).orElse(c);
        return toResponse(fresh, memberRepo.findMember(channelId, userId).orElse(null), false);
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

    // ── Discover / lookup ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ChannelResponse> discover(UUID userId, String q, String category) {
        String needle = q == null ? "" : q.trim();
        var channels = conversationRepo.discoverChannels(
                ConversationType.CHANNEL, needle, normalizeCategory(category),
                PageRequest.of(0, DISCOVER_MAX));
        // One membership query for the whole result set (previously one per channel).
        Map<UUID, ConversationMember> mine = membershipsAmong(
                channels.stream().map(Conversation::getId).toList(), userId);
        return channels.stream().map(c -> toResponse(c, mine.get(c.getId()), false)).toList();
    }

    @Transactional(readOnly = true)
    public ChannelResponse get(UUID channelId, UUID userId) {
        Conversation c = requireChannel(channelId);
        ConversationMember me = memberRepo.findMember(channelId, userId).orElse(null);
        boolean pending = me == null || !me.isActive()
                ? joinRequestRepo.existsByConversationIdAndUserIdAndStatus(channelId, userId, JoinRequestStatus.PENDING)
                : false;
        return toResponse(c, me, pending);
    }

    @Transactional(readOnly = true)
    public ChannelResponse getByHandle(String handle, UUID userId) {
        String h = normalizeHandle(handle);
        Conversation c = (h == null ? Optional.<Conversation>empty() : conversationRepo.findByHandle(h))
                .filter(x -> x.getDeletedAt() == null && x.isChannel())
                .orElseThrow(() -> new ResourceNotFoundException("Channel", "handle", handle));
        return get(c.getId(), userId);
    }

    // ── internals ──────────────────────────────────────────────────────────────

    private Map<UUID, ConversationMember> membershipsAmong(List<UUID> channelIds, UUID userId) {
        if (channelIds.isEmpty() || userId == null) return Map.of();
        return memberRepo.findMyMembershipsIn(userId, channelIds).stream()
                .collect(Collectors.toMap(m -> m.getId().getConversationId(), m -> m));
    }

    private Conversation requireChannel(UUID id) {
        return conversationRepo.findById(id)
                .filter(c -> c.getDeletedAt() == null && c.isChannel())
                .orElseThrow(() -> new ResourceNotFoundException("Channel", "id", id));
    }

    private ConversationMember requireMember(UUID channelId, UUID userId) {
        return memberRepo.findMember(channelId, userId)
                .filter(ConversationMember::canRead)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this channel.", "NOT_A_MEMBER"));
    }

    /** Owner, or an active admin holding {@code right}. */
    private ConversationMember requireRight(UUID channelId, UUID actorId,
                                            Predicate<AdminRights> right, String message) {
        ConversationMember actor = memberRepo.findMember(channelId, actorId).orElse(null);
        if (!ChannelRights.can(actor, right)) {
            throw new ForbiddenException(message, "ADMINS_ONLY");
        }
        return actor;
    }

    private void emitMemberChange(UUID channelId, UUID userId, String change, MemberRole role) {
        ChatRealtimeEvent evt = ChatRealtimeEvent.builder()
                .eventType(ChatRealtimeEventType.MEMBER_CHANGED)
                .conversationId(channelId).userId(userId)
                .memberChange(change).role(role.name())
                .build();
        broadcaster.broadcast(memberRepo.findActiveMemberIds(channelId), evt);
        broadcaster.broadcastTo(userId, evt);
    }

    private void broadcastChannelUpdated(UUID channelId) {
        broadcaster.broadcast(memberRepo.findActiveMemberIds(channelId), ChatRealtimeEvent.builder()
                .eventType(ChatRealtimeEventType.CONVERSATION_UPDATED)
                .conversationId(channelId)
                .memberChange("CHANNEL_INFO_CHANGED")
                .build());
    }

    private static AdminRights toRights(ChannelAdminRequest req) {
        if (req == null) return AdminRights.full();
        AdminRights r = AdminRights.full();
        if (req.getCanPostMessages() != null) r.setCanPostMessages(req.getCanPostMessages());
        if (req.getCanEditMessages() != null) r.setCanEditMessages(req.getCanEditMessages());
        if (req.getCanDeleteMessages() != null) r.setCanDeleteMessages(req.getCanDeleteMessages());
        if (req.getCanPinMessages() != null) r.setCanPinMessages(req.getCanPinMessages());
        if (req.getCanInviteUsers() != null) r.setCanInviteUsers(req.getCanInviteUsers());
        if (req.getCanApproveJoinRequests() != null) r.setCanApproveJoinRequests(req.getCanApproveJoinRequests());
        if (req.getCanChangeInfo() != null) r.setCanChangeInfo(req.getCanChangeInfo());
        if (req.getCanAddAdmins() != null) r.setCanAddAdmins(req.getCanAddAdmins());
        if (req.getCanManageLive() != null) r.setCanManageLive(req.getCanManageLive());
        if (req.getCanManageStories() != null) r.setCanManageStories(req.getCanManageStories());
        if (StringUtils.hasText(req.getCustomTitle())) r.setCustomTitle(req.getCustomTitle().trim());
        return r;
    }

    ChannelResponse toResponse(Conversation c, ConversationMember me, boolean pendingJoinRequest) {
        // A private channel has no public link — it is shared via an invite link
        // (POST /conversations/{id}/invite-links) instead.
        String shareUrl = c.isPublicChannel() && c.getHandle() != null
                ? ak.dev.irc.app.chat.util.ShareLinks.of(baseUrl, "/c/" + c.getHandle()) : null;
        boolean subscribed = me != null && me.isActive();
        return new ChannelResponse(
                c.getId(), c.getHandle(), c.getTitle(), c.getDescription(), c.getCategory(),
                c.isPublicChannel(), c.isVerified(),
                c.getMemberCount(), c.getPostCount(), c.getOwnerId(),
                subscribed, pendingJoinRequest,
                subscribed ? me.getRole().name() : null,
                publicUrl(c.getAvatarKey()), publicUrl(c.getCoverKey()),
                c.channelSettingsOrDefaults(),
                c.getLinkedGroupId(),
                c.getCreatedAt(), shareUrl);
    }

    private String publicUrl(String key) {
        if (!StringUtils.hasText(key)) return null;
        try { return storageService.getPublicUrl(key); } catch (Exception e) { return null; }
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

    private String normalizeCategory(String raw) {
        if (!StringUtils.hasText(raw)) return null;
        return raw.trim().toLowerCase(Locale.ROOT);
    }
}

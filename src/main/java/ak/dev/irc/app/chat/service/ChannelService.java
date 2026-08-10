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
import ak.dev.irc.app.common.messages.ChannelStreamMessages;
import ak.dev.irc.app.common.messages.ModerationMessages;
import ak.dev.irc.app.chat.util.ChatModeration;
import ak.dev.irc.app.moderation.enums.ModeratedEntityType;
import ak.dev.irc.app.moderation.service.ContentModerationService;
import ak.dev.irc.app.moderation.service.ModerationOutcome;
import ak.dev.irc.app.moderation.service.ModerationSubmission;
import ak.dev.irc.app.research.service.S3StorageService;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
    private final ak.dev.irc.app.chat.search.service.ChannelSearchService channelSearch;
    /** Automated text moderation of the channel's title/description (docs/moderation/). */
    private final ContentModerationService contentModeration;
    private final UnreadBadgeCache unreadBadge;

    /** Web origin the share links point at (frontend routes /c/{handle}). */
    @org.springframework.beans.factory.annotation.Value("${irc.base-url:https://irc.example.com}")
    private String baseUrl;

    // ── Create ───────────────────────────────────────────────────────────────────

    @Transactional
    public ChannelResponse create(UUID ownerId, CreateChannelRequest req) {
        if (!StringUtils.hasText(req.getTitle())) throw new BadRequestException(ChannelStreamMessages.CHANNEL_TITLE_REQUIRED_MSG);
        String handle = normalizeHandle(req.getHandle());
        if (req.isPublicChannel()) {
            if (handle == null) throw new BadRequestException(ChannelStreamMessages.CHANNEL_HANDLE_REQUIRED_MSG);
            if (conversationRepo.existsByHandle(handle)) throw new BadRequestException(ChannelStreamMessages.CHANNEL_HANDLE_TAKEN_MSG);
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

        // The channel id is JPA-generated, so the row has to exist before the
        // submission can name it. A rejection throws out of this @Transactional
        // method and rolls the insert back, so a blocked channel leaves nothing
        // behind — submitOrThrow's own REQUIRES_NEW transaction keeps the case row
        // and its evidence regardless. Nothing published has happened yet: the
        // owner membership and the discovery index write both come after this.
        ModerationOutcome moderation = contentModeration.submitOrThrow(
                ModerationSubmission.of(ModeratedEntityType.CHANNEL, c.getId().toString(), ownerId)
                        .field("title", c.getTitle())
                        .field("description", c.getDescription()));
        c.setModerationStatus(ChatModeration.marker(moderation));

        memberRepo.save(ConversationMember.of(c, ownerId, MemberRole.OWNER, "OWNER"));
        // A held channel is never indexed — ChannelSearchService treats "held" like
        // "private" and de-indexes, so this call is safe either way, but skipping it
        // avoids a pointless ES round trip.
        if (!moderation.held()) channelSearch.indexAsync(c);
        return toResponse(c, memberRepo.findMember(c.getId(), ownerId).orElse(null), false);
    }

    // ── Update info / settings ───────────────────────────────────────────────────

    @Transactional
    public ChannelResponse update(UUID channelId, UUID actorId, UpdateChannelRequest req) {
        Conversation c = requireChannel(channelId);
        requireRight(channelId, actorId, AdminRights::isCanChangeInfo,
                ChannelStreamMessages.CHANNEL_EDIT_INFO_FORBIDDEN_MSG);

        String newTitle = c.getTitle();
        String newDescription = c.getDescription();
        if (req.getTitle() != null) {
            if (!StringUtils.hasText(req.getTitle())) throw new BadRequestException(ChannelStreamMessages.TITLE_BLANK_MSG);
            newTitle = req.getTitle().trim();
        }
        if (req.getDescription() != null) {
            newDescription = StringUtils.hasText(req.getDescription()) ? req.getDescription().trim() : null;
        }
        boolean textChanged = !java.util.Objects.equals(newTitle, c.getTitle())
                || !java.util.Objects.equals(newDescription, c.getDescription());
        if (textChanged) {
            // §5.5 STRICT edit policy. A channel is long-lived and its title is on
            // every post it has ever published, so an unresolved verdict must not
            // blank a live channel or leave unreviewed text on it: the previously
            // approved wording stays and the change is refused. The author retries
            // once the case settles.
            ModerationOutcome moderation = contentModeration.submit(
                    ModerationSubmission.of(ModeratedEntityType.CHANNEL,
                                    channelId.toString(), actorId)
                            .field("title", newTitle)
                            .field("description", newDescription));
            if (moderation.rejected()) {
                throw new BadRequestException(ModerationMessages.CONTENT_REJECTED_MSG,
                        ModerationMessages.CONTENT_REJECTED);
            }
            if (moderation.held()) {
                throw new BadRequestException(ModerationMessages.CONTENT_UNDER_REVIEW_MSG,
                        ModerationMessages.CONTENT_UNDER_REVIEW);
            }
            c.setTitle(newTitle);
            c.setDescription(newDescription);
            // The edit cleared, so a channel still held from its create is released.
            c.setModerationStatus(null);
        }
        if (req.getCategory() != null) {
            c.setCategory(normalizeCategory(req.getCategory()));
        }
        if (req.getPublicChannel() != null || req.getHandle() != null) {
            boolean toPublic = req.getPublicChannel() != null ? req.getPublicChannel() : c.isPublicChannel();
            if (toPublic) {
                String handle = req.getHandle() != null ? normalizeHandle(req.getHandle()) : c.getHandle();
                if (handle == null) throw new BadRequestException(ChannelStreamMessages.CHANNEL_HANDLE_REQUIRED_MSG);
                if (!handle.equals(c.getHandle()) && conversationRepo.existsByHandle(handle)) {
                    throw new BadRequestException(ChannelStreamMessages.CHANNEL_HANDLE_TAKEN_MSG);
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
        channelSearch.indexAsync(c);   // turning private de-indexes; public upserts
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
        if (file == null || file.isEmpty()) throw new BadRequestException(ChannelStreamMessages.CHANNEL_IMAGE_FILE_REQUIRED_MSG);
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
        channelSearch.indexAsync(c);
        broadcastChannelUpdated(channelId);
        return toResponse(c, null, false);
    }

    // ── Admin management ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ChannelAdminResponse> listAdmins(UUID channelId, UUID actorId) {
        requireChannel(channelId);
        requireMember(channelId, actorId); // any subscriber may see who runs the channel
        // Targeted role query — the old findAllByConversation loaded EVERY
        // subscriber row into memory just to keep the handful of staff.
        List<ConversationMember> staff = memberRepo.findActiveStaff(channelId);
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
                ChannelStreamMessages.CHANNEL_MANAGE_ADMINS_FORBIDDEN_MSG);
        ConversationMember target = memberRepo.findMember(channelId, targetId)
                .filter(ConversationMember::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Subscriber", "userId", targetId));
        if (target.isOwner()) throw new ForbiddenException(
                ChannelStreamMessages.OWNER_RIGHTS_IMMUTABLE_MSG, ChannelStreamMessages.NOT_OWNER);
        if (!actor.isOwner() && target.getRole() == MemberRole.ADMIN && !actorId.equals(targetId)) {
            // Admins with canAddAdmins may promote members and edit non-owner
            // admins; they can never touch the owner (handled above).
            // (Kept permissive by design — Telegram tracks promoter chains, we don't.)
        }
        // customTitle is owner-authored free text rendered to every subscriber in
        // listAdmins, so it is user content and goes through moderation. There is
        // no held representation for a rights blob — the verdict has to be
        // terminal, cleared or refused.
        if (req != null && req.getCustomTitle() != null && !req.getCustomTitle().isBlank()) {
            contentModeration.submitOrRefuse(
                    ak.dev.irc.app.moderation.service.ModerationSubmission.of(
                                    ak.dev.irc.app.moderation.enums.ModeratedEntityType.CONTENT_ANNOTATION,
                                    channelId + ":" + targetId, actorId)
                            .parent(channelId.toString())
                            .field("admin_custom_title", req.getCustomTitle()));
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
                ChannelStreamMessages.CHANNEL_MANAGE_ADMINS_FORBIDDEN_MSG);
        ConversationMember target = memberRepo.findMember(channelId, targetId)
                .orElseThrow(() -> new ResourceNotFoundException("Member", "userId", targetId));
        if (target.isOwner()) throw new ForbiddenException(
                ChannelStreamMessages.OWNER_NOT_DEMOTABLE_MSG, ChannelStreamMessages.NOT_OWNER);
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
        if (!c.isPublicChannel()) throw new ForbiddenException(
                ChannelStreamMessages.CHANNEL_PRIVATE_MSG, ChannelStreamMessages.ACCESS_FORBIDDEN);
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
        channelSearch.indexAsync(fresh);   // keep subscriberCount ranking fresh
        return toResponse(fresh, memberRepo.findMember(channelId, userId).orElse(null), false);
    }

    @Transactional
    public void unsubscribe(UUID channelId, UUID userId) {
        requireChannel(channelId);
        ConversationMember m = memberRepo.findMember(channelId, userId).orElse(null);
        if (m == null || !m.isActive()) return; // idempotent
        if (m.isOwner()) throw new ForbiddenException(
                ChannelStreamMessages.OWNER_CANNOT_UNSUBSCRIBE_MSG, ChannelStreamMessages.ACCESS_FORBIDDEN);
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
        unreadBadge.invalidate(userId);   // any unread from this channel leaves the badge now
        conversationRepo.findById(channelId).ifPresent(channelSearch::indexAsync);
    }

    // ── Delete channel ───────────────────────────────────────────────────────────

    /**
     * Owner-only: delete the channel for <b>everyone</b> (Telegram "Delete
     * channel"). Soft-deletes the conversation — every read path filters
     * {@code deletedAt}, so the channel drops out of all subscriber inboxes,
     * lookups and discovery at once — then de-indexes it from the
     * public-channel search. A subscriber who only wants the chat out of
     * their own list uses {@link #unsubscribe} instead.
     */
    @Transactional
    public void deleteChannel(UUID channelId, UUID actorId) {
        Conversation c = requireChannel(channelId);
        ConversationMember me = requireMember(channelId, actorId);
        if (!me.isOwner()) throw new ForbiddenException(
                ChannelStreamMessages.CHANNEL_DELETE_OWNER_ONLY_MSG, ChannelStreamMessages.NOT_OWNER);
        c.setDeletedAt(LocalDateTime.now());
        conversationRepo.save(c);
        broadcaster.broadcast(memberRepo.findActiveMemberIds(channelId),
                ChatRealtimeEvent.builder()
                        .eventType(ChatRealtimeEventType.CONVERSATION_UPDATED)
                        .conversationId(channelId)
                        .memberChange("DELETED")
                        .build());
        channelSearch.deleteAsync(channelId);
    }

    // ── Discover / lookup ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ChannelResponse> discover(UUID userId, String q, String category) {
        String needle = q == null ? "" : q.trim();
        List<Conversation> channels = needle.isEmpty()
                ? null
                : discoverViaSearchIndex(needle, normalizeCategory(category));
        if (channels == null) {
            // Blank query ("browse all", ordered by subscribers) — or the ES
            // path failed / returned nothing (cold index). The bounded
            // Postgres LIKE scan keeps discovery working either way.
            channels = conversationRepo.discoverChannels(
                    ConversationType.CHANNEL, needle, normalizeCategory(category),
                    PageRequest.of(0, DISCOVER_MAX));
        }
        // One membership query for the whole result set (previously one per channel).
        Map<UUID, ConversationMember> mine = membershipsAmong(
                channels.stream().map(Conversation::getId).toList(), userId);
        return channels.stream().map(c -> toResponse(c, mine.get(c.getId()), false)).toList();
    }

    /**
     * ES-ranked channel discovery (BM25 + typo tolerance + prefix typeahead
     * × log1p subscriber boost) replacing the old sequential-scan
     * {@code LIKE '%q%'} for non-blank queries. Ids come back best-first;
     * hydration from Postgres re-checks public + live state so a stale index
     * row can never leak a now-private channel. Returns {@code null} when ES
     * is unavailable or has no hits, so the caller falls back to the scan.
     */
    private List<Conversation> discoverViaSearchIndex(String needle, String category) {
        final List<UUID> ids;
        try {
            ids = channelSearch.searchIds(needle, category, DISCOVER_MAX);
        } catch (Exception e) {
            return null;    // ES down — fall back to the Postgres scan
        }
        if (ids.isEmpty()) return null;
        Map<UUID, Conversation> byId = conversationRepo.findAllById(ids).stream()
                .filter(c -> c.isChannel() && c.isPublicChannel() && c.getDeletedAt() == null
                        && !ChatModeration.held(c.getModerationStatus()))
                .collect(java.util.stream.Collectors.toMap(Conversation::getId, c -> c));
        List<Conversation> ordered = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Conversation c = byId.get(id);
            if (c != null) ordered.add(c);
        }
        return ordered.isEmpty() ? null : ordered;
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
                .orElseThrow(() -> new ForbiddenException(
                        ChannelStreamMessages.NOT_CHANNEL_MEMBER_MSG, ChannelStreamMessages.NOT_A_MEMBER));
    }

    /** Owner, or an active admin holding {@code right}. */
    private ConversationMember requireRight(UUID channelId, UUID actorId,
                                            Predicate<AdminRights> right, String message) {
        ConversationMember actor = memberRepo.findMember(channelId, actorId).orElse(null);
        if (!ChannelRights.can(actor, right)) {
            throw new ForbiddenException(message, ChannelStreamMessages.ADMINS_ONLY);
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
        if (StringUtils.hasText(req.getCustomTitle())) r.setCustomTitle(req.getCustomTitle().trim());
        return r;
    }

    ChannelResponse toResponse(Conversation c, ConversationMember me, boolean pendingJoinRequest) {
        // A private channel has no public link — it is shared via an invite link
        // (POST /conversations/{id}/invite-links) instead.
        String shareUrl = c.isPublicChannel() && c.getHandle() != null
                ? ak.dev.irc.app.chat.util.ShareLinks.of(baseUrl, "/c/" + c.getHandle()) : null;
        boolean subscribed = me != null && me.isActive();
        // De-indexing keeps a held channel out of discovery, but the detail endpoints
        // (GET /channels/{id}, GET /channels/by-handle/{h}) are reachable by anyone
        // holding the link, so the unreviewed wording has to be withheld here too.
        // The owner authored it and keeps seeing it (§5.1).
        boolean redactInfo = ChatModeration.held(c.getModerationStatus())
                && (me == null || !me.isOwner());
        return new ChannelResponse(
                c.getId(), c.getHandle(),
                redactInfo ? null : c.getTitle(),
                redactInfo ? null : c.getDescription(),
                c.getCategory(),
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
            throw new BadRequestException(ChannelStreamMessages.CHANNEL_HANDLE_INVALID_MSG);
        }
        return h;
    }

    private String normalizeCategory(String raw) {
        if (!StringUtils.hasText(raw)) return null;
        return raw.trim().toLowerCase(Locale.ROOT);
    }
}

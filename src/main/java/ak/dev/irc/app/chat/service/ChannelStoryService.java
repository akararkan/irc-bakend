package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.dto.AdminRights;
import ak.dev.irc.app.chat.dto.request.CreateChannelStoryRequest;
import ak.dev.irc.app.chat.dto.response.ChannelStoryTrayItem;
import ak.dev.irc.app.chat.entity.Conversation;
import ak.dev.irc.app.chat.entity.ConversationMember;
import ak.dev.irc.app.chat.permission.ChannelRights;
import ak.dev.irc.app.chat.repository.ConversationMemberRepository;
import ak.dev.irc.app.chat.repository.ConversationRepository;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.post.cassandra.entity.StoryByAuthorEntity;
import ak.dev.irc.app.post.cassandra.entity.StoryLookupEntity;
import ak.dev.irc.app.post.cassandra.entity.StoryPollEntity;
import ak.dev.irc.app.post.cassandra.repository.StoryByAuthorRepository;
import ak.dev.irc.app.post.cassandra.repository.StoryLookupRepository;
import ak.dev.irc.app.post.cassandra.service.CassandraStoryPollService;
import ak.dev.irc.app.post.cassandra.service.CassandraStoryService;
import ak.dev.irc.app.post.enums.StoryLifetime;
import ak.dev.irc.app.post.enums.StoryType;
import ak.dev.irc.app.post.enums.StoryVisibility;
import ak.dev.irc.app.post.realtime.StoryTrayEvent;
import ak.dev.irc.app.post.realtime.StoryTrayEventType;
import ak.dev.irc.app.post.realtime.StoryTrayRealtimePublisher;
import ak.dev.irc.app.research.service.S3StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Telegram-style CHANNEL stories. A channel story reuses the entire story
 * infrastructure with the channel's conversation id as the "author" partition
 * and {@code visibility = CHANNEL}: the 8/16/24h per-row TTL, the view log,
 * story polls, and the per-viewer story-tray SSE all just work. This service
 * adds the channel-side permissioning (admins with {@code canManageStories}),
 * the subscriber tray, and the tray fan-out to subscribers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelStoryService {

    /** Same fan-out ceiling as the user-story delete path. */
    private static final int FANOUT_CAP = 50_000;
    private static final String MEDIA_PREFIX = "chat/channel-story";

    private final ConversationRepository conversationRepo;
    private final ConversationMemberRepository memberRepo;
    private final CassandraStoryService storyService;
    private final CassandraStoryPollService storyPollService;
    private final StoryByAuthorRepository storyRepo;
    private final StoryLookupRepository storyLookupRepo;
    private final StoryTrayRealtimePublisher trayPublisher;
    private final S3StorageService storageService;

    // ── Post ─────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public StoryByAuthorEntity post(UUID channelId, UUID actorId, CreateChannelStoryRequest req) {
        Conversation channel = requireChannel(channelId);
        requireStoryManager(channelId, actorId);
        if (!StringUtils.hasText(req.getMediaUrl()) && !StringUtils.hasText(req.getTextContent())) {
            throw new BadRequestException("A story needs media and/or text.");
        }
        String type = StringUtils.hasText(req.getStoryType()) ? req.getStoryType()
                : (StringUtils.hasText(req.getMediaUrl()) ? StoryType.IMAGE.name() : StoryType.TEXT.name());
        StoryByAuthorEntity row = storyService.createStory(
                channelId, type, StoryVisibility.CHANNEL.name(),
                req.getMediaUrl(), req.getThumbnailUrl(), req.getTextContent(),
                StoryLifetime.fromHours(req.getLifetimeHours()));
        fanout(channel, row, StoryTrayEventType.NEW_STORY);
        return row;
    }

    /** Multipart variant — uploads the media through the R2 pipeline first. */
    @Transactional(readOnly = true)
    public StoryByAuthorEntity postMultipart(UUID channelId, UUID actorId, String storyType,
                                             String textContent, Integer lifetimeHours,
                                             MultipartFile media, MultipartFile thumbnail) {
        Conversation channel = requireChannel(channelId);
        requireStoryManager(channelId, actorId);
        if ((media == null || media.isEmpty()) && !StringUtils.hasText(textContent)) {
            throw new BadRequestException("A story needs media and/or text.");
        }
        String mediaUrl = null;
        String resolvedType = storyType;
        if (media != null && !media.isEmpty()) {
            mediaUrl = storageService.getPublicUrl(storageService.upload(media, MEDIA_PREFIX));
            if (!StringUtils.hasText(resolvedType)) {
                String mime = media.getContentType();
                resolvedType = mime != null && mime.startsWith("video/")
                        ? StoryType.VIDEO.name() : StoryType.IMAGE.name();
            }
        } else if (!StringUtils.hasText(resolvedType)) {
            resolvedType = StoryType.TEXT.name();
        }
        String thumbnailUrl = null;
        if (thumbnail != null && !thumbnail.isEmpty()) {
            thumbnailUrl = storageService.getPublicUrl(storageService.upload(thumbnail, MEDIA_PREFIX));
        }
        StoryByAuthorEntity row = storyService.createStory(
                channelId, resolvedType, StoryVisibility.CHANNEL.name(),
                mediaUrl, thumbnailUrl, textContent,
                StoryLifetime.fromHours(lifetimeHours));
        fanout(channel, row, StoryTrayEventType.NEW_STORY);
        return row;
    }

    // ── Read ─────────────────────────────────────────────────────────────────────

    /** The channel's active stories — anyone for a public channel, subscribers
     *  for a private one. */
    @Transactional(readOnly = true)
    public List<StoryByAuthorEntity> list(UUID channelId, UUID viewerId) {
        Conversation channel = requireChannel(channelId);
        if (!channel.isPublicChannel()) {
            memberRepo.findMember(channelId, viewerId)
                    .filter(ConversationMember::canRead)
                    .orElseThrow(() -> new ForbiddenException(
                            "You are not a member of this channel.", "NOT_A_MEMBER"));
        }
        return storyRepo.activeStories(channelId);
    }

    /** The viewer's channel-story tray: subscribed channels that have live
     *  stories, newest story first per channel. */
    @Transactional(readOnly = true)
    public List<ChannelStoryTrayItem> tray(UUID viewerId) {
        List<ChannelStoryTrayItem> out = new ArrayList<>();
        for (Conversation channel : memberRepo.findMySubscribedChannels(viewerId)) {
            List<StoryByAuthorEntity> stories = storyRepo.activeStories(channel.getId());
            if (stories.isEmpty()) continue;
            out.add(new ChannelStoryTrayItem(
                    channel.getId(), channel.getHandle(), channel.getTitle(),
                    publicUrl(channel.getAvatarKey()), channel.isVerified(), stories));
        }
        return out;
    }

    // ── Delete ───────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public void delete(UUID channelId, UUID storyId, UUID actorId) {
        Conversation channel = requireChannel(channelId);
        requireStoryManager(channelId, actorId);
        StoryLookupEntity meta = storyLookupRepo.findById(storyId)
                .filter(m -> channelId.equals(m.getAuthorId()))
                .orElseThrow(() -> new ResourceNotFoundException("Story", "id", storyId));

        storyRepo.delete(channelId, meta.getCreatedAt(), storyId);
        storyLookupRepo.deleteById(storyId);
        try {
            storyPollService.deletePollFor(storyId);
        } catch (Exception e) {
            log.warn("[CHANNEL-STORY] poll cleanup for {} failed: {}", storyId, e.getMessage());
        }
        fanout(channel, StoryByAuthorEntity.builder()
                .authorId(channelId).storyId(storyId).build(), StoryTrayEventType.STORY_REMOVED);
    }

    // ── Story poll ───────────────────────────────────────────────────────────────

    /** Attach an A/B poll to a channel story (admins with the story right).
     *  Votes/results reuse the normal {@code /polls/*} endpoints. */
    @Transactional(readOnly = true)
    public StoryPollEntity createPoll(UUID channelId, UUID storyId, UUID actorId,
                                      String question, String optionA, String optionB) {
        requireChannel(channelId);
        requireStoryManager(channelId, actorId);
        // The poll service's author check compares against the story's author
        // partition — which IS the channel id for a channel story.
        return storyPollService.createPoll(storyId, channelId, question, optionA, optionB);
    }

    // ── internals ────────────────────────────────────────────────────────────────

    /** Tray fan-out to every active subscriber (and the poster's own tray). */
    private void fanout(Conversation channel, StoryByAuthorEntity row, StoryTrayEventType type) {
        StoryTrayEvent event = StoryTrayEvent.builder()
                .eventType(type)
                .authorId(channel.getId())
                .authorUsername(channel.getHandle())
                .authorFullName(channel.getTitle())
                .authorAvatarUrl(publicUrl(channel.getAvatarKey()))
                .storyId(row.getStoryId())
                .storyType(safeType(row.getStoryType()))
                .visibility(StoryVisibility.CHANNEL)
                .thumbnailUrl(row.getThumbnailUrl())
                .expiresAt(row.getExpiresAt() == null ? null
                        : LocalDateTime.ofInstant(row.getExpiresAt(), ZoneOffset.UTC))
                .build();
        int delivered = 0;
        for (UUID memberId : memberRepo.findActiveMemberIds(channel.getId())) {
            if (delivered++ >= FANOUT_CAP) break;
            try {
                trayPublisher.publish(memberId, event);
            } catch (Exception e) {
                log.debug("[CHANNEL-STORY] tray publish to {} skipped: {}", memberId, e.getMessage());
            }
        }
    }

    private static StoryType safeType(String raw) {
        try { return raw == null ? null : StoryType.valueOf(raw); }
        catch (IllegalArgumentException e) { return null; }
    }

    private Conversation requireChannel(UUID id) {
        return conversationRepo.findById(id)
                .filter(c -> c.getDeletedAt() == null && c.isChannel())
                .orElseThrow(() -> new ResourceNotFoundException("Channel", "id", id));
    }

    private void requireStoryManager(UUID channelId, UUID actorId) {
        ConversationMember actor = memberRepo.findMember(channelId, actorId).orElse(null);
        Predicate<AdminRights> right = AdminRights::isCanManageStories;
        if (!ChannelRights.can(actor, right)) {
            throw new ForbiddenException("You cannot manage this channel's stories.", "ADMINS_ONLY");
        }
    }

    private String publicUrl(String key) {
        if (!StringUtils.hasText(key)) return null;
        try { return storageService.getPublicUrl(key); } catch (Exception e) { return null; }
    }
}

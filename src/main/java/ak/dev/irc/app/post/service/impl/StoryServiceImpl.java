package ak.dev.irc.app.post.service.impl;

import ak.dev.irc.app.common.enums.AuditAction;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.post.dto.story.*;
import ak.dev.irc.app.post.entity.*;
import ak.dev.irc.app.post.enums.StoryType;
import ak.dev.irc.app.post.enums.StoryVisibility;
import ak.dev.irc.app.post.realtime.*;
import ak.dev.irc.app.post.repository.*;
import ak.dev.irc.app.post.service.StoryService;
import ak.dev.irc.app.post.sound.entity.PostSound;
import ak.dev.irc.app.post.sound.entity.Sound;
import ak.dev.irc.app.post.sound.repository.PostSoundRepository;
import ak.dev.irc.app.post.sound.repository.SoundRepository;
import ak.dev.irc.app.research.service.S3StorageService;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.CloseFriendsRepository;
import ak.dev.irc.app.user.repository.UserFollowRepository;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class StoryServiceImpl implements StoryService {

    private final UserRepository          userRepository;
    private final UserFollowRepository    followRepository;
    private final CloseFriendsRepository  closeFriendsRepository;
    private final StoryRepository         storyRepository;
    private final StoryViewRepository     viewRepository;
    private final StoryHighlightRepository highlightRepository;
    private final StoryPollRepository     pollRepository;
    private final StoryPollVoteRepository pollVoteRepository;
    private final PostSoundRepository     postSoundRepository;
    private final SoundRepository         soundRepository;
    private final StoryRealtimeService    realtimeService;
    private final S3StorageService        s3;

    private static final String STORY_MEDIA_PREFIX = "stories/media";
    private static final int    VIDEO_MAX_SECONDS  = 30;

    // ══════════════════════════════════════════════════════════════════════════
    //  CREATE
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public StoryResponse createTextStory(CreateTextStoryRequest req, UUID authorId) {
        User author = findActiveOrThrow(authorId);

        Story story = Story.builder()
            .author(author)
            .storyType(StoryType.TEXT)
            .visibility(req.visibility() != null ? req.visibility() : StoryVisibility.PUBLIC)
            .textContent(req.textContent())
            .backgroundType(req.backgroundType())
            .backgroundValue(req.backgroundValue())
            .overlaysJson(req.overlaysJson())
            .expiresAt(LocalDateTime.now().plusHours(24))
            .build();
        story.audit(AuditAction.CREATE, "Text story created");
        storyRepository.save(story);

        if (req.soundId() != null) attachSound(story, req.soundId(), req.soundClipStartSeconds(), req.soundVolume());

        log.info("User [{}] created TEXT story [{}]", authorId, story.getId());
        return toResponse(story, authorId);
    }

    @Override
    public StoryResponse createMediaStory(CreateMediaStoryRequest req, MultipartFile media, UUID authorId) {
        User author = findActiveOrThrow(authorId);

        StoryType type = req.storyType();
        if (type != StoryType.IMAGE && type != StoryType.VIDEO)
            throw new BadRequestException("storyType must be IMAGE or VIDEO for media stories.", "INVALID_STORY_TYPE");

        validateMedia(media, type);

        String s3Key      = s3.upload(media, STORY_MEDIA_PREFIX + "/" + authorId);
        String mediaUrl   = s3.getPublicUrl(s3Key);
        String thumbUrl   = null;
        String thumbS3Key = null;

        Story story = Story.builder()
            .author(author)
            .storyType(type)
            .visibility(req.visibility() != null ? req.visibility() : StoryVisibility.PUBLIC)
            .textContent(req.textContent())
            .overlaysJson(req.overlaysJson())
            .mediaUrl(mediaUrl)
            .mediaS3Key(s3Key)
            .thumbnailUrl(thumbUrl)
            .thumbnailS3Key(thumbS3Key)
            .expiresAt(LocalDateTime.now().plusHours(24))
            .build();
        story.audit(AuditAction.CREATE, type + " story created");
        storyRepository.save(story);

        if (req.soundId() != null) attachSound(story, req.soundId(), req.soundClipStartSeconds(), req.soundVolume());

        log.info("User [{}] created {} story [{}]", authorId, type, story.getId());
        return toResponse(story, authorId);
    }

    @Override
    public StoryResponse shareToStory(ShareToStoryRequest req, UUID authorId) {
        User author = findActiveOrThrow(authorId);

        // Validate it's a LINKED_* type
        if (!req.storyType().name().startsWith("LINKED_"))
            throw new BadRequestException("storyType must be a LINKED_* type for share-to-story.", "INVALID_STORY_TYPE");

        Story story = Story.builder()
            .author(author)
            .storyType(req.storyType())
            .visibility(req.visibility() != null ? req.visibility() : StoryVisibility.PUBLIC)
            .textContent(req.caption())
            .linkedContentId(req.linkedContentId())
            .expiresAt(LocalDateTime.now().plusHours(24))
            .build();
        story.audit(AuditAction.CREATE, "Share-to-story: " + req.storyType());
        storyRepository.save(story);

        return toResponse(story, authorId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  READ
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<StoryTrayGroup> getStoryTray(UUID viewerId) {
        List<UUID> followedIds = followRepository.findFollowingIds(viewerId);
        if (followedIds.isEmpty()) return List.of();

        List<UUID> authorIds = storyRepository.findAuthorIdsWithActiveStories(followedIds, LocalDateTime.now());
        Set<UUID> closeFriendIds = closeFriendsRepository.findFriendIds(viewerId);

        return authorIds.stream().map(authorId -> {
            User author = userRepository.findById(authorId).orElse(null);
            if (author == null) return null;

            List<Story> stories = storyRepository.findActiveByAuthorId(authorId, LocalDateTime.now())
                .stream()
                .filter(s -> isVisibleTo(s, viewerId, closeFriendIds))
                .collect(Collectors.toList());

            if (stories.isEmpty()) return null;

            Set<UUID> storyIds = stories.stream().map(Story::getId).collect(Collectors.toSet());
            Set<UUID> seenIds  = viewRepository.findSeenStoryIds(viewerId, storyIds);
            boolean hasUnseen  = stories.stream().anyMatch(s -> !seenIds.contains(s.getId()));

            List<StoryResponse> storyResponses = stories.stream()
                .map(s -> toResponseWithSeenFlag(s, viewerId, seenIds))
                .collect(Collectors.toList());

            return new StoryTrayGroup(
                authorId,
                author.getUsername(),
                author.getProfileImage(),
                hasUnseen,
                stories.size(),
                storyResponses
            );
        }).filter(Objects::nonNull).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoryResponse> getStoriesByAuthor(UUID authorId, UUID viewerId) {
        Set<UUID> closeFriendIds = viewerId != null
            ? closeFriendsRepository.findFriendIds(authorId)
            : Set.of();

        return storyRepository.findActiveByAuthorId(authorId, LocalDateTime.now())
            .stream()
            .filter(s -> isVisibleTo(s, viewerId, closeFriendIds))
            .map(s -> toResponse(s, viewerId))
            .collect(Collectors.toList());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  INTERACT
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void recordView(UUID storyId, UUID viewerId, int watchDurationMs) {
        Story story = findActiveStoryOrThrow(storyId);

        StoryView.StoryViewId vid = new StoryView.StoryViewId(storyId, viewerId);
        if (!viewRepository.existsById(vid)) {
            User viewer = userRepository.findById(viewerId).orElse(null);
            StoryView view = StoryView.builder()
                .id(vid)
                .story(story)
                .viewer(viewer)
                .watchDurationMs(watchDurationMs)
                .build();
            viewRepository.save(view);
            storyRepository.incrementViewCount(storyId);

            broadcastCount(story);
        } else {
            // Update watch duration if new value is longer
            viewRepository.findById(vid).ifPresent(v -> {
                if (watchDurationMs > (v.getWatchDurationMs() != null ? v.getWatchDurationMs() : 0)) {
                    v.setWatchDurationMs(watchDurationMs);
                    viewRepository.save(v);
                }
            });
        }
    }

    @Override
    public void reactToStory(UUID storyId, UUID viewerId, String emoji) {
        Story story = findActiveStoryOrThrow(storyId);
        User viewer = findActiveOrThrow(viewerId);

        StoryView.StoryViewId vid = new StoryView.StoryViewId(storyId, viewerId);
        StoryView view = viewRepository.findById(vid).orElseGet(() ->
            StoryView.builder().id(vid).story(story).viewer(viewer).build());

        String previous = view.getReactionEmoji();
        view.setReactionEmoji(emoji);
        viewRepository.save(view);

        if (previous == null) storyRepository.incrementReactionCount(storyId);

        StoryRealtimeEvent event = StoryRealtimeEvent.builder()
            .eventType(StoryRealtimeEventType.STORY_REACTED)
            .storyId(storyId)
            .actorId(viewerId)
            .actorUsername(viewer.getUsername())
            .actorAvatarUrl(viewer.getProfileImage())
            .reactionEmoji(emoji)
            .reactionCount(story.getReactionCount() + (previous == null ? 1 : 0))
            .build();
        realtimeService.broadcastEvent(storyId, event);
    }

    @Override
    public void replyToStory(UUID storyId, UUID viewerId, String text) {
        Story story = findActiveStoryOrThrow(storyId);
        User viewer = findActiveOrThrow(viewerId);

        StoryView.StoryViewId vid = new StoryView.StoryViewId(storyId, viewerId);
        viewRepository.findById(vid).ifPresent(v -> { v.setReplied(true); viewRepository.save(v); });

        storyRepository.incrementReplyCount(storyId);

        StoryRealtimeEvent event = StoryRealtimeEvent.builder()
            .eventType(StoryRealtimeEventType.STORY_REPLIED)
            .storyId(storyId)
            .actorId(viewerId)
            .actorUsername(viewer.getUsername())
            .actorAvatarUrl(viewer.getProfileImage())
            .replyText(text)
            .build();
        realtimeService.broadcastEvent(storyId, event);
        log.info("User [{}] replied to story [{}]", viewerId, storyId);
    }

    @Override
    public void voteOnPoll(UUID storyId, UUID voterId, String choice) {
        Story story = findActiveStoryOrThrow(storyId);
        User voter  = findActiveOrThrow(voterId);

        StoryPoll poll = pollRepository.findByStoryId(storyId)
            .orElseThrow(() -> new ResourceNotFoundException("StoryPoll", "storyId", storyId));

        if (pollVoteRepository.existsByPollIdAndVoterId(poll.getId(), voterId))
            throw new BadRequestException("You have already voted on this poll.", "ALREADY_VOTED");

        if (!"A".equals(choice) && !"B".equals(choice))
            throw new BadRequestException("Choice must be A or B.", "INVALID_CHOICE");

        StoryPollVote vote = StoryPollVote.builder()
            .poll(poll).voter(voter).choice(choice).build();
        pollVoteRepository.save(vote);

        if ("A".equals(choice)) poll.setVoteACount(poll.getVoteACount() + 1);
        else                     poll.setVoteBCount(poll.getVoteBCount() + 1);
        pollRepository.save(poll);

        StoryRealtimeEvent event = StoryRealtimeEvent.builder()
            .eventType(StoryRealtimeEventType.STORY_POLL_VOTED)
            .storyId(storyId)
            .actorId(voterId)
            .pollChoice(choice)
            .pollVoteACount(poll.getVoteACount())
            .pollVoteBCount(poll.getVoteBCount())
            .build();
        realtimeService.broadcastEvent(storyId, event);
    }

    @Override
    public void deleteStory(UUID storyId, UUID requesterId) {
        Story story = storyRepository.findById(storyId)
            .orElseThrow(() -> new ResourceNotFoundException("Story", "id", storyId));

        if (!story.getAuthor().getId().equals(requesterId))
            throw new ForbiddenException("Only the author can delete this story.", "NOT_AUTHOR");

        if (story.getMediaS3Key() != null) {
            try { s3.delete(story.getMediaS3Key()); } catch (Exception e) { log.warn("S3 delete failed: {}", e.getMessage()); }
        }

        story.setDeleted(true);
        story.setDeletedAt(LocalDateTime.now());
        story.audit(AuditAction.DELETE, "Story deleted by author");
        storyRepository.save(story);

        realtimeService.broadcastEvent(storyId, StoryRealtimeEvent.builder()
            .eventType(StoryRealtimeEventType.STORY_DELETED)
            .storyId(storyId)
            .actorId(requesterId)
            .build());
    }

    @Override
    public int expireStories() {
        int count = storyRepository.softDeleteExpired(LocalDateTime.now());
        log.info("StoryExpiryJob: {} stories expired", count);
        return count;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  HIGHLIGHTS
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public StoryHighlightResponse createHighlight(CreateHighlightRequest req, UUID authorId) {
        User author = findActiveOrThrow(authorId);
        StoryHighlight highlight = StoryHighlight.builder()
            .author(author)
            .title(req.title())
            .displayOrder(req.displayOrder())
            .build();
        highlight.audit(AuditAction.CREATE, "Highlight created");
        highlightRepository.save(highlight);
        return toHighlightResponse(highlight);
    }

    @Override
    public StoryHighlightResponse updateHighlight(UUID highlightId, UpdateHighlightRequest req, UUID authorId) {
        StoryHighlight highlight = findHighlightOrThrow(highlightId, authorId);
        if (req.title() != null)        highlight.setTitle(req.title());
        if (req.displayOrder() != null) highlight.setDisplayOrder(req.displayOrder());
        highlight.audit(AuditAction.UPDATE, "Highlight updated");
        highlightRepository.save(highlight);
        return toHighlightResponse(highlight);
    }

    @Override
    public StoryHighlightResponse addStoryToHighlight(UUID highlightId, UUID storyId, UUID authorId) {
        StoryHighlight highlight = findHighlightOrThrow(highlightId, authorId);
        Story story = storyRepository.findById(storyId)
            .orElseThrow(() -> new ResourceNotFoundException("Story", "id", storyId));
        if (!story.getAuthor().getId().equals(authorId))
            throw new ForbiddenException("Story does not belong to you.", "NOT_AUTHOR");
        story.setHighlight(highlight);
        storyRepository.save(story);
        return toHighlightResponse(highlight);
    }

    @Override
    public void removeStoryFromHighlight(UUID highlightId, UUID storyId, UUID authorId) {
        findHighlightOrThrow(highlightId, authorId);
        Story story = storyRepository.findById(storyId)
            .orElseThrow(() -> new ResourceNotFoundException("Story", "id", storyId));
        story.setHighlight(null);
        storyRepository.save(story);
    }

    @Override
    public void deleteHighlight(UUID highlightId, UUID authorId) {
        StoryHighlight highlight = findHighlightOrThrow(highlightId, authorId);
        highlight.getStories().forEach(s -> s.setHighlight(null));
        highlightRepository.delete(highlight);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoryHighlightResponse> getHighlights(UUID authorId) {
        return highlightRepository.findByAuthorId(authorId).stream()
            .map(this::toHighlightResponse)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StoryResponse> getHighlightStories(UUID highlightId, Pageable pageable) {
        return storyRepository.findByHighlightId(highlightId, pageable)
            .map(s -> toResponse(s, null));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StoryViewerResponse> getViewers(UUID storyId, UUID requesterId, Pageable pageable) {
        Story story = storyRepository.findById(storyId)
            .orElseThrow(() -> new ResourceNotFoundException("Story", "id", storyId));
        if (!story.getAuthor().getId().equals(requesterId))
            throw new ForbiddenException("Only the story author can view the viewer list.", "NOT_AUTHOR");

        return viewRepository.findByStoryId(storyId, pageable).map(v -> {
            User viewer = v.getViewer();
            return new StoryViewerResponse(
                v.getId().getViewerId(),
                viewer != null ? viewer.getUsername() : null,
                viewer != null ? viewer.getProfileImage() : null,
                v.getWatchDurationMs(),
                v.getReactionEmoji(),
                v.isReplied(),
                v.getViewedAt()
            );
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private void attachSound(Story story, UUID soundId, int clipStart, float volume) {
        Sound sound = soundRepository.findById(soundId)
            .orElseThrow(() -> new ResourceNotFoundException("Sound", "id", soundId));
        PostSound ps = PostSound.builder()
            .story(story).sound(sound).clipStartSeconds(clipStart).volume(volume).build();
        postSoundRepository.save(ps);
        soundRepository.incrementUseCount(soundId);
    }

    private boolean isVisibleTo(Story story, UUID viewerId, Set<UUID> closeFriendIds) {
        return switch (story.getVisibility()) {
            case PUBLIC          -> true;
            case FOLLOWERS_ONLY  -> viewerId != null;
            case CLOSE_FRIENDS   -> viewerId != null && closeFriendIds.contains(viewerId);
            case ONLY_ME         -> viewerId != null && viewerId.equals(story.getAuthor().getId());
        };
    }

    private StoryResponse toResponse(Story s, UUID viewerId) {
        return toResponseWithSeenFlag(s, viewerId, Set.of());
    }

    private StoryResponse toResponseWithSeenFlag(Story s, UUID viewerId, Set<UUID> seenIds) {
        PostSound ps = s.getSound();
        StoryResponse.SoundBrief soundBrief = ps == null ? null : new StoryResponse.SoundBrief(
            ps.getSound().getId(), ps.getSound().getTitle(), ps.getSound().getArtistName(),
            ps.getSound().getAudioUrl(), ps.getSound().getCoverArtUrl(),
            ps.getClipStartSeconds(), ps.getVolume()
        );

        String myEmoji = null;
        if (viewerId != null) {
            StoryView.StoryViewId vid = new StoryView.StoryViewId(s.getId(), viewerId);
            myEmoji = viewRepository.findById(vid).map(StoryView::getReactionEmoji).orElse(null);
        }

        User author = s.getAuthor();
        return new StoryResponse(
            s.getId(),
            new StoryResponse.StoryAuthor(
                author.getId(), author.getUsername(), author.getFullName(), author.getProfileImage(),
                author.getRole().name()),
            s.getStoryType(),
            s.getVisibility(),
            s.getTextContent(),
            s.getBackgroundType(),
            s.getBackgroundValue(),
            s.getMediaUrl(),
            s.getThumbnailUrl(),
            s.getDurationSeconds(),
            s.getLinkedContentId(),
            s.getLinkedContentSnapshot(),
            s.getOverlaysJson(),
            soundBrief,
            s.getExpiresAt(),
            s.isExpired(),
            s.getViewCount(),
            s.getReactionCount(),
            s.getReplyCount(),
            myEmoji,
            seenIds.contains(s.getId()),
            s.getCreatedAt()
        );
    }

    private StoryHighlightResponse toHighlightResponse(StoryHighlight h) {
        List<StoryResponse> stories = h.getStories().stream()
            .map(s -> toResponse(s, null))
            .collect(Collectors.toList());
        return new StoryHighlightResponse(
            h.getId(), h.getAuthor().getId(), h.getTitle(), h.getCoverUrl(),
            h.getDisplayOrder(), stories.size(), stories, h.getCreatedAt()
        );
    }

    private void broadcastCount(Story story) {
        realtimeService.broadcastEvent(story.getId(), StoryRealtimeEvent.builder()
            .eventType(StoryRealtimeEventType.VIEW_COUNT_UPDATED)
            .storyId(story.getId())
            .viewCount(story.getViewCount() + 1)
            .build());
    }

    private Story findActiveStoryOrThrow(UUID id) {
        Story s = storyRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Story", "id", id));
        if (s.isDeleted() || s.isExpired())
            throw new ResourceNotFoundException("Story", "id", id);
        return s;
    }

    private StoryHighlight findHighlightOrThrow(UUID highlightId, UUID authorId) {
        StoryHighlight h = highlightRepository.findById(highlightId)
            .orElseThrow(() -> new ResourceNotFoundException("StoryHighlight", "id", highlightId));
        if (!h.getAuthor().getId().equals(authorId))
            throw new ForbiddenException("Only the highlight author can modify this highlight.", "NOT_AUTHOR");
        return h;
    }

    private User findActiveOrThrow(UUID id) {
        return userRepository.findActiveById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
    }

    private void validateMedia(MultipartFile file, StoryType type) {
        if (file == null || file.isEmpty())
            throw new BadRequestException("Media file is required.", "EMPTY_FILE");
        String ct = file.getContentType();
        if (ct == null) throw new BadRequestException("Unknown media type.", "UNKNOWN_MEDIA_TYPE");
        if (type == StoryType.IMAGE && !ct.startsWith("image/"))
            throw new BadRequestException("Image story requires an image file.", "INVALID_FILE_TYPE");
        if (type == StoryType.VIDEO && !ct.startsWith("video/"))
            throw new BadRequestException("Video story requires a video file.", "INVALID_FILE_TYPE");
    }
}

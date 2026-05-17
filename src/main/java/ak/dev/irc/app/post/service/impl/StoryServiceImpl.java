package ak.dev.irc.app.post.service.impl;

import ak.dev.irc.app.common.enums.AuditAction;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.post.dto.story.*;
import ak.dev.irc.app.post.entity.*;
import ak.dev.irc.app.post.enums.StoryType;
import ak.dev.irc.app.post.enums.StoryVisibility;
import ak.dev.irc.app.post.enums.ViewerRelationship;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class StoryServiceImpl implements StoryService {

    private final UserRepository             userRepository;
    private final UserFollowRepository       followRepository;
    private final CloseFriendsRepository     closeFriendsRepository;
    private final StoryRepository            storyRepository;
    private final StoryViewRepository        viewRepository;
    private final StoryHighlightRepository   highlightRepository;
    private final StoryPollRepository        pollRepository;
    private final StoryPollVoteRepository    pollVoteRepository;
    private final PostSoundRepository        postSoundRepository;
    private final SoundRepository            soundRepository;
    private final StoryRealtimeService       realtimeService;
    private final StoryTrayRealtimePublisher trayPublisher;
    private final S3StorageService           s3;

    /** Max followers to fan-out to via SSE. Beyond this, they see the story on next tray load. */
    private static final int    FANOUT_LIMIT       = 500;
    private static final String STORY_MEDIA_PREFIX = "stories/media";

    // ══════════════════════════════════════════════════════════════════════════
    //  CREATE — with tray fan-out after commit
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public StoryResponse createTextStory(CreateTextStoryRequest req, UUID authorId) {
        User author = findActiveOrThrow(authorId);
        StoryVisibility vis = req.visibility() != null ? req.visibility() : StoryVisibility.PUBLIC;

        Story story = Story.builder()
            .author(author).storyType(StoryType.TEXT).visibility(vis)
            .textContent(req.textContent()).backgroundType(req.backgroundType())
            .backgroundValue(req.backgroundValue()).overlaysJson(req.overlaysJson())
            .expiresAt(LocalDateTime.now().plusHours(24)).build();
        story.audit(AuditAction.CREATE, "Text story created");
        storyRepository.save(story);

        if (req.soundId() != null)
            attachSound(story, req.soundId(), req.soundClipStartSeconds(), req.soundVolume());

        fanOutTrayUpdate(story, author);
        log.info("User [{}] created TEXT story [{}]", authorId, story.getId());
        return toResponse(story, authorId, false);
    }

    @Override
    public StoryResponse createMediaStory(CreateMediaStoryRequest req, MultipartFile media, UUID authorId) {
        User author = findActiveOrThrow(authorId);
        StoryType type = req.storyType();
        if (type != StoryType.IMAGE && type != StoryType.VIDEO)
            throw new BadRequestException("storyType must be IMAGE or VIDEO for media stories.", "INVALID_STORY_TYPE");
        validateMedia(media, type);

        String s3Key = s3.upload(media, STORY_MEDIA_PREFIX + "/" + authorId);
        String url   = s3.getPublicUrl(s3Key);
        StoryVisibility vis = req.visibility() != null ? req.visibility() : StoryVisibility.PUBLIC;

        Story story = Story.builder()
            .author(author).storyType(type).visibility(vis)
            .textContent(req.textContent()).overlaysJson(req.overlaysJson())
            .mediaUrl(url).mediaS3Key(s3Key)
            .expiresAt(LocalDateTime.now().plusHours(24)).build();
        story.audit(AuditAction.CREATE, type + " story created");
        storyRepository.save(story);

        if (req.soundId() != null)
            attachSound(story, req.soundId(), req.soundClipStartSeconds(), req.soundVolume());

        fanOutTrayUpdate(story, author);
        log.info("User [{}] created {} story [{}]", authorId, type, story.getId());
        return toResponse(story, authorId, false);
    }

    @Override
    public StoryResponse shareToStory(ShareToStoryRequest req, UUID authorId) {
        User author = findActiveOrThrow(authorId);
        if (!req.storyType().name().startsWith("LINKED_"))
            throw new BadRequestException("storyType must be LINKED_* for share-to-story.", "INVALID_STORY_TYPE");

        StoryVisibility vis = req.visibility() != null ? req.visibility() : StoryVisibility.PUBLIC;
        Story story = Story.builder()
            .author(author).storyType(req.storyType()).visibility(vis)
            .textContent(req.caption()).linkedContentId(req.linkedContentId())
            .expiresAt(LocalDateTime.now().plusHours(24)).build();
        story.audit(AuditAction.CREATE, "Share-to-story: " + req.storyType());
        storyRepository.save(story);

        fanOutTrayUpdate(story, author);
        return toResponse(story, authorId, false);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  READ — correct visibility enforcement (no extra DB query per story)
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<StoryTrayGroup> getStoryTray(UUID viewerId) {
        List<UUID> followedIds = followRepository.findFollowingIds(viewerId);
        if (followedIds.isEmpty()) return List.of();

        List<UUID> authorIds = storyRepository.findAuthorIdsWithActiveStories(
            followedIds, LocalDateTime.now());
        if (authorIds.isEmpty()) return List.of();

        Set<UUID> viewerFollowedSet = new HashSet<>(followedIds);

        return authorIds.stream().map(authorId -> {
            User author = userRepository.findById(authorId).orElse(null);
            if (author == null) return null;

            // Load author's close-friends once per author
            Set<UUID> authorCloseFriends = closeFriendsRepository.findFriendIds(authorId);

            List<Story> visible = storyRepository
                .findActiveByAuthorId(authorId, LocalDateTime.now()).stream()
                .filter(s -> isVisibleTo(s, viewerId, authorId, viewerFollowedSet, authorCloseFriends))
                .collect(Collectors.toList());

            if (visible.isEmpty()) return null;

            Set<UUID> storyIds = visible.stream().map(Story::getId).collect(Collectors.toSet());
            Set<UUID> seenIds  = viewRepository.findSeenStoryIds(viewerId, storyIds);
            boolean   hasUnseen = visible.stream().anyMatch(s -> !seenIds.contains(s.getId()));

            List<StoryResponse> responses = visible.stream()
                .map(s -> toResponseWithSeenFlag(s, viewerId, seenIds, false))
                .collect(Collectors.toList());

            return new StoryTrayGroup(
                authorId, author.getUsername(), author.getProfileImage(),
                hasUnseen, visible.size(), responses);
        }).filter(Objects::nonNull).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoryResponse> getStoriesByAuthor(UUID authorId, UUID viewerId) {
        boolean isOwner       = viewerId != null && viewerId.equals(authorId);
        boolean viewerFollows = !isOwner && viewerId != null
                                && followRepository.isFollowing(viewerId, authorId);
        Set<UUID> closeFriendIds   = closeFriendsRepository.findFriendIds(authorId);
        Set<UUID> viewerFollowedSet = viewerFollows ? Set.of(authorId) : Set.of();

        return storyRepository.findActiveByAuthorId(authorId, LocalDateTime.now()).stream()
            .filter(s -> isOwner || isVisibleTo(s, viewerId, authorId, viewerFollowedSet, closeFriendIds))
            .map(s -> toResponse(s, viewerId, isOwner))
            .collect(Collectors.toList());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  RECORD VIEW — captures ViewerRelationship + increments counter
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void recordView(UUID storyId, UUID viewerId, int watchDurationMs) {
        Story story    = findActiveStoryOrThrow(storyId);
        UUID  authorId = story.getAuthor().getId();

        StoryView.StoryViewId vid = new StoryView.StoryViewId(storyId, viewerId);

        if (!viewRepository.existsById(vid)) {
            ViewerRelationship rel = resolveRelationship(viewerId, authorId);
            User viewer = userRepository.findById(viewerId).orElse(null);
            StoryView view = StoryView.builder()
                .id(vid).story(story).viewer(viewer)
                .viewerRelationship(rel).watchDurationMs(watchDurationMs).build();
            viewRepository.save(view);
            storyRepository.incrementViewCount(storyId);

            afterCommit(() -> realtimeService.broadcastEvent(storyId,
                StoryRealtimeEvent.builder()
                    .eventType(StoryRealtimeEventType.VIEW_COUNT_UPDATED)
                    .storyId(storyId).actorId(viewerId)
                    .viewCount(story.getViewCount() + 1).build()));
        } else {
            viewRepository.findByIdStoryIdAndIdViewerId(storyId, viewerId).ifPresent(v -> {
                if (watchDurationMs > (v.getWatchDurationMs() != null ? v.getWatchDurationMs() : 0)) {
                    v.setWatchDurationMs(watchDurationMs);
                    viewRepository.save(v);
                }
            });
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  REACT
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void reactToStory(UUID storyId, UUID viewerId, String emoji) {
        Story story  = findActiveStoryOrThrow(storyId);
        User  viewer = findActiveOrThrow(viewerId);

        StoryView.StoryViewId vid = new StoryView.StoryViewId(storyId, viewerId);
        StoryView view = viewRepository.findById(vid).orElseGet(() -> {
            ViewerRelationship rel = resolveRelationship(viewerId, story.getAuthor().getId());
            return StoryView.builder()
                .id(vid).story(story).viewer(viewer).viewerRelationship(rel).build();
        });

        String previous = view.getReactionEmoji();
        view.setReactionEmoji(emoji);
        viewRepository.save(view);
        if (previous == null) storyRepository.incrementReactionCount(storyId);

        afterCommit(() -> realtimeService.broadcastEvent(storyId,
            StoryRealtimeEvent.builder()
                .eventType(StoryRealtimeEventType.STORY_REACTED)
                .storyId(storyId).actorId(viewerId)
                .actorUsername(viewer.getUsername()).actorAvatarUrl(viewer.getProfileImage())
                .reactionEmoji(emoji)
                .reactionCount(story.getReactionCount() + (previous == null ? 1 : 0))
                .build()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  REPLY
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void replyToStory(UUID storyId, UUID viewerId, String text) {
        Story story  = findActiveStoryOrThrow(storyId);
        User  viewer = findActiveOrThrow(viewerId);

        viewRepository.findByIdStoryIdAndIdViewerId(storyId, viewerId)
            .ifPresent(v -> { v.setReplied(true); viewRepository.save(v); });
        storyRepository.incrementReplyCount(storyId);

        afterCommit(() -> realtimeService.broadcastEvent(storyId,
            StoryRealtimeEvent.builder()
                .eventType(StoryRealtimeEventType.STORY_REPLIED)
                .storyId(storyId).actorId(viewerId)
                .actorUsername(viewer.getUsername()).actorAvatarUrl(viewer.getProfileImage())
                .replyText(text).build()));

        log.info("User [{}] replied to story [{}]", viewerId, storyId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  POLL VOTE
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void voteOnPoll(UUID storyId, UUID voterId, String choice) {
        Story     story = findActiveStoryOrThrow(storyId);
        User      voter = findActiveOrThrow(voterId);
        StoryPoll poll  = pollRepository.findByStoryId(storyId)
            .orElseThrow(() -> new ResourceNotFoundException("StoryPoll", "storyId", storyId));

        if (pollVoteRepository.existsByPollIdAndVoterId(poll.getId(), voterId))
            throw new BadRequestException("You have already voted on this poll.", "ALREADY_VOTED");
        if (!"A".equals(choice) && !"B".equals(choice))
            throw new BadRequestException("Choice must be A or B.", "INVALID_CHOICE");

        pollVoteRepository.save(
            StoryPollVote.builder().poll(poll).voter(voter).choice(choice).build());

        if ("A".equals(choice)) poll.setVoteACount(poll.getVoteACount() + 1);
        else                     poll.setVoteBCount(poll.getVoteBCount() + 1);
        pollRepository.save(poll);

        afterCommit(() -> realtimeService.broadcastEvent(storyId,
            StoryRealtimeEvent.builder()
                .eventType(StoryRealtimeEventType.STORY_POLL_VOTED)
                .storyId(storyId).actorId(voterId).pollChoice(choice)
                .pollVoteACount(poll.getVoteACount()).pollVoteBCount(poll.getVoteBCount())
                .build()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  DELETE
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void deleteStory(UUID storyId, UUID requesterId) {
        Story story = storyRepository.findById(storyId)
            .orElseThrow(() -> new ResourceNotFoundException("Story", "id", storyId));
        if (!story.getAuthor().getId().equals(requesterId))
            throw new ForbiddenException("Only the author can delete this story.", "NOT_AUTHOR");

        if (story.getMediaS3Key() != null) {
            try { s3.delete(story.getMediaS3Key()); }
            catch (Exception e) { log.warn("R2 delete failed: {}", e.getMessage()); }
        }

        story.setDeleted(true);
        story.setDeletedAt(LocalDateTime.now());
        story.audit(AuditAction.DELETE, "Story deleted by author");
        storyRepository.save(story);

        afterCommit(() -> {
            realtimeService.broadcastEvent(storyId, StoryRealtimeEvent.builder()
                .eventType(StoryRealtimeEventType.STORY_DELETED)
                .storyId(storyId).actorId(requesterId).build());
            fanOutTrayRemovalAsync(story, story.getAuthor());
        });
    }

    @Override
    public int expireStories() {
        int count = storyRepository.softDeleteExpired(LocalDateTime.now());
        log.info("StoryExpiryJob: {} stories expired", count);
        return count;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  VIEW BREAKDOWN — only for the author
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public StoryResponse.ViewBreakdown getViewBreakdown(UUID storyId, UUID requesterId) {
        Story story = storyRepository.findById(storyId)
            .orElseThrow(() -> new ResourceNotFoundException("Story", "id", storyId));
        if (!story.getAuthor().getId().equals(requesterId))
            throw new ForbiddenException("Only the story author can view the breakdown.", "NOT_AUTHOR");
        return buildBreakdown(storyId);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  HIGHLIGHTS
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public StoryHighlightResponse createHighlight(CreateHighlightRequest req, UUID authorId) {
        User author = findActiveOrThrow(authorId);
        StoryHighlight hl = StoryHighlight.builder()
            .author(author).title(req.title()).displayOrder(req.displayOrder()).build();
        hl.audit(AuditAction.CREATE, "Highlight created");
        return toHighlightResponse(highlightRepository.save(hl));
    }

    @Override
    public StoryHighlightResponse updateHighlight(UUID highlightId, UpdateHighlightRequest req, UUID authorId) {
        StoryHighlight hl = findHighlightOrThrow(highlightId, authorId);
        if (req.title() != null)        hl.setTitle(req.title());
        if (req.displayOrder() != null) hl.setDisplayOrder(req.displayOrder());
        hl.audit(AuditAction.UPDATE, "Highlight updated");
        return toHighlightResponse(highlightRepository.save(hl));
    }

    @Override
    public StoryHighlightResponse addStoryToHighlight(UUID highlightId, UUID storyId, UUID authorId) {
        StoryHighlight hl    = findHighlightOrThrow(highlightId, authorId);
        Story          story = storyRepository.findById(storyId)
            .orElseThrow(() -> new ResourceNotFoundException("Story", "id", storyId));
        if (!story.getAuthor().getId().equals(authorId))
            throw new ForbiddenException("Story does not belong to you.", "NOT_AUTHOR");
        story.setHighlight(hl);
        storyRepository.save(story);
        return toHighlightResponse(hl);
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
        StoryHighlight hl = findHighlightOrThrow(highlightId, authorId);
        hl.getStories().forEach(s -> s.setHighlight(null));
        highlightRepository.delete(hl);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoryHighlightResponse> getHighlights(UUID authorId) {
        return highlightRepository.findByAuthorId(authorId).stream()
            .map(this::toHighlightResponse).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StoryResponse> getHighlightStories(UUID highlightId, Pageable pageable) {
        return storyRepository.findByHighlightId(highlightId, pageable)
            .map(s -> toResponse(s, null, false));
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
                viewer != null ? viewer.getUsername()     : null,
                viewer != null ? viewer.getProfileImage() : null,
                v.getViewerRelationship(),
                v.getWatchDurationMs(),
                v.getReactionEmoji(),
                v.isReplied(),
                v.getViewedAt()
            );
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  TRAY FAN-OUT
    // ══════════════════════════════════════════════════════════════════════════

    private void fanOutTrayUpdate(Story story, User author) {
        if (story.getVisibility() == StoryVisibility.ONLY_ME) return;

        StoryTrayEvent event = StoryTrayEvent.builder()
            .eventType(StoryTrayEventType.NEW_STORY)
            .authorId(author.getId())
            .authorUsername(author.getUsername())
            .authorFullName(author.getFullName())
            .authorAvatarUrl(author.getProfileImage())
            .storyId(story.getId())
            .storyType(story.getStoryType())
            .visibility(story.getVisibility())
            .thumbnailUrl(story.getThumbnailUrl() != null ? story.getThumbnailUrl() : story.getMediaUrl())
            .backgroundValue(story.getBackgroundValue())
            .expiresAt(story.getExpiresAt())
            .build();

        afterCommit(() -> {
            List<UUID> recipients = resolveRecipients(author.getId(), story.getVisibility());
            recipients.forEach(viewerId -> trayPublisher.publish(viewerId, event));
            log.debug("[TRAY-FANOUT] story={} → {} recipient(s)", story.getId(), recipients.size());
        });
    }

    private void fanOutTrayRemovalAsync(Story story, User author) {
        StoryTrayEvent event = StoryTrayEvent.builder()
            .eventType(StoryTrayEventType.STORY_REMOVED)
            .authorId(author.getId()).authorUsername(author.getUsername())
            .storyId(story.getId()).build();
        List<UUID> recipients = resolveRecipients(author.getId(), story.getVisibility());
        recipients.forEach(viewerId -> trayPublisher.publish(viewerId, event));
    }

    private List<UUID> resolveRecipients(UUID authorId, StoryVisibility visibility) {
        return switch (visibility) {
            case PUBLIC, FOLLOWERS_ONLY ->
                followRepository.findAllFollowerIds(authorId, PageRequest.of(0, FANOUT_LIMIT));
            case CLOSE_FRIENDS ->
                new ArrayList<>(closeFriendsRepository.findFriendIds(authorId));
            case ONLY_ME -> List.of();
        };
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  VISIBILITY CHECK (correct — no N+1)
    // ══════════════════════════════════════════════════════════════════════════

    private boolean isVisibleTo(Story story, UUID viewerId, UUID authorId,
                                 Set<UUID> viewerFollowedIds,
                                 Set<UUID> authorCloseFriends) {
        if (viewerId != null && viewerId.equals(authorId)) return true;
        return switch (story.getVisibility()) {
            case PUBLIC         -> true;
            case FOLLOWERS_ONLY -> viewerId != null && viewerFollowedIds.contains(authorId);
            case CLOSE_FRIENDS  -> viewerId != null && authorCloseFriends.contains(viewerId);
            case ONLY_ME        -> false;
        };
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  RELATIONSHIP RESOLUTION
    // ══════════════════════════════════════════════════════════════════════════

    private ViewerRelationship resolveRelationship(UUID viewerId, UUID authorId) {
        if (viewerId.equals(authorId)) return ViewerRelationship.AUTHOR;
        if (closeFriendsRepository.existsByIdOwnerIdAndIdFriendId(authorId, viewerId))
            return ViewerRelationship.CLOSE_FRIEND;
        if (followRepository.isFollowing(viewerId, authorId))
            return ViewerRelationship.FOLLOWER;
        return ViewerRelationship.PUBLIC;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  MAPPING
    // ══════════════════════════════════════════════════════════════════════════

    private StoryResponse toResponse(Story s, UUID viewerId, boolean isOwner) {
        return toResponseWithSeenFlag(s, viewerId, Set.of(), isOwner);
    }

    private StoryResponse toResponseWithSeenFlag(Story s, UUID viewerId,
                                                  Set<UUID> seenIds, boolean isOwner) {
        PostSound ps = s.getSound();
        StoryResponse.SoundBrief soundBrief = ps == null ? null :
            new StoryResponse.SoundBrief(
                ps.getSound().getId(), ps.getSound().getTitle(),
                ps.getSound().getArtistName(), ps.getSound().getAudioUrl(),
                ps.getSound().getCoverArtUrl(), ps.getClipStartSeconds(), ps.getVolume());

        String myEmoji = null;
        if (viewerId != null) {
            myEmoji = viewRepository.findByIdStoryIdAndIdViewerId(s.getId(), viewerId)
                .map(StoryView::getReactionEmoji).orElse(null);
        }

        StoryResponse.ViewBreakdown breakdown = (isOwner && viewerId != null
            && viewerId.equals(s.getAuthor().getId()))
            ? buildBreakdown(s.getId()) : null;

        User author = s.getAuthor();
        return new StoryResponse(
            s.getId(),
            new StoryResponse.StoryAuthor(
                author.getId(), author.getUsername(), author.getFullName(),
                author.getProfileImage(), author.getRole().name(),
                author.getAccountType().name()),
            s.getStoryType(), s.getVisibility(),
            s.getTextContent(), s.getBackgroundType(), s.getBackgroundValue(),
            s.getMediaUrl(), s.getThumbnailUrl(), s.getDurationSeconds(),
            s.getLinkedContentId(), s.getLinkedContentSnapshot(), s.getOverlaysJson(),
            soundBrief, s.getExpiresAt(), s.isExpired(),
            s.getViewCount(), breakdown,
            s.getReactionCount(), s.getReplyCount(),
            myEmoji, seenIds.contains(s.getId()),
            s.getCreatedAt()
        );
    }

    private StoryResponse.ViewBreakdown buildBreakdown(UUID storyId) {
        Map<ViewerRelationship, Long> counts = new EnumMap<>(ViewerRelationship.class);
        viewRepository.countGroupedByRelationship(storyId).forEach(row ->
            counts.put((ViewerRelationship) row[0], (Long) row[1]));
        long cf = counts.getOrDefault(ViewerRelationship.CLOSE_FRIEND, 0L);
        long fl = counts.getOrDefault(ViewerRelationship.FOLLOWER,     0L);
        long pu = counts.getOrDefault(ViewerRelationship.PUBLIC,       0L);
        long au = counts.getOrDefault(ViewerRelationship.AUTHOR,       0L);
        return new StoryResponse.ViewBreakdown(cf + fl + pu + au, cf, fl, pu, au);
    }

    private StoryHighlightResponse toHighlightResponse(StoryHighlight h) {
        List<StoryResponse> stories = h.getStories().stream()
            .map(s -> toResponse(s, null, false)).collect(Collectors.toList());
        return new StoryHighlightResponse(
            h.getId(), h.getAuthor().getId(), h.getTitle(), h.getCoverUrl(),
            h.getDisplayOrder(), stories.size(), stories, h.getCreatedAt());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private void attachSound(Story story, UUID soundId, int clipStart, float volume) {
        Sound sound = soundRepository.findById(soundId)
            .orElseThrow(() -> new ResourceNotFoundException("Sound", "id", soundId));
        postSoundRepository.save(PostSound.builder()
            .story(story).sound(sound).clipStartSeconds(clipStart).volume(volume).build());
        soundRepository.incrementUseCount(soundId);
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
            throw new ForbiddenException("Only the highlight author can modify this.", "NOT_AUTHOR");
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

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override public void afterCommit() { action.run(); }
                });
        } else {
            action.run();
        }
    }
}

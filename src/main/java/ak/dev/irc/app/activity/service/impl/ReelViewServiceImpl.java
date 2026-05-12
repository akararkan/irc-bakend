package ak.dev.irc.app.activity.service.impl;

import ak.dev.irc.app.activity.dto.ReelViewResponse;
import ak.dev.irc.app.activity.entity.ReelView;
import ak.dev.irc.app.activity.mapper.ReelViewMapper;
import ak.dev.irc.app.activity.repository.ReelViewRepository;
import ak.dev.irc.app.activity.service.ReelViewService;
import ak.dev.irc.app.activity.service.UserActivityService;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.post.entity.Post;
import ak.dev.irc.app.post.enums.PostType;
import ak.dev.irc.app.post.realtime.PostRealtimeBroadcaster;
import ak.dev.irc.app.post.realtime.PostRealtimeEvent;
import ak.dev.irc.app.post.realtime.PostRealtimeEventType;
import ak.dev.irc.app.post.realtime.PostViewTracker;
import ak.dev.irc.app.post.repository.PostRepository;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReelViewServiceImpl implements ReelViewService {

    private final ReelViewRepository reelViewRepo;
    private final UserRepository userRepo;
    private final PostRepository postRepo;
    private final ReelViewMapper mapper;
    private final UserActivityService userActivityService;
    private final PostRealtimeBroadcaster postRealtime;
    private final PostViewTracker viewTracker;

    // Self-reference for proxy-mediated calls (so REQUIRES_NEW takes effect on internal calls).
    @Autowired @Lazy
    private ReelViewService self;

    @Override
    @Transactional
    public ReelViewResponse recordWatch(UUID userId, UUID postId, Integer watchedSeconds) {
        User user = userRepo.findActiveById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        Post post = postRepo.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post", "id", postId));

        if (post.getPostType() != PostType.REEL) {
            throw new BadRequestException("Post is not a reel", "NOT_A_REEL");
        }

        ReelView view = ReelView.builder()
                .user(user)
                .post(post)
                .watchedSeconds(watchedSeconds)
                .build();
        ReelView saved = reelViewRepo.save(view);

        // Watch-history row is always saved (per-watch analytics), but the
        // displayed view counter on the underlying post is deduped + broadcast
        // through the self proxy so REQUIRES_NEW gives us a fresh L1 cache —
        // otherwise the post entity already loaded above would shadow the
        // increment and the broadcast would carry a stale count.
        self.recordPostView(post.getId(), userId);

        userActivityService.recordReelWatch(userId, postId, watchedSeconds);

        return mapper.toResponse(saved);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordPostView(UUID postId, UUID viewerId) {
        try {
            // Refresh-spam from the same viewer inside the dedupe window is ignored.
            if (!viewTracker.shouldCount(postId, viewerId.toString())) return;

            postRepo.incrementViewCount(postId);

            // Fresh read in this REQUIRES_NEW tx — no stale L1 cache from the caller.
            Long freshCount = postRepo.findById(postId).map(Post::getViewCount).orElse(null);
            if (freshCount != null) {
                postRealtime.broadcast(PostRealtimeEvent.builder()
                        .eventType(PostRealtimeEventType.VIEW_COUNT_UPDATED)
                        .postId(postId)
                        .actorId(viewerId)
                        .postViewCount(freshCount)
                        .build());
            }
        } catch (Exception e) {
            // View counts are best-effort — never let a counter failure break a watch.
            log.warn("Failed to bump view count for reel {}: {}", postId, e.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ReelViewResponse> listMyWatched(UUID userId, Pageable pageable) {
        return reelViewRepo.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(mapper::toResponse);
    }

    @Override
    @Transactional
    public void deleteOne(UUID userId, UUID reelViewId) {
        ReelView view = reelViewRepo.findById(reelViewId)
                .orElseThrow(() -> new ResourceNotFoundException("ReelView", "id", reelViewId));
        if (!view.getUser().getId().equals(userId)) {
            throw new ForbiddenException("You cannot delete another user's watch history");
        }
        reelViewRepo.delete(view);
    }

    @Override
    @Transactional
    public int deleteAll(UUID userId) {
        return reelViewRepo.deleteAllByUserId(userId);
    }
}

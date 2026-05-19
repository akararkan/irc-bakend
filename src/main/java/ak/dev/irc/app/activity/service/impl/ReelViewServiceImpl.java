package ak.dev.irc.app.activity.service.impl;

import ak.dev.irc.app.activity.cassandra.entity.ReelViewEntity;
import ak.dev.irc.app.activity.cassandra.repository.ReelViewCassandraRepository;
import ak.dev.irc.app.activity.dto.ReelViewResponse;
import ak.dev.irc.app.activity.mapper.ReelViewMapper;
import ak.dev.irc.app.activity.service.ReelViewService;
import ak.dev.irc.app.activity.service.UserActivityService;
import ak.dev.irc.app.post.cassandra.service.CassandraViewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reel-watch history — Cassandra-backed.
 *
 * Per-watch rows live in {@code reel_views_by_user}; the underlying post's
 * unique-viewer counter still bumps through {@link CassandraViewService},
 * which is idempotent via Redis NX dedupe.
 *
 * The {@link ReelViewService} contract is unchanged.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReelViewServiceImpl implements ReelViewService {

    private final ReelViewCassandraRepository reelRepo;
    private final ReelViewMapper              mapper;
    private final UserActivityService         userActivityService;
    private final CassandraViewService        viewService;

    @Override
    public ReelViewResponse recordWatch(UUID userId, UUID postId, Integer watchedSeconds) {
        ReelViewEntity row = ReelViewEntity.builder()
                .userId(userId).createdAt(Instant.now())
                .reelViewId(UUID.randomUUID())
                .postId(postId).watchedSeconds(watchedSeconds)
                .build();
        reelRepo.save(row);

        // Bump the post's unique-viewer counter (idempotent per (post,user)
        // within the dedupe window), then log the watch in activity history.
        viewService.recordView(postId, userId);
        userActivityService.recordReelWatch(userId, postId, watchedSeconds);

        return mapper.toResponse(row);
    }

    @Override
    public void recordPostView(UUID postId, UUID viewerId) {
        viewService.recordView(postId, viewerId);
    }

    @Override
    public Page<ReelViewResponse> listMyWatched(UUID userId, Pageable pageable) {
        List<ReelViewResponse> content = reelRepo.firstPage(userId, pageable.getPageSize())
                .stream().map(mapper::toResponse).toList();
        return new PageImpl<>(content, pageable, content.size());
    }

    @Override
    public void deleteOne(UUID userId, UUID reelViewId) {
        // We don't store a separate lookup table for reel views — scan the
        // user's recent slice and remove the matching row. Reel-views are
        // typically short windows so this is cheap.
        for (ReelViewEntity row : reelRepo.firstPage(userId, 500)) {
            if (reelViewId.equals(row.getReelViewId())) {
                reelRepo.delete(row.getUserId(), row.getCreatedAt(), reelViewId);
                return;
            }
        }
        log.debug("[REEL] delete {} miss (not in recent 500) — silently ignored", reelViewId);
    }

    @Override
    public int deleteAll(UUID userId) {
        final int BATCH = 200;
        int deleted = 0;
        while (true) {
            List<ReelViewEntity> page = reelRepo.firstPage(userId, BATCH);
            if (page.isEmpty()) break;
            for (ReelViewEntity row : page) {
                try {
                    reelRepo.delete(row.getUserId(), row.getCreatedAt(), row.getReelViewId());
                    deleted++;
                } catch (Exception ignored) { /* best-effort */ }
            }
            if (page.size() < BATCH) break;
        }
        return deleted;
    }
}

package ak.dev.irc.app.post.cassandra.service;

import ak.dev.irc.app.post.cassandra.entity.SaveByUserEntity;
import ak.dev.irc.app.post.cassandra.entity.SaveLookupEntity;
import ak.dev.irc.app.post.cassandra.repository.PostCounterRepository;
import ak.dev.irc.app.post.cassandra.repository.SaveByUserRepository;
import ak.dev.irc.app.post.cassandra.repository.SaveLookupRepository;
import ak.dev.irc.app.post.realtime.PostRealtimeEvent;
import ak.dev.irc.app.post.realtime.PostRealtimeEventType;
import ak.dev.irc.app.post.realtime.PostRealtimePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Toggle-save (bookmark) for posts, backed by Cassandra.
 *
 * Write fan-out per save:
 *   • saves_by_user        ← user's bookmark list, newest first
 *   • saves_by_post_user   ← point-lookup "did U save P?" + remembers createdAt
 *   • post_counters++      ← save_count
 *
 * Why two write tables: "is this saved?" on the post-detail page is a hot
 * point read — querying saves_by_user for a needle in N saves would do a
 * partition scan. The lookup table is a single read.
 *
 * Unsave needs to find the matching saves_by_user row, but its cluster key
 * includes created_at. The lookup table duplicates created_at so unsave is
 * still a single read + two deletes.
 *
 * Collections (user-named folders): stored as collection_name on both rows.
 * Updating which collection a save belongs to: unsave + save with the new
 * name. Cheap enough not to warrant a separate update path.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CassandraSaveService {

    private final SaveByUserRepository  savesByUserRepo;
    private final SaveLookupRepository  saveLookupRepo;
    private final PostCounterRepository postCounterRepo;
    private final CounterService        counterService;
    private final PostRealtimePublisher realtimePublisher;

    /**
     * Toggle a user's save on a post.
     *
     * @param collectionName optional folder name; null = the default "All" bucket
     * @return true if the post is saved AFTER the call, false if it was unsaved
     */
    public boolean toggleSave(UUID postId, UUID userId, String collectionName) {
        Optional<SaveLookupEntity> existing = saveLookupRepo.find(postId, userId);

        if (existing.isPresent()) {
            SaveLookupEntity prior = existing.get();
            savesByUserRepo.delete(userId, prior.getCreatedAt(), postId);
            saveLookupRepo.delete(postId, userId);
            counterService.decrementPostSaves(postId);
            broadcast(postId, userId);
            return false;
        }

        Instant now = Instant.now();
        savesByUserRepo.save(SaveByUserEntity.builder()
                .userId(userId).createdAt(now).postId(postId)
                .collectionName(collectionName)
                .build());
        saveLookupRepo.save(SaveLookupEntity.builder()
                .postId(postId).userId(userId)
                .createdAt(now).collectionName(collectionName)
                .build());
        counterService.incrementPostSaves(postId);
        broadcast(postId, userId);
        return true;
    }

    public boolean isSaved(UUID postId, UUID userId) {
        return saveLookupRepo.find(postId, userId).isPresent();
    }

    public List<SaveByUserEntity> savesForUser(UUID userId, int pageSize) {
        return savesByUserRepo.firstPage(userId, pageSize);
    }

    public List<SaveByUserEntity> savesForUserAfter(UUID userId, Instant cursor, int pageSize) {
        return savesByUserRepo.nextPage(userId, cursor, pageSize);
    }

    // ── realtime ─────────────────────────────────────────────────────────────

    private void broadcast(UUID postId, UUID actorId) {
        try {
            Long latest = postCounterRepo.findByPostId(postId)
                    .map(c -> c.getSaveCount()).orElse(null);
            realtimePublisher.publish(postId, PostRealtimeEvent.builder()
                    .eventType(PostRealtimeEventType.SAVE_COUNT_UPDATED)
                    .postId(postId)
                    .actorId(actorId)
                    .postSaveCount(latest)
                    .build());
        } catch (Exception e) {
            log.debug("[SAVE] realtime broadcast skipped: {}", e.getMessage());
        }
    }
}

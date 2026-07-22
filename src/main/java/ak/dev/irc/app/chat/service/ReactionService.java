package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.cassandra.entity.ReactionByMessageEntity;
import ak.dev.irc.app.chat.cassandra.repository.ReactionByMessageRepository;
import ak.dev.irc.app.chat.dto.response.ReactionSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Message reactions. One Cassandra row per (message, user) is the source of
 * truth; a Redis hash {@code chat:reactions:{messageId}} (emoji → count) is the
 * hot read for rendering the timeline without scanning Cassandra per message.
 * The hash is delta-maintained on every react/unreact and lazily rebuilt from
 * Cassandra when cold.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReactionService {

    private static final String HASH_PREFIX = "chat:reactions:";

    private final ReactionByMessageRepository reactionRepo;
    private final StringRedisTemplate redis;

    /** @return {@code true} if the reaction state actually changed. */
    public boolean react(long messageId, UUID userId, String emoji) {
        ReactionByMessageEntity prior = reactionRepo.findOne(messageId, userId);
        if (prior != null && emoji.equals(prior.getEmoji())) {
            return false; // idempotent — same reaction already present
        }
        reactionRepo.save(ReactionByMessageEntity.builder()
                .messageId(messageId).userId(userId)
                .emoji(emoji).createdAt(Instant.now())
                .build());
        if (prior != null && !emoji.equals(prior.getEmoji())) {
            adjust(messageId, prior.getEmoji(), -1);
        }
        adjust(messageId, emoji, +1);
        return true;
    }

    /** @return the removed emoji, or {@code null} if the user had no reaction. */
    public String unreact(long messageId, UUID userId) {
        ReactionByMessageEntity prior = reactionRepo.findOne(messageId, userId);
        if (prior == null) return null;
        reactionRepo.deleteOne(messageId, userId);
        adjust(messageId, prior.getEmoji(), -1);
        return prior.getEmoji();
    }

    /** Full detail for one message, including whether the viewer reacted. */
    public List<ReactionSummary> detailFor(long messageId, UUID viewerId) {
        List<ReactionByMessageEntity> rows = reactionRepo.findByMessage(messageId);
        Map<String, Long> counts = new LinkedHashMap<>();
        Set<String> mine = new HashSet<>();
        for (ReactionByMessageEntity r : rows) {
            counts.merge(r.getEmoji(), 1L, Long::sum);
            if (r.getUserId().equals(viewerId)) mine.add(r.getEmoji());
        }
        List<ReactionSummary> out = new ArrayList<>(counts.size());
        counts.forEach((emoji, count) -> out.add(new ReactionSummary(emoji, count, mine.contains(emoji))));
        return out;
    }

    /**
     * Batch reaction counts for a page of messages (timeline hydration). Reads
     * the Redis count hashes only — bounded by the page size — and does not set
     * {@code reactedByMe} (the client tracks its own reactions optimistically;
     * exact per-viewer state is available via {@link #detailFor}).
     */
    public Map<Long, List<ReactionSummary>> countsFor(Collection<Long> messageIds) {
        Map<Long, List<ReactionSummary>> result = new HashMap<>();
        for (Long id : messageIds) {
            try {
                Map<Object, Object> hash = redis.opsForHash().entries(HASH_PREFIX + id);
                if (hash == null || hash.isEmpty()) continue;
                List<ReactionSummary> list = new ArrayList<>(hash.size());
                for (Map.Entry<Object, Object> e : hash.entrySet()) {
                    long count = parse(e.getValue());
                    if (count > 0) list.add(new ReactionSummary(String.valueOf(e.getKey()), count, false));
                }
                if (!list.isEmpty()) result.put(id, list);
            } catch (Exception ignored) { /* cold/unavailable cache → no reactions rendered */ }
        }
        return result;
    }

    public void clear(long messageId) {
        try {
            reactionRepo.deleteAllForMessage(messageId);
            redis.delete(HASH_PREFIX + messageId);
        } catch (Exception e) {
            log.debug("[REACTION] clear failed for {}: {}", messageId, e.getMessage());
        }
    }

    private void adjust(long messageId, String emoji, long delta) {
        try {
            Long v = redis.opsForHash().increment(HASH_PREFIX + messageId, emoji, delta);
            if (v != null && v <= 0) redis.opsForHash().delete(HASH_PREFIX + messageId, emoji);
        } catch (Exception e) {
            log.debug("[REACTION] redis adjust failed: {}", e.getMessage());
        }
    }

    private static long parse(Object v) {
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return 0L; }
    }
}

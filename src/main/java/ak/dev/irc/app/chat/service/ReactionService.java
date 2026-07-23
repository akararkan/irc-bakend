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
    /** Field marking a hash rebuilt from Cassandra that found no reactions, so a
     *  reaction-less message is a warm cache hit instead of a Cassandra re-scan. */
    private static final String EMPTY_SENTINEL = "~";
    private static final java.time.Duration EMPTY_TTL = java.time.Duration.ofHours(6);

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
        if (messageIds == null || messageIds.isEmpty()) return result;
        // One pipelined round-trip for the whole page instead of an HGETALL per message.
        List<Long> ids = List.copyOf(messageIds);
        List<Object> hashes = null;
        try {
            hashes = redis.executePipelined(
                    (org.springframework.data.redis.core.RedisCallback<Object>) conn -> {
                        for (Long id : ids) {
                            conn.hGetAll((HASH_PREFIX + id)
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        }
                        return null;
                    });
        } catch (Exception ignored) { /* Redis down → rebuild from Cassandra below */ }
        for (int i = 0; i < ids.size(); i++) {
            long id = ids.get(i);
            Object o = hashes == null || i >= hashes.size() ? null : hashes.get(i);
            List<ReactionSummary> list = (o instanceof Map<?, ?> hash && !hash.isEmpty())
                    ? summarize(hash)
                    : rebuild(id); // cold (flushed/never built) → self-heal from Cassandra
            if (!list.isEmpty()) result.put(id, list);
        }
        return result;
    }

    private static List<ReactionSummary> summarize(Map<?, ?> hash) {
        List<ReactionSummary> list = new ArrayList<>(hash.size());
        for (Map.Entry<?, ?> e : hash.entrySet()) {
            String emoji = String.valueOf(e.getKey());
            if (EMPTY_SENTINEL.equals(emoji)) continue;
            long count = parse(e.getValue());
            if (count > 0) list.add(new ReactionSummary(emoji, count, false));
        }
        return list;
    }

    /**
     * Rebuild a cold count hash from the Cassandra source of truth so counts
     * survive a Redis flush (the documented self-heal). A message with no
     * reactions is negative-cached under a sentinel field with a TTL, so the
     * common reaction-less case stays a warm hash hit rather than a Cassandra
     * read on every page render.
     */
    private List<ReactionSummary> rebuild(long messageId) {
        List<ReactionByMessageEntity> rows;
        try {
            rows = reactionRepo.findByMessage(messageId);
        } catch (Exception e) {
            return List.of(); // Cassandra unavailable → render no reactions
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ReactionByMessageEntity r : rows) counts.merge(r.getEmoji(), 1L, Long::sum);
        String key = HASH_PREFIX + messageId;
        try {
            if (counts.isEmpty()) {
                redis.opsForHash().put(key, EMPTY_SENTINEL, "0");
                redis.expire(key, EMPTY_TTL);
            } else {
                Map<String, String> m = new HashMap<>(counts.size());
                counts.forEach((emoji, c) -> m.put(emoji, Long.toString(c)));
                redis.opsForHash().putAll(key, m);
                redis.persist(key); // real counts are delta-maintained, never expiring
            }
        } catch (Exception ignored) { /* best-effort cache refill */ }
        List<ReactionSummary> out = new ArrayList<>(counts.size());
        counts.forEach((emoji, count) -> out.add(new ReactionSummary(emoji, count, false)));
        return out;
    }

    /**
     * Like {@link #countsFor(Collection)} but also sets {@code reactedByMe} for the
     * viewer, via one bulk Cassandra read of the viewer's own reaction rows. Used by
     * the starred list, which promises a fully-hydrated {@code reactedByMe}.
     */
    public Map<Long, List<ReactionSummary>> countsFor(Collection<Long> messageIds, UUID viewerId) {
        Map<Long, List<ReactionSummary>> base = countsFor(messageIds);
        if (viewerId == null || base.isEmpty()) return base;
        Map<Long, String> mine = new HashMap<>();
        try {
            for (ReactionByMessageEntity r : reactionRepo.findByMessageIdInAndUserId(messageIds, viewerId)) {
                mine.put(r.getMessageId(), r.getEmoji());   // ≤1 reaction per (message, user)
            }
        } catch (Exception e) {
            log.debug("[REACTION] viewer reaction load failed: {}", e.getMessage());
            return base;
        }
        Map<Long, List<ReactionSummary>> out = new HashMap<>(base.size());
        base.forEach((id, list) -> {
            String myEmoji = mine.get(id);
            out.put(id, myEmoji == null ? list : list.stream()
                    .map(s -> new ReactionSummary(s.emoji(), s.count(), myEmoji.equals(s.emoji())))
                    .toList());
        });
        return out;
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
            // A real count must outlive any negative-cache TTL left by rebuild().
            if (delta > 0) redis.persist(HASH_PREFIX + messageId);
        } catch (Exception e) {
            log.debug("[REACTION] redis adjust failed: {}", e.getMessage());
        }
    }

    private static long parse(Object v) {
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return 0L; }
    }
}

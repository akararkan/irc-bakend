package ak.dev.irc.app.chat.service;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Per-post view/forward metrics for channel posts (Telegram's eye counter).
 *
 * <p>Durable counts live in the Cassandra counter table {@code message_counters};
 * uniqueness of views is enforced with a Redis HyperLogLog per post ({@code
 * PFADD} returns 1 only for a first-seen viewer), so a user scrolling past the
 * same post twice never double-counts. Channel-level running totals and the
 * most-viewed ZSET are Redis conveniences for the stats endpoint — best-effort,
 * the per-post counters remain the source of truth.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelPostMetricsService {

    private static final String VIEWERS_PREFIX = "chat:viewers:";     // HLL per post
    private static final String TOTALS_PREFIX  = "chat:chtotals:";    // hash per channel
    private static final String TOP_PREFIX     = "chat:chtop:";       // ZSET per channel
    private static final String TYPES_PREFIX   = "chat:chposttypes:"; // hash per channel

    private final CqlSession session;
    private final StringRedisTemplate redis;

    @Value("${spring.cassandra.keyspace-name:irc_keyspace}")
    private String keyspace;

    private PreparedStatement bumpViews;
    private PreparedStatement bumpForwards;
    private PreparedStatement bumpComments;
    private PreparedStatement selectIn;
    private PreparedStatement deleteRow;

    /** A post's durable counters. */
    public record PostMetrics(long views, long forwards, long comments) {}

    @PostConstruct
    void prepare() {
        try {
            bumpViews = session.prepare(
                    "UPDATE " + keyspace + ".message_counters SET views = views + 1 WHERE message_id = ?");
            bumpForwards = session.prepare(
                    "UPDATE " + keyspace + ".message_counters SET forwards = forwards + 1 WHERE message_id = ?");
            bumpComments = session.prepare(
                    "UPDATE " + keyspace + ".message_counters SET comments = comments + ? WHERE message_id = ?");
            selectIn = session.prepare(
                    "SELECT message_id, views, forwards, comments FROM " + keyspace
                    + ".message_counters WHERE message_id IN ?");
            deleteRow = session.prepare(
                    "DELETE FROM " + keyspace + ".message_counters WHERE message_id = ?");
        } catch (Exception e) {
            log.warn("[CHANNEL-METRICS] statement prepare skipped (no Cassandra?): {}", e.getMessage());
        }
    }

    /** A discussion-group comment was added (+1) / deleted (−1) on a channel post. */
    public void onCommentDelta(long postMessageId, long delta) {
        if (bumpComments == null) return;
        try {
            session.execute(bumpComments.bind(delta, postMessageId));
        } catch (Exception e) {
            log.debug("[CHANNEL-METRICS] comment bump failed for {}: {}", postMessageId, e.getMessage());
        }
    }

    /**
     * Record that {@code viewerId} saw these channel posts. Returns the message
     * ids that were counted as NEW views (first sight by this viewer).
     */
    public List<Long> markViewed(UUID channelId, UUID viewerId, Collection<Long> messageIds) {
        List<Long> counted = new ArrayList<>();
        if (messageIds == null || messageIds.isEmpty() || bumpViews == null) return counted;
        for (Long id : messageIds) {
            if (id == null) continue;
            boolean firstSight;
            try {
                Long added = redis.opsForHyperLogLog().add(VIEWERS_PREFIX + id, viewerId.toString());
                firstSight = added != null && added > 0;
            } catch (Exception e) {
                firstSight = true; // Redis down → count best-effort rather than lose views
            }
            if (!firstSight) continue;
            try {
                session.execute(bumpViews.bind(id));
                counted.add(id);
            } catch (Exception e) {
                log.debug("[CHANNEL-METRICS] view bump failed for {}: {}", id, e.getMessage());
                continue;
            }
            try {
                redis.opsForHash().increment(TOTALS_PREFIX + channelId, "views", 1);
                redis.opsForZSet().incrementScore(TOP_PREFIX + channelId, Long.toString(id), 1);
            } catch (Exception ignored) { /* best-effort aggregates */ }
        }
        return counted;
    }

    /** A post of {@code channelId} was forwarded somewhere. */
    public void onForwarded(UUID channelId, long messageId) {
        if (bumpForwards == null) return;
        try {
            session.execute(bumpForwards.bind(messageId));
        } catch (Exception e) {
            log.debug("[CHANNEL-METRICS] forward bump failed for {}: {}", messageId, e.getMessage());
            return;
        }
        try {
            redis.opsForHash().increment(TOTALS_PREFIX + channelId, "forwards", 1);
        } catch (Exception ignored) { /* best-effort */ }
    }

    /** Bulk counter read for a page of messages — ids with no counter row are absent. */
    public Map<Long, PostMetrics> metricsFor(Collection<Long> messageIds) {
        Map<Long, PostMetrics> out = new HashMap<>();
        if (messageIds == null || messageIds.isEmpty() || selectIn == null) return out;
        try {
            ResultSet rs = session.execute(selectIn.bind(List.copyOf(messageIds)));
            for (Row row : rs) {
                out.put(row.getLong("message_id"),
                        new PostMetrics(row.getLong("views"), row.getLong("forwards"),
                                        row.getLong("comments")));
            }
        } catch (Exception e) {
            log.debug("[CHANNEL-METRICS] bulk read failed: {}", e.getMessage());
        }
        return out;
    }

    /** Channel running totals (views/forwards) from the best-effort Redis hash. */
    public Map<String, Long> totals(UUID channelId) {
        try {
            Map<Object, Object> h = redis.opsForHash().entries(TOTALS_PREFIX + channelId);
            Map<String, Long> out = new HashMap<>();
            h.forEach((k, v) -> out.put(String.valueOf(k), parse(v)));
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** The channel's most-viewed post ids, most viewed first. */
    public List<Long> topPostIds(UUID channelId, int limit) {
        try {
            Set<String> top = redis.opsForZSet().reverseRange(TOP_PREFIX + channelId, 0, limit - 1L);
            if (top == null) return List.of();
            return top.stream().map(Long::valueOf).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Best-effort live post count per message type (the stats breakdown). */
    public void postTypeAdjust(UUID channelId, String type, long delta) {
        try {
            redis.opsForHash().increment(TYPES_PREFIX + channelId, type, delta);
        } catch (Exception ignored) { /* best-effort */ }
    }

    public Map<String, Long> postsByType(UUID channelId) {
        try {
            Map<Object, Object> h = redis.opsForHash().entries(TYPES_PREFIX + channelId);
            Map<String, Long> out = new HashMap<>();
            h.forEach((k, v) -> {
                long c = parse(v);
                if (c > 0) out.put(String.valueOf(k), c);
            });
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Drop a deleted post's metrics (counter row, viewer HLL, top-posts entry). */
    public void clear(UUID channelId, long messageId) {
        try { if (deleteRow != null) session.execute(deleteRow.bind(messageId)); } catch (Exception ignored) { }
        try {
            redis.delete(VIEWERS_PREFIX + messageId);
            if (channelId != null) redis.opsForZSet().remove(TOP_PREFIX + channelId, Long.toString(messageId));
        } catch (Exception ignored) { /* best-effort */ }
    }

    private static long parse(Object v) {
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return 0L; }
    }
}

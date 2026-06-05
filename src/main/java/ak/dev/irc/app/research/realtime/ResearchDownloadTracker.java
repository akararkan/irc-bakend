package ak.dev.irc.app.research.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Redis-backed dedupe gate for research downloads — mirrors
 * {@link ResearchViewTracker} but keyed at the file granularity, not the
 * research granularity, because the user reports an action like "I
 * downloaded the PDF" not "I downloaded the research".
 *
 * <p><b>Why this exists.</b> Without dedupe, every {@code POST /download}
 * incremented the counter, so a front-end that fires the call twice
 * (React StrictMode dev double-effect, browser pre-flight quirks, or a
 * user double-clicking the button) bumped the counter by 2, 4, 6 on
 * every interaction. With this gate, the second-and-subsequent fires
 * within the dedupe window short-circuit before they reach the
 * {@code RabbitMQ → counter increment} path.</p>
 *
 * <p><b>Dedupe semantics</b>:
 * <ul>
 *   <li><b>Authenticated user</b> — keyed by
 *       {@code (researchId, mediaId, userId)} for {@value #AUTHED_DEDUPE_DAYS}
 *       days. Same user re-downloading the same file inside that window
 *       does not bump the counter. Months later: counts again
 *       (legitimate revisit).</li>
 *   <li><b>Anonymous</b> — keyed by {@code (researchId, mediaId, ip)} for
 *       1 hour. Aggressive enough to swallow double-clicks, short enough
 *       that genuine repeat downloads from the same NAT eventually
 *       count.</li>
 *   <li><b>Redis down</b> — fail-open (return true). Counter inflation
 *       is preferable to dropping the download itself; an underclaimed
 *       counter is a worse user-trust outcome than a slightly-inflated
 *       one during a Redis outage.</li>
 * </ul></p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResearchDownloadTracker {

    /** Permanent-ish dedupe horizon for authenticated downloads. */
    public static final int AUTHED_DEDUPE_DAYS = 90;
    /** Burst-window dedupe for anonymous downloads. */
    public static final Duration ANON_DEDUPE_WINDOW = Duration.ofHours(1);

    private static final String AUTHED_KEY_PREFIX = "irc:rdownload:dedupe:u:";
    private static final String ANON_KEY_PREFIX   = "irc:rdownload:dedupe:a:";

    private final StringRedisTemplate redis;

    /**
     * Returns {@code true} if this download should bump the counter,
     * {@code false} if it's a duplicate inside the dedupe window.
     *
     * @param researchId  required
     * @param mediaId     required — counters are per-physical-file
     * @param userId      null for anonymous downloads
     * @param ipAddress   used only when {@code userId} is null
     */
    public boolean shouldCount(UUID researchId, UUID mediaId, UUID userId, String ipAddress) {
        if (researchId == null || mediaId == null) {
            // Caller bug — but be defensive. Count nothing for a malformed
            // request rather than letting it through to RabbitMQ.
            return false;
        }
        final String key;
        final Duration ttl;
        if (userId != null) {
            key = AUTHED_KEY_PREFIX + researchId + ":" + mediaId + ":" + userId;
            ttl = Duration.ofDays(AUTHED_DEDUPE_DAYS);
        } else {
            String ip = (ipAddress == null || ipAddress.isBlank()) ? "anon" : ipAddress;
            key = ANON_KEY_PREFIX + researchId + ":" + mediaId + ":" + ip;
            ttl = ANON_DEDUPE_WINDOW;
        }

        try {
            Boolean fresh = redis.opsForValue().setIfAbsent(key, "1", ttl);
            return Boolean.TRUE.equals(fresh);
        } catch (Exception ex) {
            // Fail-open: better to inflate the counter than silently drop
            // the user's download analytics. The legitimate-double-click
            // bug only matters when Redis is healthy anyway.
            log.debug("[RDOWNLOAD-TRACKER] Redis unavailable, counting without dedupe: {}",
                    ex.getMessage());
            return true;
        }
    }
}

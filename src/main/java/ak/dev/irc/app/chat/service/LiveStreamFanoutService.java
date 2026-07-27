package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.dto.response.LiveStreamResponse;
import ak.dev.irc.app.chat.realtime.ChatRealtimeBroadcaster;
import ak.dev.irc.app.chat.realtime.ChatRealtimeEvent;
import ak.dev.irc.app.chat.realtime.ChatRealtimeEventType;
import ak.dev.irc.app.common.notification.NotificationKind;
import ak.dev.irc.app.post.cassandra.service.CassandraNotificationService;
import ak.dev.irc.app.user.repository.UserFollowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

/**
 * "{@code host} is live" fan-out. When a user goes live
 * ({@link LiveStreamService#start}) every follower gets, exactly like
 * TikTok / Instagram / Facebook:
 * <ul>
 *   <li>a realtime {@code stream.started} SSE event carrying the PUBLIC stream
 *       view (so their Live directory prepends the card with no extra fetch), and</li>
 *   <li>a persisted inbox notification (bell + optional email-gated) via the
 *       shared {@link CassandraNotificationService} delivery engine.</li>
 * </ul>
 *
 * <p>Mirrors {@code FeedTimelineService.fanoutAsync} (the POST_NEW fan-out):
 * a keyset follower scan in {@value #FANOUT_BATCH}-row pages, capped at
 * {@value #MAX_FANOUT_FOLLOWERS}, running on the shared async pool so the
 * go-live HTTP request returns immediately. It is a SEPARATE bean from
 * {@link LiveStreamService} so the {@code @Async} proxy actually applies
 * (a self-invocation would run inline).</p>
 *
 * <p><b>Security:</b> the caller MUST pass the public stream view (no
 * {@code whipUrl}/{@code ingestUrl}) — those carry the secret stream key and
 * must never reach a follower.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveStreamFanoutService {

    private static final int MAX_FANOUT_FOLLOWERS = 50_000;
    private static final int FANOUT_BATCH         = 500;

    private final UserFollowRepository         userFollowRepo;
    private final CassandraNotificationService notificationService;
    private final ChatRealtimeBroadcaster      broadcaster;

    /**
     * @param publicStream the PUBLIC {@code LiveStreamResponse} (built with
     *                     {@code includeIngest=false}) — never the host view.
     * @param hostId       the streamer; also the follower-scan root and the
     *                     notification actor (self is suppressed downstream).
     * @param hostLabel    pre-resolved display label, e.g. {@code "@alice"} —
     *                     resolved once by the caller, not per follower.
     */
    @Async
    public void fanoutStreamStarted(LiveStreamResponse publicStream, UUID hostId, String hostLabel) {
        if (publicStream == null || hostId == null) return;
        final UUID   streamId = publicStream.id();
        final String title    = hostLabel + " is live";
        final String body     = StringUtils.hasText(publicStream.title())
                ? publicStream.title() : "Tap to watch the stream.";
        final String groupKey = "STREAM_STARTED:" + streamId;   // one row per go-live

        final ChatRealtimeEvent event = ChatRealtimeEvent.builder()
                .eventType(ChatRealtimeEventType.STREAM_STARTED)
                .stream(publicStream)
                .build();

        UUID cursor = null;
        int  total  = 0;
        while (total < MAX_FANOUT_FOLLOWERS) {
            List<UUID> batch;
            try {
                batch = userFollowRepo.findFollowerIdsAfter(hostId, cursor, PageRequest.of(0, FANOUT_BATCH));
            } catch (Exception e) {
                log.warn("[LIVE] follower lookup failed for host {}: {}", hostId, e.getMessage());
                return;
            }
            if (batch.isEmpty()) break;

            // Realtime: one broadcast to the whole page. The broadcaster fans out
            // per recipient onto their irc:chat:{userId} channel; the payload is
            // self-contained so no client needs to re-fetch the stream.
            try {
                broadcaster.broadcast(batch, event);
            } catch (Exception e) {
                log.debug("[LIVE] realtime stream.started batch failed: {}", e.getMessage());
            }

            // Persisted inbox notification per follower. deliverAsync is itself
            // @Async (runs on the shared taskExecutor) and internally suppresses
            // self + blocked pairs, so no extra filtering or wrapping is needed.
            for (UUID followerId : batch) {
                try {
                    notificationService.deliverAsync(new CassandraNotificationService.DeliverRequest(
                            followerId, NotificationKind.STREAM_STARTED,
                            title, body, hostId, "LiveStream", streamId, groupKey));
                } catch (Exception e) {
                    log.debug("[LIVE] notify follower {} skipped: {}", followerId, e.getMessage());
                }
            }

            total += batch.size();
            cursor = batch.get(batch.size() - 1);      // keyset advance
            if (batch.size() < FANOUT_BATCH) break;
        }
        log.info("[LIVE] stream.started fan-out complete for {}: {} followers notified", streamId, total);
    }

    /**
     * "{@code host}'s stream ended" realtime fan-out to the host's followers — the
     * mirror image of {@link #fanoutStreamStarted}, so a follower's "following is
     * live" rail can DROP the card the moment the stream ends (the started event
     * added it; without this, the rail only ever grows and a tapped card opens a
     * dead room).
     *
     * <p><b>Realtime only — no inbox notification</b> (a "went offline" bell would
     * be spam). Same keyset follower scan, capped at {@value #MAX_FANOUT_FOLLOWERS}.
     * {@code stream.viewer} is deliberately NOT fanned to followers: it fires on
     * every join/leave, so following it would be a fan-out storm per stream — the
     * rail's viewer counts stay approximate between refreshes by design.</p>
     *
     * @param publicStream the PUBLIC ended view (status {@code ENDED}); the rail
     *                     matches and removes the card by {@code id}.
     */
    @Async
    public void fanoutStreamEnded(LiveStreamResponse publicStream, UUID hostId) {
        if (publicStream == null || hostId == null) return;
        final ChatRealtimeEvent event = ChatRealtimeEvent.builder()
                .eventType(ChatRealtimeEventType.STREAM_ENDED)
                .stream(publicStream)
                .build();

        UUID cursor = null;
        int  total  = 0;
        while (total < MAX_FANOUT_FOLLOWERS) {
            List<UUID> batch;
            try {
                batch = userFollowRepo.findFollowerIdsAfter(hostId, cursor, PageRequest.of(0, FANOUT_BATCH));
            } catch (Exception e) {
                log.warn("[LIVE] follower lookup failed for host {} (ended fan-out): {}", hostId, e.getMessage());
                return;
            }
            if (batch.isEmpty()) break;
            try {
                broadcaster.broadcast(batch, event);
            } catch (Exception e) {
                log.debug("[LIVE] realtime stream.ended batch failed: {}", e.getMessage());
            }
            total += batch.size();
            cursor = batch.get(batch.size() - 1);
            if (batch.size() < FANOUT_BATCH) break;
        }
        log.info("[LIVE] stream.ended fan-out complete for {}: {} followers reached", publicStream.id(), total);
    }
}

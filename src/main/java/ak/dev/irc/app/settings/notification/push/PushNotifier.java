package ak.dev.irc.app.settings.notification.push;

import ak.dev.irc.app.common.notification.NotificationDeepLinks;
import ak.dev.irc.app.common.notification.NotificationKind;
import ak.dev.irc.app.settings.notification.entity.PushToken;
import ak.dev.irc.app.settings.notification.enums.NotificationChannel;
import ak.dev.irc.app.settings.notification.repository.PushTokenRepository;
import ak.dev.irc.app.settings.notification.repository.UserDndRepository;
import ak.dev.irc.app.settings.notification.service.DndEvaluator;
import ak.dev.irc.app.settings.notification.service.NotificationPrefResolver;
import ak.dev.irc.app.settings.notification.service.PushTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The delivery-pipeline seam for mobile push (spec §8): per recipient, check
 * {@link NotificationPrefResolver#isEnabled} on the PUSH channel → unless the
 * kind bypasses, drop inside {@link DndEvaluator#inQuietHours} → fan out one
 * message to every registered token → delete the tokens the provider reported
 * as no longer registered (token hygiene).
 *
 * <p>Block/mute/self-suppression have already happened upstream in
 * {@code CassandraNotificationService} — this bean only ever sees events that
 * earned an inbox row. The payload carries the same {@code href} grammar the
 * inbox rows use ({@link NotificationDeepLinks}), so the client routes a push
 * tap and an inbox tap through one mapping.</p>
 *
 * <p>Android channel ids are a contract with the mobile client, which creates
 * the channels (and picks per-channel sounds) on its side: {@code default_v2}
 * for general notifications, {@code messages_v2} for chat, {@code calls_v1}
 * for incoming calls.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushNotifier {

    public static final String CHANNEL_DEFAULT  = "default_v2";
    public static final String CHANNEL_MESSAGES = "messages_v2";
    public static final String CHANNEL_CALLS    = "calls_v1";

    private final PushSender               pushSender;
    private final PushTokenRepository      tokenRepo;
    private final PushTokenService         tokenService;
    private final NotificationPrefResolver prefResolver;
    private final DndEvaluator             dndEvaluator;
    private final UserDndRepository        dndRepo;

    /**
     * Pipeline entry — one push per notification event, gated by the
     * preference matrix and the DND window (spec §8: quiet hours drop the
     * push; the inbox row and SSE frame were already delivered). Async so the
     * provider round-trip never blocks the originating write.
     *
     * @param unreadCount the recipient's unread badge count when the caller
     *                    already knows it, else null (never invented here)
     */
    @Async
    public void deliver(UUID userId, NotificationKind kind, String title, String body,
                        String resourceType, UUID resourceId, Long unreadCount) {
        try {
            String eventType = kind.name();
            if (!prefResolver.isEnabled(userId, eventType, NotificationChannel.PUSH)) {
                log.info("[PUSH] {} → user {} skipped: PUSH disabled by preference", eventType, userId);
                return;
            }
            if (!prefResolver.bypassesDnd(eventType)
                    && dndEvaluator.inQuietHours(dndRepo.findById(userId).orElse(null), Instant.now())) {
                log.info("[PUSH] {} → user {} skipped: quiet hours (DND)", eventType, userId);
                return;
            }
            Map<String, String> data = new HashMap<>();
            data.put("type", eventType);
            if (resourceType != null) data.put("resourceType", resourceType);
            if (resourceId != null)   data.put("resourceId", resourceId.toString());
            String href = NotificationDeepLinks.deepLinkOf(resourceType, resourceId);
            if (href != null) data.put("href", href);

            dispatch(userId, title, body, data, channelFor(kind),
                    unreadCount == null ? null : unreadCount.intValue(), false);
        } catch (Exception e) {
            log.debug("[PUSH] deliver {} → user {} skipped: {}", kind, userId, e.getMessage());
        }
    }

    /**
     * Direct push that skips the matrix and DND — used for incoming-call
     * rings, which have no {@link NotificationKind} row and are ephemeral: a
     * ring the callee sleeps through simply times out to MISSED (and the
     * missed-call bell honours DND like everything else). The spec gates only
     * pipeline notifications, so this is a deliberate carve-out.
     *
     * @param dataOnly see {@link PushSender#send} — the call-ring push passes
     *                 {@code true} so a killed Android app's background task
     *                 gets a chance to build the full-screen ringing UI
     *                 instead of the OS auto-rendering a plain heads-up
     *                 notification. {@code title}/{@code body} must already be
     *                 present in {@code data} when {@code dataOnly} is
     *                 {@code true} — the client has nothing else to read them
     *                 from.
     */
    @Async
    public void deliverDirectAsync(UUID userId, String title, String body,
                                   Map<String, String> data, String channelId, boolean dataOnly) {
        try {
            dispatch(userId, title, body, data, channelId, null, dataOnly);
        } catch (Exception e) {
            log.debug("[PUSH] direct deliver → user {} skipped: {}", userId, e.getMessage());
        }
    }

    // ── internals ──────────────────────────────────────────────────────────

    private void dispatch(UUID userId, String title, String body,
                          Map<String, String> data, String channelId, Integer badge, boolean dataOnly) {
        List<PushToken> tokens = tokenRepo.findByUserId(userId);
        if (tokens.isEmpty()) return;
        List<PushToken> dead = pushSender.sendAll(tokens, title, body, data, channelId, badge, dataOnly);
        for (PushToken t : dead) {
            try {
                tokenService.deleteByToken(t.getToken());
                log.info("[PUSH] pruned unregistered token for user {} ({})",
                        userId, t.getPlatform());
            } catch (Exception e) {
                log.debug("[PUSH] token prune failed: {}", e.getMessage());
            }
        }
    }

    /** Kind → Android channel: chat/message kinds ring the messages channel,
     *  the missed-call bell the calls channel, everything else default. */
    private static String channelFor(NotificationKind kind) {
        return switch (kind) {
            case NEW_MESSAGE, MESSAGE_REQUEST, MESSAGE_MENTION, CHANNEL_NEW_POST -> CHANNEL_MESSAGES;
            case CALL_MISSED -> CHANNEL_CALLS;
            default -> CHANNEL_DEFAULT;
        };
    }
}

package ak.dev.irc.app.settings.notification.push;

import ak.dev.irc.app.settings.notification.entity.PushToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Abstraction over a mobile-push provider (Expo / FCM / APNs), spec §8. The
 * {@link NoOpPushSender} stand-in lets the whole notification pipeline —
 * preference matrix, DND, token hygiene, fan-out — run end-to-end without
 * provider credentials; the real path is {@link PushSenderRouter}, which
 * routes each token by its provider column to {@link ExpoPushSender} or
 * {@link FcmPushSender} ({@code app.push.provider=expo}, the default).
 */
public interface PushSender {

    /**
     * Deliver one push. Returns {@code false} ONLY when the provider reported
     * the token is no longer registered, so the caller can delete it (token
     * hygiene). Transient failures — timeouts, 5xx, network errors — must
     * return {@code true}: a flaky network is never a reason to prune.
     *
     * @param token     the registered device token row
     * @param title     notification title
     * @param body      notification body
     * @param data      opaque payload delivered to the app on tap; carries the
     *                  same {@code href} grammar the inbox rows use so one
     *                  client-side mapping routes both surfaces (may be null)
     * @param channelId Android notification channel ({@code default_v2},
     *                  {@code messages_v2}, {@code calls_v1}); the client
     *                  creates the channels and picks sounds per channel
     * @param badge     the recipient's unread count for the app icon, or null
     *                  when the pipeline doesn't know it
     * @param dataOnly  when {@code true}, send NO title/body block at all —
     *                  {@code title}/{@code body} still travel inside
     *                  {@code data} so the client can render its own UI. A
     *                  killed Android app only gets a chance to run JS for a
     *                  push when the FCM message carries no top-level
     *                  {@code notification} block; otherwise the OS renders
     *                  the tray entry itself and no client code — including a
     *                  full-screen incoming-call UI — ever runs. Reserved for
     *                  pushes the client must build custom UI for (today:
     *                  {@code CALL_INCOMING} only) — everything else keeps a
     *                  real notification block so it still shows on a killed
     *                  app even with no background task registered for it.
     */
    boolean send(PushToken token, String title, String body,
                 Map<String, String> data, String channelId, Integer badge, boolean dataOnly);

    /**
     * Deliver the same message to every token of one recipient. Returns the
     * tokens the provider reported as no longer registered — the caller
     * deletes those rows. Implementations that speak a batch protocol (Expo
     * takes up to 100 messages per request) override this; the default just
     * loops {@link #send}.
     */
    default List<PushToken> sendAll(List<PushToken> tokens, String title, String body,
                                    Map<String, String> data, String channelId, Integer badge,
                                    boolean dataOnly) {
        List<PushToken> dead = new ArrayList<>();
        for (PushToken t : tokens) {
            if (!send(t, title, body, data, channelId, badge, dataOnly)) dead.add(t);
        }
        return dead;
    }
}

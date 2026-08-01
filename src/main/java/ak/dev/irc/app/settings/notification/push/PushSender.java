package ak.dev.irc.app.settings.notification.push;

import ak.dev.irc.app.settings.notification.entity.PushToken;

/**
 * Abstraction over a mobile-push provider (FCM / APNs), spec §8. The default
 * {@link NoOpPushSender} lets the whole notification pipeline — preference
 * matrix, DND, token hygiene, fan-out — run end-to-end without provider
 * credentials; a real {@code FcmPushSender} drops in later behind
 * {@code @ConditionalOnProperty}.
 */
public interface PushSender {

    /**
     * Deliver a push. Returns {@code false} if the provider reported the token
     * is no longer registered, so the caller can delete it (token hygiene).
     */
    boolean send(PushToken token, String title, String body);
}

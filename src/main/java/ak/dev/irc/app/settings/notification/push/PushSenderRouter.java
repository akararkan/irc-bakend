package ak.dev.irc.app.settings.notification.push;

import ak.dev.irc.app.settings.notification.entity.PushToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The {@link PushSender} the pipeline actually injects: routes each token to
 * the sender that owns its provider — {@code FCM} rows (native device tokens)
 * to {@link FcmPushSender}, {@code EXPO} rows (and legacy
 * {@code ExponentPushToken[...]} rows) to {@link ExpoPushSender}. A provider
 * neither sender claims is logged and treated as delivered — an unknown
 * provider is never a reason to prune.
 *
 * <p>{@code @Primary} keeps {@link PushNotifier}'s one-{@code PushSender}
 * injection unambiguous. Present (with both real senders) for every value of
 * {@code app.push.provider} except {@code noop}, which selects
 * {@link NoOpPushSender} alone — the yaml default {@code expo} therefore means
 * "router with Expo + FCM".</p>
 */
@Slf4j
@Component
@Primary
@RequiredArgsConstructor
@ConditionalOnExpression(PushSenderRouter.NOT_NOOP)
public class PushSenderRouter implements PushSender {

    /** SpEL guard shared by the router and both real senders: everything but
     *  {@code app.push.provider=noop}. */
    public static final String NOT_NOOP = "!'noop'.equals('${app.push.provider:expo}')";

    private final ExpoPushSender expoPushSender;
    private final FcmPushSender  fcmPushSender;

    @Override
    public boolean send(PushToken token, String title, String body,
                        Map<String, String> data, String channelId, Integer badge, boolean dataOnly) {
        PushSender delegate = delegateFor(token);
        if (delegate == null) {
            log.warn("[PUSH] no sender for provider {} ({}) — treating as delivered",
                    token.getProvider(), token.getPlatform());
            return true;
        }
        return delegate.send(token, title, body, data, channelId, badge, dataOnly);
    }

    @Override
    public List<PushToken> sendAll(List<PushToken> tokens, String title, String body,
                                   Map<String, String> data, String channelId, Integer badge,
                                   boolean dataOnly) {
        List<PushToken> expoTokens = new ArrayList<>();
        List<PushToken> fcmTokens  = new ArrayList<>();
        for (PushToken t : tokens) {
            PushSender delegate = delegateFor(t);
            if (delegate == fcmPushSender)       fcmTokens.add(t);
            else if (delegate == expoPushSender) expoTokens.add(t);
            else log.warn("[PUSH] no sender for provider {} ({}) — treating as delivered",
                    t.getProvider(), t.getPlatform());
        }
        List<PushToken> dead = new ArrayList<>();
        if (!expoTokens.isEmpty()) {
            dead.addAll(expoPushSender.sendAll(expoTokens, title, body, data, channelId, badge, dataOnly));
        }
        if (!fcmTokens.isEmpty()) {
            dead.addAll(fcmPushSender.sendAll(fcmTokens, title, body, data, channelId, badge, dataOnly));
        }
        return dead;
    }

    /** Provider → sender; {@code null} when neither sender owns the row. The
     *  {@code ExponentPushToken} fallback keeps rows registered before the
     *  provider column existed on the Expo path. */
    private PushSender delegateFor(PushToken t) {
        if ("FCM".equalsIgnoreCase(t.getProvider())) return fcmPushSender;
        if ("EXPO".equalsIgnoreCase(t.getProvider())
                || (t.getToken() != null && t.getToken().startsWith("ExponentPushToken"))) {
            return expoPushSender;
        }
        return null;
    }
}

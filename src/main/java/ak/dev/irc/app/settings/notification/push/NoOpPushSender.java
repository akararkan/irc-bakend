package ak.dev.irc.app.settings.notification.push;

import ak.dev.irc.app.settings.notification.entity.PushToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * No-op push sender (spec Part 7). Logs the intended delivery so the pipeline
 * is observable in dev without provider credentials. Selected with
 * {@code app.push.provider=noop} — the only value that suppresses
 * {@link PushSenderRouter} and the real senders it fronts; any other value
 * (including the absent-property default, {@code expo}) selects the router.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.push.provider", havingValue = "noop")
public class NoOpPushSender implements PushSender {

    @Override
    public boolean send(PushToken token, String title, String body,
                        Map<String, String> data, String channelId, Integer badge, boolean dataOnly) {
        log.debug("[PUSH-NOOP] would push to {}/{} on channel {} : {} — {} (data {}, dataOnly {})",
                token.getProvider(), token.getPlatform(), channelId, title, body, data, dataOnly);
        return true; // treat as delivered; no provider means no UNREGISTERED signal
    }
}

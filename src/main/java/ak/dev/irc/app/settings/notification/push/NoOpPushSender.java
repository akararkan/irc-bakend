package ak.dev.irc.app.settings.notification.push;

import ak.dev.irc.app.settings.notification.entity.PushToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Default no-op push sender (spec Part 7). Logs the intended delivery so the
 * pipeline is observable in dev without Firebase/Apple credentials. A real
 * provider bean marked {@code @Primary}/{@code @ConditionalOnProperty} replaces it.
 */
@Slf4j
@Component
public class NoOpPushSender implements PushSender {

    @Override
    public boolean send(PushToken token, String title, String body) {
        log.debug("[PUSH-NOOP] would push to {}/{} : {} — {}",
                token.getProvider(), token.getPlatform(), title, body);
        return true; // treat as delivered; no provider means no UNREGISTERED signal
    }
}

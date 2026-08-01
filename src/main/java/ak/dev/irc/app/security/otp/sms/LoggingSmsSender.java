package ak.dev.irc.app.security.otp.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Default {@link SmsSender} — logs the message instead of sending it (spec §20
 * Part 7 pragmatic stand-in). This is the {@code @Primary} bean so the OTP flow
 * works end-to-end in dev/CI: the code appears in the application log, the
 * verify path is fully exercised. A real gateway impl ({@code TwilioSmsSender},
 * a local Iraqi provider) annotated {@code @Primary} / {@code @ConditionalOnProperty}
 * drops in without touching callers.
 */
@Slf4j
@Primary
@Component
@ConditionalOnMissingBean(name = "realSmsSender")
public class LoggingSmsSender implements SmsSender {

    @Override
    public boolean send(String e164Phone, String message) {
        log.info("[SMS-DEV] to={} body=\"{}\" (LoggingSmsSender — no real SMS sent; "
                + "configure a real gateway in production)", e164Phone, message);
        return true;
    }
}

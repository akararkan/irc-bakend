package ak.dev.irc.app.config;

import ak.dev.irc.app.security.SecurityUtils;
import lombok.NonNull;
import org.springframework.data.domain.AuditorAware;

import java.util.Optional;
import java.util.UUID;

public class AuditorAwareImpl implements AuditorAware<UUID> {

    @Override
    @NonNull
    public Optional<UUID> getCurrentAuditor() {
        return SecurityUtils.getCurrentUserId();
    }
}

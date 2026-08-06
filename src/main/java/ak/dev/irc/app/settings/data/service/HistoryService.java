package ak.dev.irc.app.settings.data.service;

import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.messages.SettingsMessages;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

/**
 * History deletion (spec §16). Search/watch history are personalisation signals;
 * deleting them should also invalidate the recommendation cache so suggestions
 * stop reflecting the removed history immediately.
 *
 * <p>SEAM: this platform has no dedicated search/watch-history tables yet, so
 * this is a validated no-op today. When those stores land, wire the scoped
 * DELETE + Redis personalisation-cache eviction here.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryService {

    private static final Set<String> TYPES = Set.of("search", "watch");

    public void deleteHistory(UUID userId, String type) {
        String t = type == null ? "" : type.trim().toLowerCase();
        if (!TYPES.contains(t)) {
            throw new BadRequestException(SettingsMessages.HISTORY_TYPE_UNKNOWN_MSG.formatted(type));
        }
        log.info("[HISTORY] delete {} history for user {} (no-op until history stores exist)", t, userId);
    }
}

package ak.dev.irc.app.settings.notification.service;

import ak.dev.irc.app.settings.notification.entity.UserNotificationPref;
import ak.dev.irc.app.settings.notification.enums.NotificationChannel;
import ak.dev.irc.app.settings.notification.repository.UserNotificationPrefRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves whether a notification (eventType, channel) should be delivered to a
 * user (spec §8). An absent preference row falls back to
 * {@link NotificationDefaults}. Security/login alerts bypass all preferences.
 *
 * <p>Integration seam: the delivery path
 * ({@code CassandraNotificationService}) calls {@link #isEnabled} before fanning
 * out to a channel, replacing its hardcoded email switch. This resolver is
 * standalone so it can be wired without disturbing the existing hot path; a
 * short Redis cache of the per-user matrix is the documented next step.</p>
 */
@Service
@RequiredArgsConstructor
public class NotificationPrefResolver {

    private final UserNotificationPrefRepository repo;

    @Transactional(readOnly = true)
    public boolean isEnabled(UUID userId, String eventType, NotificationChannel channel) {
        if (NotificationDefaults.bypasses(eventType)) return true;
        return repo.findByUserIdAndEventTypeAndChannel(userId, eventType, channel)
                .map(UserNotificationPref::isEnabled)
                .orElseGet(() -> NotificationDefaults.defaultEnabled(eventType, channel));
    }

    /** Security/login alerts must reach the user regardless of the DND window. */
    public boolean bypassesDnd(String eventType) {
        return NotificationDefaults.bypasses(eventType);
    }

    /** The full resolved matrix for the settings UI (defaults merged). */
    @Transactional(readOnly = true)
    public Map<String, Map<String, Boolean>> resolvedMatrix(UUID userId, java.util.Collection<String> eventTypes) {
        Map<String, UserNotificationPref> stored = new HashMap<>();
        for (UserNotificationPref p : repo.findByUserId(userId)) {
            stored.put(p.getEventType() + "|" + p.getChannel().name(), p);
        }
        Map<String, Map<String, Boolean>> out = new java.util.LinkedHashMap<>();
        for (String ev : eventTypes) {
            Map<String, Boolean> byChannel = new java.util.LinkedHashMap<>();
            for (NotificationChannel ch : NotificationChannel.values()) {
                UserNotificationPref p = stored.get(ev + "|" + ch.name());
                boolean enabled = p != null ? p.isEnabled()
                        : NotificationDefaults.defaultEnabled(ev, ch);
                byChannel.put(ch.name(), enabled);
            }
            out.put(ev, byChannel);
        }
        return out;
    }
}

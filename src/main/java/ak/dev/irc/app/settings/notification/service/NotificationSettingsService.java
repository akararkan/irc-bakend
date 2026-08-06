package ak.dev.irc.app.settings.notification.service;

import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.messages.SettingsMessages;
import ak.dev.irc.app.settings.notification.dto.NotificationSettingsDtos.UpdateDndRequest;
import ak.dev.irc.app.settings.notification.entity.UserDnd;
import ak.dev.irc.app.settings.notification.entity.UserNotificationPref;
import ak.dev.irc.app.settings.notification.enums.NotificationChannel;
import ak.dev.irc.app.settings.notification.repository.UserDndRepository;
import ak.dev.irc.app.settings.notification.repository.UserNotificationPrefRepository;
import ak.dev.irc.app.user.enums.NotificationType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read/write side of the notification preference matrix and DND window (spec §8).
 * The resolved matrix and the pure evaluation live in
 * {@link NotificationPrefResolver} / {@link DndEvaluator}; this service handles
 * validation and persistence for the settings endpoints.
 */
@Service
@RequiredArgsConstructor
public class NotificationSettingsService {

    private final UserNotificationPrefRepository prefRepo;
    private final UserDndRepository dndRepo;
    private final NotificationPrefResolver resolver;

    private static final List<String> ALL_EVENT_TYPES =
            Arrays.stream(NotificationType.values()).map(Enum::name).toList();

    // ── Matrix ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Map<String, Boolean>> matrix(UUID userId) {
        return resolver.resolvedMatrix(userId, ALL_EVENT_TYPES);
    }

    @Transactional
    public void setPref(UUID userId, String eventType, String channel, boolean enabled) {
        String ev = validateEventType(eventType);
        NotificationChannel ch = validateChannel(channel);
        UserNotificationPref pref = prefRepo
                .findByUserIdAndEventTypeAndChannel(userId, ev, ch)
                .orElseGet(() -> UserNotificationPref.builder()
                        .userId(userId).eventType(ev).channel(ch).build());
        pref.setEnabled(enabled);
        prefRepo.save(pref);
    }

    // ── DND ─────────────────────────────────────────────────────────────────────

    @Transactional
    public UserDnd getDnd(UUID userId) {
        return dndRepo.findById(userId)
                .orElseGet(() -> UserDnd.builder().userId(userId).timezone("UTC").build());
    }

    @Transactional
    public UserDnd updateDnd(UUID userId, UpdateDndRequest req) {
        UserDnd dnd = getDnd(userId);
        if (req.timezone() != null && !req.timezone().isBlank()) {
            try { ZoneId.of(req.timezone()); }
            catch (Exception ex) { throw new BadRequestException(SettingsMessages.BAD_TIMEZONE_MSG.formatted(req.timezone()), SettingsMessages.BAD_TIMEZONE); }
            dnd.setTimezone(req.timezone());
        }
        if (req.startTime() != null) dnd.setStartTime(parseTime(req.startTime()));
        if (req.endTime() != null)   dnd.setEndTime(parseTime(req.endTime()));
        if (req.daysMask() != null)  dnd.setDaysMask(req.daysMask());
        if (req.muteUntil() != null) dnd.setMuteUntil(req.muteUntil());
        if (req.enabled() != null) {
            dnd.setEnabled(req.enabled());
        } else {
            // Derive: a fully-specified window turns DND on.
            dnd.setEnabled(dnd.getStartTime() != null && dnd.getEndTime() != null);
        }
        return dndRepo.save(dnd);
    }

    // ── validation helpers ──────────────────────────────────────────────────────

    private String validateEventType(String eventType) {
        try {
            return NotificationType.valueOf(eventType.trim().toUpperCase()).name();
        } catch (Exception ex) {
            throw new BadRequestException(SettingsMessages.BAD_EVENT_TYPE_MSG.formatted(eventType), SettingsMessages.BAD_EVENT_TYPE);
        }
    }

    private NotificationChannel validateChannel(String channel) {
        try {
            return NotificationChannel.valueOf(channel.trim().toUpperCase());
        } catch (Exception ex) {
            throw new BadRequestException(SettingsMessages.BAD_CHANNEL_MSG.formatted(channel), SettingsMessages.BAD_CHANNEL);
        }
    }

    private LocalTime parseTime(String hhmm) {
        try {
            return LocalTime.parse(hhmm.trim());
        } catch (Exception ex) {
            throw new BadRequestException(SettingsMessages.BAD_TIME_MSG.formatted(hhmm), SettingsMessages.BAD_TIME);
        }
    }
}

package ak.dev.irc.app.settings.notification.dto;

import ak.dev.irc.app.settings.notification.entity.PushToken;
import ak.dev.irc.app.settings.notification.entity.UserDnd;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;
import java.util.UUID;

/** Notification-settings request/response DTOs (spec §8). */
public final class NotificationSettingsDtos {

    private NotificationSettingsDtos() {}

    public record SetPrefRequest(boolean enabled) {}

    public record DndResponse(boolean enabled, String timezone, String startTime,
                              String endTime, int daysMask, LocalDateTime muteUntil) {
        public static DndResponse of(UserDnd d) {
            return new DndResponse(
                    d.isEnabled(), d.getTimezone(),
                    d.getStartTime() == null ? null : d.getStartTime().toString(),
                    d.getEndTime() == null ? null : d.getEndTime().toString(),
                    d.getDaysMask(), d.getMuteUntil());
        }
    }

    public record UpdateDndRequest(Boolean enabled, String timezone, String startTime,
                                   String endTime, Integer daysMask, LocalDateTime muteUntil) {}

    public record RegisterPushTokenRequest(@NotBlank String provider, @NotBlank String token,
                                           String platform, UUID sid) {}

    public record PushTokenResponse(UUID id, String provider, String platform, LocalDateTime lastSeenAt) {
        public static PushTokenResponse of(PushToken t) {
            return new PushTokenResponse(t.getId(), t.getProvider(), t.getPlatform(), t.getLastSeenAt());
        }
    }
}

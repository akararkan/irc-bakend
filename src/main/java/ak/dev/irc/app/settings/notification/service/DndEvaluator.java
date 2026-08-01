package ak.dev.irc.app.settings.notification.service;

import ak.dev.irc.app.settings.notification.entity.UserDnd;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Do-Not-Disturb evaluator (spec §8). Pure: given a {@link UserDnd} and an
 * instant, decides whether the user is in quiet hours. Two rules the spec calls
 * out as easy to get wrong:
 *
 * <ol>
 *   <li><b>User's own timezone.</b> The window is evaluated in the stored IANA
 *       zone (e.g. {@code Asia/Baghdad}), not a UTC offset — offsets break on
 *       DST changes.</li>
 *   <li><b>Windows that cross midnight</b> (22:00 → 07:00) are handled
 *       explicitly; the naive {@code start <= now < end} comparison silently
 *       disables DND for everyone who sets one.</li>
 * </ol>
 *
 * <p>Security and login alerts must ignore DND — that decision belongs to the
 * caller (the resolver's {@code bypassesDnd}); this evaluator is unconditional.</p>
 */
@Service
public class DndEvaluator {

    /** True when {@code now} falls inside the user's quiet hours / hard mute. */
    public boolean inQuietHours(UserDnd dnd, Instant now) {
        if (dnd == null || now == null) return false;
        ZoneId zone = safeZone(dnd.getTimezone());
        ZonedDateTime znow = now.atZone(zone);

        // Hard mute-until override — independent of the scheduled window.
        if (dnd.getMuteUntil() != null && znow.toLocalDateTime().isBefore(dnd.getMuteUntil())) {
            return true;
        }
        if (!dnd.isEnabled() || dnd.getStartTime() == null || dnd.getEndTime() == null) {
            return false;
        }
        // Day-of-week mask (bit 0 = Monday … bit 6 = Sunday); 0 = every day.
        if (dnd.getDaysMask() != 0) {
            int bit = 1 << (znow.getDayOfWeek().getValue() - 1);
            if ((dnd.getDaysMask() & bit) == 0) return false;
        }
        LocalTime t = znow.toLocalTime();
        LocalTime start = dnd.getStartTime();
        LocalTime end = dnd.getEndTime();
        if (!start.isAfter(end)) {
            // Same-day window (start <= end).
            return !t.isBefore(start) && t.isBefore(end);
        }
        // Cross-midnight window (e.g. 22:00 → 07:00).
        return !t.isBefore(start) || t.isBefore(end);
    }

    public boolean shouldDefer(UserDnd dnd, Instant now) {
        return inQuietHours(dnd, now);
    }

    private ZoneId safeZone(String tz) {
        try {
            return (tz == null || tz.isBlank()) ? ZoneId.of("UTC") : ZoneId.of(tz);
        } catch (Exception ex) {
            return ZoneId.of("UTC");
        }
    }
}

package ak.dev.irc.app.common.util;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

public final class TimeDisplayUtil {

    private TimeDisplayUtil() {}

    private static final DateTimeFormatter FULL_FORMAT =
            DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a");

    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a");

    private static final DateTimeFormatter TIME_ONLY =
            DateTimeFormatter.ofPattern("h:mm a");

    private static final DateTimeFormatter DATE_ONLY =
            DateTimeFormatter.ofPattern("MMMM d, yyyy");

    /**
     * Returns a human-readable relative time string like Facebook/Instagram.
     * Examples: "Just now", "5m", "2h", "Yesterday at 3:45 PM", "March 15 at 10:30 AM"
     */
    public static String timeAgo(LocalDateTime dateTime) {
        if (dateTime == null) return null;

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        long seconds = ChronoUnit.SECONDS.between(dateTime, now);

        if (seconds < 0) return "Just now";
        if (seconds < 60) return "Just now";
        if (seconds < 3600) return (seconds / 60) + "m";
        if (seconds < 86400) return (seconds / 3600) + "h";

        long days = ChronoUnit.DAYS.between(dateTime.toLocalDate(), now.toLocalDate());

        if (days == 1) return "Yesterday at " + dateTime.format(TIME_ONLY);
        if (days < 7) return days + "d";

        // Same year — show "Mar 15 at 10:30 AM"
        if (dateTime.getYear() == now.getYear()) {
            return dateTime.format(DateTimeFormatter.ofPattern("MMM d 'at' h:mm a"));
        }

        // Different year — show "Mar 15, 2024 at 10:30 AM"
        return dateTime.format(DATE_TIME_FORMAT);
    }

    /**
     * Returns a full formatted date string.
     * Example: "Saturday, March 15, 2025 at 3:45 PM"
     */
    public static String formattedDate(LocalDateTime dateTime) {
        if (dateTime == null) return null;
        return dateTime.format(FULL_FORMAT);
    }
}

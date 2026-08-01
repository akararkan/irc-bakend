package ak.dev.irc.app.settings.notification.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Do-Not-Disturb window (spec §8). Evaluated in the <em>user's own IANA
 * timezone</em> (stored on {@code User.timezone}), never a UTC offset — offsets
 * break on DST. Windows that cross midnight (22:00 → 07:00) are handled
 * explicitly by {@code DndEvaluator}.
 */
@Entity
@Table(name = "user_dnd")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDnd {

    @Id
    @Column(name = "user_id", updatable = false, nullable = false)
    private UUID userId;

    /**
     * IANA timezone the window is evaluated in (e.g. {@code Asia/Baghdad}). A
     * zone id, never a UTC offset — offsets break on DST changes. Defaults to
     * UTC; mirrors {@code User.timezone} but is kept here so DND evaluation is
     * self-contained.
     */
    @Column(name = "timezone", nullable = false, length = 40,
            columnDefinition = "varchar(40) not null default 'UTC'")
    @Builder.Default
    private String timezone = "UTC";

    /** Master switch — off means DND never applies (window fields ignored). */
    @Column(name = "enabled", nullable = false,
            columnDefinition = "boolean not null default false")
    @Builder.Default
    private boolean enabled = false;

    /** Local start of the quiet window. */
    @Column(name = "start_time")
    private LocalTime startTime;

    /** Local end of the quiet window. */
    @Column(name = "end_time")
    private LocalTime endTime;

    /**
     * Bitmask of active days (bit {@code 1 << (DayOfWeek.getValue()-1)};
     * Monday = bit 0). {@code 0} means "every day".
     */
    @Column(name = "days_mask", nullable = false,
            columnDefinition = "int not null default 0")
    @Builder.Default
    private int daysMask = 0;

    /** Ad-hoc "mute until" that overrides the window (snooze). */
    @Column(name = "mute_until")
    private LocalDateTime muteUntil;
}

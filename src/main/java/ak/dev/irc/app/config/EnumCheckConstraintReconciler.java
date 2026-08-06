package ak.dev.irc.app.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Drops stale enum {@code CHECK} constraints that {@code ddl-auto: update} refuses
 * to widen.
 *
 * <p><b>The hazard.</b> Hibernate emits a {@code CHECK (col IN ('A','B',…))}
 * constraint for every {@code @Enumerated(STRING)} column when it first creates the
 * table. Add a value to that enum later and {@code ddl-auto: update} does
 * <b>nothing</b> to the existing constraint — so the very first row carrying the new
 * value fails with a constraint violation on any database that was created before
 * the enum grew. With no Flyway/Liquibase in the project this would otherwise be a
 * manual {@code ALTER TABLE … DROP CONSTRAINT} on every environment, forever (a
 * standing trap for every future enum change).</p>
 *
 * <p><b>The fix.</b> On startup (after Hibernate's schema export has run), drop the
 * known-stale constraints. The enum is still fully enforced at the application layer
 * (JPA {@code @Enumerated} on write + Jackson on the wire), so the database-level
 * {@code CHECK} is redundant defence, not the source of truth — dropping it is safe
 * and it makes every <i>future</i> value of that enum work with no further DDL. Each
 * statement is {@code DROP CONSTRAINT IF EXISTS}, so this is idempotent and a no-op
 * on a fresh database or a second boot.</p>
 *
 * <p>To cover a newly-widened enum, add its {@code (table, constraint)} here — the
 * Postgres constraint name is {@code <table>_<column>_check}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order // default lowest precedence — run after any other ApplicationReadyEvent setup
public class EnumCheckConstraintReconciler {

    private final JdbcTemplate jdbc;

    /** Enum {@code CHECK} constraints that have been widened since a table was first
     *  created. {@code recording_status} gained {@code PAUSED} + {@code PROCESSING}
     *  when live-stream recording learned to record in takes. */
    private static final List<String[]> STALE_ENUM_CHECKS = List.<String[]>of(
            new String[]{"live_streams", "live_streams_recording_status_check"},
            // Role enum widened with the staff tiers (MODERATOR/SUPPORT/ANALYST)
            // — the pre-widening CHECK constraint would reject the new values.
            new String[]{"users", "users_role_check"},
            // Announcement Status gained SCHEDULED/CANCELLED with the scheduler.
            new String[]{"platform_announcements", "platform_announcements_status_check"}
    );

    /** table.constraint → outcome of the last startup run (ops report). */
    private final java.util.Map<String, String> lastOutcome =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());
    private volatile java.time.LocalDateTime lastRunAt;

    @EventListener(ApplicationReadyEvent.class)
    public void reconcile() {
        lastRunAt = java.time.LocalDateTime.now();
        for (String[] tc : STALE_ENUM_CHECKS) {
            String table = tc[0], constraint = tc[1];
            try {
                jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS " + constraint);
                log.info("[SCHEMA] reconciled enum check: dropped stale {} on {} "
                        + "(enum is enforced at the app layer)", constraint, table);
                lastOutcome.put(table + "." + constraint, "DROPPED_OR_ABSENT");
            } catch (Exception e) {
                // Never fail startup over this — a missing table/permission just
                // means the manual ALTER is still needed on that environment.
                log.warn("[SCHEMA] could not drop stale constraint {} on {}: {}",
                        constraint, table, e.getMessage());
                lastOutcome.put(table + "." + constraint, "FAILED: " + e.getMessage());
            }
        }
    }

    /** Startup outcome report for the admin ops board. */
    public java.util.Map<String, Object> report() {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("lastRunAt", lastRunAt);
        out.put("constraints", new java.util.LinkedHashMap<>(lastOutcome));
        out.put("note", "Runs once per boot; DROP CONSTRAINT IF EXISTS is idempotent. "
                + "When widening an @Enumerated(STRING) enum on an existing table, add its "
                + "(table, <table>_<column>_check) pair to STALE_ENUM_CHECKS.");
        return out;
    }
}

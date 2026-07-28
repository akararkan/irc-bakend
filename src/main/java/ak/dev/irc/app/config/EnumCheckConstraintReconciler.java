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
            new String[]{"live_streams", "live_streams_recording_status_check"}
    );

    @EventListener(ApplicationReadyEvent.class)
    public void reconcile() {
        for (String[] tc : STALE_ENUM_CHECKS) {
            String table = tc[0], constraint = tc[1];
            try {
                jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS " + constraint);
                log.info("[SCHEMA] reconciled enum check: dropped stale {} on {} "
                        + "(enum is enforced at the app layer)", constraint, table);
            } catch (Exception e) {
                // Never fail startup over this — a missing table/permission just
                // means the manual ALTER is still needed on that environment.
                log.warn("[SCHEMA] could not drop stale constraint {} on {}: {}",
                        constraint, table, e.getMessage());
            }
        }
    }
}

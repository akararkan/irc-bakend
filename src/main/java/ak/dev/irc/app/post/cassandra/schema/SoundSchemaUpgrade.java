package ak.dev.irc.app.post.cassandra.schema;

import com.datastax.oss.driver.api.core.CqlSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Adds the admin-era columns to {@code sounds_by_id} on existing keyspaces —
 * {@code schema-action: create_if_not_exists} creates missing TABLES from the
 * entity mapping but never ALTERs an existing one, so the new
 * {@code official} / {@code trending_excluded} fields need this one-time,
 * idempotent upgrade (an already-present column just logs and moves on).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SoundSchemaUpgrade {

    private final CqlSession session;

    @Value("${spring.cassandra.keyspace-name:irc_keyspace}")
    private String keyspace;

    @EventListener(ApplicationReadyEvent.class)
    public void upgrade() {
        addColumn("sounds_by_id", "official", "boolean");
        addColumn("sounds_by_id", "trending_excluded", "boolean");
    }

    private void addColumn(String table, String column, String type) {
        try {
            session.execute("ALTER TABLE %s.%s ADD %s %s".formatted(keyspace, table, column, type));
            log.info("[SOUND-SCHEMA] added {}.{}", table, column);
        } catch (Exception ex) {
            // "Invalid column name … conflicts with an existing column" = already applied.
            log.debug("[SOUND-SCHEMA] {}.{} not added ({})", table, column, ex.getMessage());
        }
    }
}

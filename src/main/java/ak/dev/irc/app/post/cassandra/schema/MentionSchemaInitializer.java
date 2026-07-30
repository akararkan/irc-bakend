package ak.dev.irc.app.post.cassandra.schema;

import com.datastax.oss.driver.api.core.CqlSession;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Widens {@code mentions_by_user} for the unified mentions-of-me feed.
 *
 * <p>The table predates multi-source mentions, and Spring Data's
 * {@code schema-action: create_if_not_exists} only CREATEs — it never ALTERs
 * an existing table, so the new {@code source_type} / {@code source_parent_id}
 * entity columns would fail on save against a pre-existing deployment. Same
 * idempotent {@code ensureColumn} pattern as {@code ChatCassandraSchemaInitializer}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MentionSchemaInitializer {

    private final CqlSession session;

    @Value("${spring.cassandra.keyspace-name:irc_keyspace}")
    private String keyspace;

    @PostConstruct
    public void widenMentionsTable() {
        ensureColumn("mentions_by_user", "source_type", "text");
        ensureColumn("mentions_by_user", "source_parent_id", "uuid");
    }

    private void ensureColumn(String table, String column, String cqlType) {
        try {
            if (!columnExists(table, column)) {
                session.execute("ALTER TABLE " + keyspace + "." + table
                        + " ADD " + column + " " + cqlType);
                log.info("[MENTION-CASSANDRA] added {}.{} ({})", table, column, cqlType);
            }
        } catch (Exception e) {
            log.warn("[MENTION-CASSANDRA] could not ensure column {}.{}: {}", table, column, e.getMessage());
        }
    }

    private boolean columnExists(String table, String column) {
        return session.execute(
                "SELECT column_name FROM system_schema.columns"
                        + " WHERE keyspace_name = ? AND table_name = ? AND column_name = ?",
                keyspace, table, column).one() != null;
    }
}

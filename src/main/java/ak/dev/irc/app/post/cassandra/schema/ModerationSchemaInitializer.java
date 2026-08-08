package ak.dev.irc.app.post.cassandra.schema;

import com.datastax.oss.driver.api.core.CqlSession;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Adds the {@code moderation_status} column to the Cassandra-backed content
 * tables that had no notion of a held state
 * (docs/moderation/MODERATION_ROADMAP.md §5.1).
 *
 * <p>Posts already carry a {@code status} string that the read path gates on, so
 * they need nothing here — {@code PENDING_REVIEW} and {@code REJECTED} slot
 * straight into the existing {@code isServable} check. Comments, replies and
 * stories had no state at all, hence these columns.</p>
 *
 * <p>Spring Data's {@code schema-action: create_if_not_exists} only ever CREATEs;
 * it never ALTERs an existing table, so a deployment that already has these
 * tables would fail on the first save with the new entity field. Same idempotent
 * {@code ensureColumn} pattern as {@code MentionSchemaInitializer} and
 * {@code ChatCassandraSchemaInitializer}.</p>
 *
 * <p>NULL means "predates moderation" and is read as approved — the same
 * legacy-row convention {@code PostHydrator.isServable} already uses for a null
 * post status. Backfilling would mean rewriting every historical row for no
 * behavioural gain.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationSchemaInitializer {

    private final CqlSession session;

    @Value("${spring.cassandra.keyspace-name:irc_keyspace}")
    private String keyspace;

    @PostConstruct
    public void widenContentTables() {
        // Comments and replies: the queue/hydrator gate reads these.
        ensureColumn("comments_by_post", "moderation_status", "text");
        ensureColumn("replies_by_comment", "moderation_status", "text");
        // comment_lookup is the point-read table every edit/delete path resolves
        // through, so it needs the same flag or a held reply is invisible to the
        // applier that has to publish it later.
        ensureColumn("comment_lookup", "moderation_status", "text");

        // Stories: no status column existed; visibility was the only gate.
        ensureColumn("stories_by_author", "moderation_status", "text");
        ensureColumn("story_lookup", "moderation_status", "text");
    }

    private void ensureColumn(String table, String column, String cqlType) {
        try {
            if (!columnExists(table, column)) {
                session.execute("ALTER TABLE " + keyspace + "." + table
                        + " ADD " + column + " " + cqlType);
                log.info("[MODERATION-CASSANDRA] added {}.{} ({})", table, column, cqlType);
            }
        } catch (Exception e) {
            log.warn("[MODERATION-CASSANDRA] could not ensure column {}.{}: {}",
                    table, column, e.getMessage());
        }
    }

    private boolean columnExists(String table, String column) {
        return session.execute(
                "SELECT column_name FROM system_schema.columns"
                        + " WHERE keyspace_name = ? AND table_name = ? AND column_name = ?",
                keyspace, table, column).one() != null;
    }
}

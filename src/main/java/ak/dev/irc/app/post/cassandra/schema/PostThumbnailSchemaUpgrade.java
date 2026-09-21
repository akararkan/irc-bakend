package ak.dev.irc.app.post.cassandra.schema;

import com.datastax.oss.driver.api.core.CqlSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Adds the server-generated video-poster column to {@code posts_by_id} on
 * existing keyspaces — {@code schema-action: create_if_not_exists} creates
 * missing TABLES from the entity mapping but never ALTERs an existing one, so
 * the new {@code thumbnail_url} field needs this one-time, idempotent upgrade
 * (an already-present column just logs and moves on). Same pattern as
 * {@link SoundSchemaUpgrade}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostThumbnailSchemaUpgrade {

    private final CqlSession session;

    @Value("${spring.cassandra.keyspace-name:irc_keyspace}")
    private String keyspace;

    @EventListener(ApplicationReadyEvent.class)
    public void upgrade() {
        addColumn("posts_by_id", "thumbnail_url", "text");
    }

    private void addColumn(String table, String column, String type) {
        try {
            session.execute("ALTER TABLE %s.%s ADD %s %s".formatted(keyspace, table, column, type));
            log.info("[POST-SCHEMA] added {}.{}", table, column);
        } catch (Exception ex) {
            // "Invalid column name … conflicts with an existing column" = already applied.
            log.debug("[POST-SCHEMA] {}.{} not added ({})", table, column, ex.getMessage());
        }
    }
}

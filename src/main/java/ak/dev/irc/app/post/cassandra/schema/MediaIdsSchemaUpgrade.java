package ak.dev.irc.app.post.cassandra.schema;

import com.datastax.oss.driver.api.core.CqlSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Media-pipeline columns on existing keyspaces (same idempotent pattern as
 * {@link PostThumbnailSchemaUpgrade}): {@code posts_by_id.media_ids} is
 * index-aligned with {@code media_urls} ("" for legacy/unprocessed entries) and
 * lets the read path bulk-hydrate variant sets; {@code
 * stories_by_author.media_asset_id} is the single story asset.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaIdsSchemaUpgrade {

    private final CqlSession session;

    @Value("${spring.cassandra.keyspace-name:irc_keyspace}")
    private String keyspace;

    @EventListener(ApplicationReadyEvent.class)
    public void upgrade() {
        addColumn("posts_by_id", "media_ids", "list<text>");
        addColumn("stories_by_author", "media_asset_id", "text");
    }

    private void addColumn(String table, String column, String type) {
        try {
            session.execute("ALTER TABLE %s.%s ADD %s %s".formatted(keyspace, table, column, type));
            log.info("[MEDIA-SCHEMA] added {}.{}", table, column);
        } catch (Exception ex) {
            // "Invalid column name … conflicts with an existing column" = already applied.
            log.debug("[MEDIA-SCHEMA] {}.{} not added ({})", table, column, ex.getMessage());
        }
    }
}

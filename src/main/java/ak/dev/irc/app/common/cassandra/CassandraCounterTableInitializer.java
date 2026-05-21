package ak.dev.irc.app.common.cassandra;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.Row;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Detects counter tables that were created as plain {@code bigint} (because
 * Spring's schema-action didn't know they were counters) and rebuilds them
 * with the correct {@code counter} CQL type.
 *
 * Cassandra rules:
 *  - All non-PK columns in a counter table must be {@code counter} type.
 *  - You cannot {@code ALTER} a column to/from counter — must drop and recreate.
 *
 * Counters are recomputable from the source tables, so dropping them on a
 * fresh dev environment is safe. In prod, run a one-time backfill after this.
 *
 * Runs as a {@code @PostConstruct} on a separate component — Spring instantiates
 * it after the Cassandra session is open but before any service touches a counter.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CassandraCounterTableInitializer {

    private final CqlSession session;

    @Value("${spring.cassandra.keyspace-name:irc_keyspace}")
    private String keyspace;

    /** name → (PK column, non-counter columns we need to recreate as counters) */
    private static final Map<String, TableSpec> TABLES = Map.of(
            "post_counters",
                new TableSpec("post_id",   "uuid",
                        List.of("reaction_count", "comment_count", "share_count",
                                "view_count", "save_count")),
            "comment_counters",
                new TableSpec("comment_id", "uuid",
                        List.of("reaction_count", "reply_count")),
            "sound_counters",
                new TableSpec("sound_id",   "uuid",
                        List.of("use_count")),
            "hashtag_counters",
                new TableSpec("hashtag",    "text",
                        List.of("post_count")),
            "poll_counters",
                new TableSpec("poll_id",    "uuid",
                        List.of("vote_a", "vote_b")),
            "notification_unread_counter",
                new TableSpec("user_id",    "uuid",
                        List.of("unread"))
    );

    @PostConstruct
    public void ensureCounterColumns() {
        TABLES.forEach(this::repairIfMistyped);
    }

    private void repairIfMistyped(String table, TableSpec spec) {
        String probeColumn = spec.counters.get(0);
        String currentType = readColumnType(table, probeColumn);

        if ("counter".equalsIgnoreCase(currentType)) {
            log.debug("[CASSANDRA-COUNTER] {} already uses counter columns — skip", table);
            ensureTableExists(table, spec);   // idempotent CREATE in case it doesn't exist
            return;
        }
        if (currentType != null) {
            // Wrong type (likely bigint). Drop and recreate.
            log.warn("[CASSANDRA-COUNTER] {} column {} is {} — dropping and recreating as counter",
                    table, probeColumn, currentType);
            session.execute("DROP TABLE IF EXISTS " + keyspace + "." + table);
        }
        ensureTableExists(table, spec);
    }

    private String readColumnType(String table, String column) {
        Row row = session.execute(
                "SELECT type FROM system_schema.columns "
                + "WHERE keyspace_name = ? AND table_name = ? AND column_name = ?",
                keyspace, table, column).one();
        return row == null ? null : row.getString("type");
    }

    private void ensureTableExists(String table, TableSpec spec) {
        StringBuilder cql = new StringBuilder("CREATE TABLE IF NOT EXISTS ")
                .append(keyspace).append('.').append(table)
                .append(" (").append(spec.pkColumn).append(' ').append(spec.pkType)
                .append(" PRIMARY KEY");
        for (String c : spec.counters) cql.append(", ").append(c).append(" counter");
        cql.append(')');
        session.execute(cql.toString());
        log.info("[CASSANDRA-COUNTER] {} ready", table);
    }

    private record TableSpec(String pkColumn, String pkType, List<String> counters) {}
}

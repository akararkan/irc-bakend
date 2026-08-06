package ak.dev.irc.app.audit.config;

import com.datastax.oss.driver.api.core.CqlSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Applies the documented 180-day retention to both audit tables.
 * <p>
 * The entities are materialized by {@code schema-action: create_if_not_exists},
 * which cannot emit {@code default_time_to_live} — so on a running system the
 * "180-day TTL" the javadocs promise was never actually applied (the CQL that
 * carries it lives in a reference file no code loads). This initializer closes
 * that gap idempotently: create-with-TTL for fresh keyspaces, ALTER for
 * existing ones. Runs after startup so Spring Data's own schema pass is done.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditSchemaInitializer {

    /** 180 days, in seconds — the retention every audit javadoc documents. */
    public static final int AUDIT_TTL_SECONDS = 15_552_000;

    private final CqlSession session;

    @Value("${spring.cassandra.keyspace-name:irc_keyspace}")
    private String keyspace;

    @EventListener(ApplicationReadyEvent.class)
    public void applyRetention() {
        execute("""
                CREATE TABLE IF NOT EXISTS %s.audit_log_by_user (
                    user_id uuid, created_at timestamp, audit_id uuid, username text,
                    operation text, outcome text, resource_type text, resource_id uuid,
                    http_method text, path text, query_string text, status_code int,
                    duration_ms bigint, ip_address text, user_agent text, summary text,
                    error_code text,
                    PRIMARY KEY (user_id, created_at, audit_id)
                ) WITH CLUSTERING ORDER BY (created_at DESC, audit_id ASC)
                  AND default_time_to_live = %d
                """.formatted(keyspace, AUDIT_TTL_SECONDS));

        execute("""
                CREATE TABLE IF NOT EXISTS %s.audit_log_by_resource (
                    resource_type text, resource_id uuid, created_at timestamp, audit_id uuid,
                    user_id uuid, username text, operation text, outcome text,
                    http_method text, path text, query_string text, status_code int,
                    duration_ms bigint, ip_address text, user_agent text, summary text,
                    error_code text,
                    PRIMARY KEY ((resource_type, resource_id), created_at, audit_id)
                ) WITH CLUSTERING ORDER BY (created_at DESC, audit_id ASC)
                  AND default_time_to_live = %d
                """.formatted(keyspace, AUDIT_TTL_SECONDS));

        // Tables that predate this initializer were created without the TTL —
        // ALTER is idempotent and covers them.
        execute("ALTER TABLE %s.audit_log_by_user WITH default_time_to_live = %d"
                .formatted(keyspace, AUDIT_TTL_SECONDS));
        execute("ALTER TABLE %s.audit_log_by_resource WITH default_time_to_live = %d"
                .formatted(keyspace, AUDIT_TTL_SECONDS));

        log.info("[AUDIT-SCHEMA] 180d default_time_to_live ensured on audit_log_by_user / audit_log_by_resource");
    }

    private void execute(String cql) {
        try {
            session.execute(cql);
        } catch (Exception ex) {
            log.warn("[AUDIT-SCHEMA] statement failed (continuing): {}", ex.getMessage());
        }
    }
}

package ak.dev.irc.app.chat.cassandra;

import com.datastax.oss.driver.api.core.CqlSession;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Creates the chat keyspace objects explicitly and idempotently at boot.
 *
 * <p>Chat introduces the project's <b>first</b> Cassandra UDT ({@code media_ref}).
 * Spring Data's {@code schema-action: create_if_not_exists} does create user
 * types before the tables that reference them, but because a table with a
 * {@code list<frozen<media_ref>>} column will fail hard if the type is missing,
 * this initializer removes all doubt: it runs {@code CREATE TYPE IF NOT EXISTS}
 * first, then {@code CREATE TABLE IF NOT EXISTS} for each chat table. Every
 * statement is idempotent, so it is a harmless no-op once the schema exists and
 * co-exists safely with the entity-driven schema-action.</p>
 *
 * <p>Mirrors the existing {@code CassandraCounterTableInitializer} pattern:
 * a {@code @PostConstruct} on a component that injects the auto-configured
 * {@link CqlSession}, which Spring instantiates after the session is open.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatCassandraSchemaInitializer {

    private final CqlSession session;

    @Value("${spring.cassandra.keyspace-name:irc_keyspace}")
    private String keyspace;

    @PostConstruct
    public void createChatSchema() {
        try {
            session.execute("""
                CREATE TYPE IF NOT EXISTS %s.media_ref (
                    kind          text,
                    storage_key   text,
                    url           text,
                    thumbnail_key text,
                    thumbnail_url text,
                    mime          text,
                    bytes         bigint,
                    width         int,
                    height        int,
                    duration_ms   int,
                    waveform      text,
                    file_name     text,
                    alt_text      text
                )""".formatted(keyspace));

            session.execute("""
                CREATE TABLE IF NOT EXISTS %s.messages_by_conversation (
                    conversation_id uuid,
                    bucket          int,
                    message_id      bigint,
                    sender_id       uuid,
                    type            text,
                    body            text,
                    media           list<frozen<media_ref>>,
                    reply_to_id     bigint,
                    forwarded_from  uuid,
                    mentions        set<uuid>,
                    edited_at       timestamp,
                    deleted         boolean,
                    system_event    text,
                    created_at      timestamp,
                    PRIMARY KEY ((conversation_id, bucket), message_id)
                ) WITH CLUSTERING ORDER BY (message_id DESC)""".formatted(keyspace));

            session.execute("""
                CREATE TABLE IF NOT EXISTS %s.message_by_id (
                    message_id      bigint PRIMARY KEY,
                    conversation_id uuid,
                    bucket          int,
                    sender_id       uuid,
                    type            text,
                    body            text,
                    media           list<frozen<media_ref>>,
                    reply_to_id     bigint,
                    forwarded_from  uuid,
                    mentions        set<uuid>,
                    deleted         boolean,
                    edited_at       timestamp,
                    system_event    text,
                    created_at      timestamp
                )""".formatted(keyspace));

            session.execute("""
                CREATE TABLE IF NOT EXISTS %s.reactions_by_message (
                    message_id bigint,
                    user_id    uuid,
                    emoji      text,
                    created_at timestamp,
                    PRIMARY KEY ((message_id), user_id)
                )""".formatted(keyspace));

            log.info("[CHAT-CASSANDRA] media_ref UDT + message tables ready");
        } catch (Exception e) {
            // Non-fatal in a local env with no Cassandra — the app already tolerates
            // a missing broker/driver; log loudly and let Spring surface a clearer
            // error on first real use if the cluster is genuinely misconfigured.
            log.warn("[CHAT-CASSANDRA] schema init skipped/failed: {}", e.getMessage());
        }
    }
}

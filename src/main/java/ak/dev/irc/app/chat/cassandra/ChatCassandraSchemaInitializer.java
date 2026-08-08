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
                    tags            set<text>,
                    author_signature text,
                    poll            text,
                    location        text,
                    contact         text,
                    edited_at       timestamp,
                    deleted         boolean,
                    system_event    text,
                    created_at      timestamp,
                    moderation_status text,
                    delivered boolean,
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
                    tags            set<text>,
                    author_signature text,
                    poll            text,
                    location        text,
                    contact         text,
                    deleted         boolean,
                    edited_at       timestamp,
                    system_event    text,
                    created_at      timestamp,
                    moderation_status text,
                    delivered boolean
                )""".formatted(keyspace));

            session.execute("""
                CREATE TABLE IF NOT EXISTS %s.reactions_by_message (
                    message_id bigint,
                    user_id    uuid,
                    emoji      text,
                    created_at timestamp,
                    PRIMARY KEY ((message_id), user_id)
                )""".formatted(keyspace));

            // Poll votes — one row per (poll message, voter); Redis holds the counts.
            session.execute("""
                CREATE TABLE IF NOT EXISTS %s.poll_votes_by_message (
                    message_id bigint,
                    user_id    uuid,
                    options    set<int>,
                    created_at timestamp,
                    PRIMARY KEY ((message_id), user_id)
                )""".formatted(keyspace));

            // Per-post view/forward counters (Cassandra counter table — counters
            // must live in their own table).
            session.execute("""
                CREATE TABLE IF NOT EXISTS %s.message_counters (
                    message_id bigint PRIMARY KEY,
                    views      counter,
                    forwards   counter
                )""".formatted(keyspace));

            // Shared-media gallery index: one partition per (conversation, kind),
            // newest first — "all photos of this channel" without a timeline scan.
            session.execute("""
                CREATE TABLE IF NOT EXISTS %s.media_by_conversation (
                    conversation_id uuid,
                    kind            text,
                    message_id      bigint,
                    media_index     int,
                    created_at      timestamp,
                    PRIMARY KEY ((conversation_id, kind), message_id, media_index)
                ) WITH CLUSTERING ORDER BY (message_id DESC, media_index ASC)""".formatted(keyspace));

            // Discussion-group comment index: comments on a channel post are the
            // linked group's replies to it — one partition per post, newest first.
            session.execute("""
                CREATE TABLE IF NOT EXISTS %s.chat_comments_by_post (
                    post_message_id    bigint,
                    comment_message_id bigint,
                    created_at         timestamp,
                    PRIMARY KEY ((post_message_id), comment_message_id)
                ) WITH CLUSTERING ORDER BY (comment_message_id DESC)""".formatted(keyspace));

            repairMessageByIdColumn();

            // Channel-post columns added after the tables first shipped —
            // CREATE TABLE IF NOT EXISTS never alters an existing table, so an
            // existing keyspace needs an explicit, idempotent ALTER per column.
            ensureColumn("messages_by_conversation", "tags", "set<text>");
            ensureColumn("messages_by_conversation", "author_signature", "text");
            ensureColumn("messages_by_conversation", "poll", "text");
            ensureColumn("messages_by_conversation", "location", "text");
            ensureColumn("messages_by_conversation", "contact", "text");
            ensureColumn("message_by_id", "tags", "set<text>");
            ensureColumn("message_by_id", "author_signature", "text");
            ensureColumn("message_by_id", "poll", "text");
            ensureColumn("message_by_id", "location", "text");
            ensureColumn("message_by_id", "contact", "text");
            ensureColumn("message_counters", "comments", "counter");
            // Automated text moderation (docs/moderation/) — both message tables
            // carry the held/rejected flag so the read path and the applier agree.
            ensureColumn("messages_by_conversation", "moderation_status", "text");
            ensureColumn("message_by_id", "moderation_status", "text");
            // NULL = predates moderation, and those rows were all delivered.
            ensureColumn("messages_by_conversation", "delivered", "boolean");
            ensureColumn("message_by_id", "delivered", "boolean");

            log.info("[CHAT-CASSANDRA] media_ref UDT + message/poll/counter/gallery tables ready");
        } catch (Exception e) {
            // Non-fatal in a local env with no Cassandra — the app already tolerates
            // a missing broker/driver; log loudly and let Spring surface a clearer
            // error on first real use if the cluster is genuinely misconfigured.
            log.warn("[CHAT-CASSANDRA] schema init skipped/failed: {}", e.getMessage());
        }
    }

    /**
     * Repairs the {@code message_by_id} primary-key column name for keyspaces
     * created by a pre-fix build.
     *
     * <p>The entity's id was originally a bare {@code @PrimaryKey} with no
     * {@code @Column}. Spring Data Cassandra does <b>not</b> snake_case a camelCase
     * property — it lowercases it — so {@code messageId} became the CQL column
     * {@code messageid}. The entity now declares {@code @Column("message_id")} and
     * every repository query uses {@code message_id}, but neither
     * {@code schema-action=create_if_not_exists} nor {@code CREATE TABLE IF NOT
     * EXISTS} ever renames a column on a table that already exists — so an existing
     * keyspace is left with {@code messageid} and every id lookup fails with
     * {@code Undefined column name message_id}.</p>
     *
     * <p>Cassandra permits renaming a primary-key column, and the rename is a pure
     * metadata change that <b>preserves every existing row</b>, so we rename in
     * place. Idempotent: a fresh keyspace already has {@code message_id} and skips.</p>
     */
    private void repairMessageByIdColumn() {
        try {
            boolean hasCorrect = columnExists("message_by_id", "message_id");
            boolean hasLegacy  = columnExists("message_by_id", "messageid");
            if (hasLegacy && !hasCorrect) {
                session.execute("ALTER TABLE " + keyspace + ".message_by_id RENAME messageid TO message_id");
                log.warn("[CHAT-CASSANDRA] repaired message_by_id: renamed primary-key column "
                        + "'messageid' -> 'message_id' (existing messages preserved)");
            }
        } catch (Exception e) {
            log.error("[CHAT-CASSANDRA] could not repair message_by_id primary-key column "
                    + "('messageid' -> 'message_id'); id-based message ops will fail until fixed: {}",
                    e.getMessage());
        }
    }

    /** Idempotently add a column to an existing table (no-op when present). */
    private void ensureColumn(String table, String column, String cqlType) {
        try {
            if (!columnExists(table, column)) {
                session.execute("ALTER TABLE " + keyspace + "." + table
                        + " ADD " + column + " " + cqlType);
                log.info("[CHAT-CASSANDRA] added {}.{} ({})", table, column, cqlType);
            }
        } catch (Exception e) {
            log.warn("[CHAT-CASSANDRA] could not ensure column {}.{}: {}", table, column, e.getMessage());
        }
    }

    private boolean columnExists(String table, String column) {
        return session.execute(
                "SELECT column_name FROM system_schema.columns "
                + "WHERE keyspace_name = ? AND table_name = ? AND column_name = ?",
                keyspace, table, column).one() != null;
    }
}

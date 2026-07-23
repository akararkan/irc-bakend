-- ═══════════════════════════════════════════════════════════════════════════════
--  IRC PLATFORM — CHAT / MESSAGING SCHEMA  ·  Cassandra (CQL)
-- ═══════════════════════════════════════════════════════════════════════════════
--
--  The message LOG lives in Cassandra; the relational metadata (conversations,
--  members, requests, settings, calls, streams, …) is the companion file:
--      db/chat_schema_postgres.sql
--
--  YOU PROBABLY DO NOT NEED TO RUN THIS BY HAND. These objects are created
--  idempotently at boot by ChatCassandraSchemaInitializer (@PostConstruct), which
--  also repairs a legacy `messageid` -> `message_id` column on old keyspaces.
--
--  Run it manually only to provision a keyspace by hand, e.g.:
--      cqlsh -k irc_keyspace -f db/chat_schema_cassandra.cql
--  or open cqlsh and `USE irc_keyspace;` first. Every statement is idempotent.
--
--  Design: messages_by_conversation is partitioned by (conversation_id, bucket)
--  — bucket is a time bucket of the Snowflake id — and clustered by message_id
--  DESC (newest first). message_by_id is the point-lookup mirror. media_ref is a
--  frozen UDT (the project's first). Snowflake ids -> bigint.
--
--  Disappearing messages get a per-row TTL applied by the app at insert/edit time
--  (CQL `USING TTL <seconds>`) — there is no schema-level TTL here.
-- ═══════════════════════════════════════════════════════════════════════════════

-- Fresh cluster only — create + select the keyspace (tune replication for prod):
-- CREATE KEYSPACE IF NOT EXISTS irc_keyspace
--     WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};
-- USE irc_keyspace;

-- ── media_ref UDT — an attachment; frozen, embedded in message rows ─────────────
CREATE TYPE IF NOT EXISTS media_ref (
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
);

-- ── messages_by_conversation — the message log (one partition per conv + bucket) ─
CREATE TABLE IF NOT EXISTS messages_by_conversation (
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
) WITH CLUSTERING ORDER BY (message_id DESC);

-- ── message_by_id — point lookups (replies, forwards, jump-to, edit/delete) ──────
CREATE TABLE IF NOT EXISTS message_by_id (
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
);

-- ── reactions_by_message — one row per (message, user) ───────────────────────────
CREATE TABLE IF NOT EXISTS reactions_by_message (
    message_id bigint,
    user_id    uuid,
    emoji      text,
    created_at timestamp,
    PRIMARY KEY ((message_id), user_id)
);

-- ── chat_migrations — one-time backfill / repair markers ─────────────────────────
CREATE TABLE IF NOT EXISTS chat_migrations (
    id         text PRIMARY KEY,
    applied_at timestamp
);

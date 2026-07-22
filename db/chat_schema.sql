-- ═══════════════════════════════════════════════════════════════════════════════
--  IRC PLATFORM — CHAT / MESSAGING SCHEMA
-- ═══════════════════════════════════════════════════════════════════════════════
--
--  YOU PROBABLY DO NOT NEED TO RUN THIS BY HAND.
--  The app uses Hibernate `spring.jpa.hibernate.ddl-auto=update`, so on the next
--  boot it AUTO-CREATES every chat table + index + constraint below. Chat adds
--  only NEW tables — it does NOT alter or add columns to any existing table, so
--  there is no destructive migration and nothing to back-fill.
--
--  Run this file manually ONLY for:
--    • a reproducible fresh install / prod bootstrap (psql -f db/chat_schema.sql), or
--    • an environment where ddl-auto is disabled.
--  Every statement is idempotent (IF NOT EXISTS), so re-running is safe.
--
--  Postgres tables. (Cassandra tables + the media_ref UDT are created
--  idempotently at boot by ChatCassandraSchemaInitializer — CQL reproduced at the
--  bottom of this file for reference.)
--
--  Types: UUID→uuid, Snowflake ids (message/last_read/…)→bigint, enums→varchar,
--  LocalDateTime→timestamp (the JVM + hibernate.jdbc.time_zone are pinned to UTC),
--  group settings→jsonb. Every table carries the shared BaseAuditEntity columns.
-- ═══════════════════════════════════════════════════════════════════════════════

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. conversations
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS conversations (
    id                    uuid         PRIMARY KEY,
    type                  varchar(8)   NOT NULL,           -- DIRECT | GROUP
    title                 varchar(120),                    -- null for DIRECT
    avatar_key            varchar(255),
    owner_id              uuid         NOT NULL,
    direct_key            varchar(73),                     -- 'minUserId:maxUserId'; null for GROUP
    last_message_id       bigint,                          -- Snowflake of newest message
    last_message_at       timestamp,
    last_message_preview  varchar(160),
    member_count          integer      NOT NULL DEFAULT 0,
    group_settings        jsonb,                           -- GROUP settings; null for DIRECT
    deleted_at            timestamp,
    -- shared audit columns (BaseAuditEntity)
    created_at            timestamp    NOT NULL,
    updated_at            timestamp    NOT NULL,
    created_by            uuid,
    updated_by            uuid,
    created_by_ip         varchar(45),
    updated_by_ip         varchar(45),
    created_by_device     varchar(300),
    updated_by_device     varchar(300),
    last_action           varchar(30),
    action_note           varchar(500),
    CONSTRAINT uk_conversation_direct_key UNIQUE (direct_key)
);
CREATE INDEX IF NOT EXISTS idx_conversation_last_msg ON conversations (last_message_at DESC);
CREATE INDEX IF NOT EXISTS idx_conversation_owner    ON conversations (owner_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. conversation_members  (composite PK conversation_id + user_id)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS conversation_members (
    conversation_id       uuid         NOT NULL,
    user_id               uuid         NOT NULL,
    role                  varchar(8)   NOT NULL,           -- OWNER | ADMIN | MEMBER
    status                varchar(10)  NOT NULL,           -- ACTIVE | RESTRICTED | LEFT | REMOVED
    last_read_message_id  bigint       NOT NULL DEFAULT 0,
    unread_count          integer      NOT NULL DEFAULT 0,
    muted_until           timestamp,
    pinned                boolean      NOT NULL DEFAULT false,
    archived              boolean      NOT NULL DEFAULT false,
    joined_at             timestamp    NOT NULL,
    created_at            timestamp    NOT NULL,
    updated_at            timestamp    NOT NULL,
    created_by            uuid,
    updated_by            uuid,
    created_by_ip         varchar(45),
    updated_by_ip         varchar(45),
    created_by_device     varchar(300),
    updated_by_device     varchar(300),
    last_action           varchar(30),
    action_note           varchar(500),
    CONSTRAINT pk_conversation_members PRIMARY KEY (conversation_id, user_id),
    CONSTRAINT fk_member_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id)
);
CREATE INDEX IF NOT EXISTS idx_member_inbox        ON conversation_members (user_id, archived);
CREATE INDEX IF NOT EXISTS idx_member_conversation ON conversation_members (conversation_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. message_requests
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS message_requests (
    id                    uuid         PRIMARY KEY,
    conversation_id       uuid         NOT NULL,
    requester_id          uuid         NOT NULL,
    recipient_id          uuid         NOT NULL,
    status                varchar(10)  NOT NULL,           -- PENDING | ACCEPTED | DECLINED | BLOCKED
    first_message_id      bigint       NOT NULL,
    message_count         integer      NOT NULL DEFAULT 1,
    created_at            timestamp    NOT NULL,
    updated_at            timestamp    NOT NULL,
    created_by            uuid,
    updated_by            uuid,
    created_by_ip         varchar(45),
    updated_by_ip         varchar(45),
    created_by_device     varchar(300),
    updated_by_device     varchar(300),
    last_action           varchar(30),
    action_note           varchar(500),
    CONSTRAINT uk_message_request_pair UNIQUE (recipient_id, requester_id)
);
CREATE INDEX IF NOT EXISTS idx_request_inbox ON message_requests (recipient_id, status, created_at DESC);

-- ─────────────────────────────────────────────────────────────────────────────
-- 4. conversation_invites
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS conversation_invites (
    id                    uuid         PRIMARY KEY,
    conversation_id       uuid         NOT NULL,
    token_hash            varchar(64)  NOT NULL,           -- SHA-256 of the opaque token
    created_by_user       uuid         NOT NULL,
    expires_at            timestamp,
    max_uses              integer,
    use_count             integer      NOT NULL DEFAULT 0,
    revoked               boolean      NOT NULL DEFAULT false,
    created_at            timestamp    NOT NULL,
    updated_at            timestamp    NOT NULL,
    created_by            uuid,
    updated_by            uuid,
    created_by_ip         varchar(45),
    updated_by_ip         varchar(45),
    created_by_device     varchar(300),
    updated_by_device     varchar(300),
    last_action           varchar(30),
    action_note           varchar(500),
    CONSTRAINT uk_invite_token_hash UNIQUE (token_hash)
);
CREATE INDEX IF NOT EXISTS idx_invite_conversation ON conversation_invites (conversation_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- 5. conversation_pins  (pinned messages)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS conversation_pins (
    id                    uuid         PRIMARY KEY,
    conversation_id       uuid         NOT NULL,
    message_id            bigint       NOT NULL,
    pinned_by             uuid         NOT NULL,
    pinned_at             timestamp    NOT NULL,
    created_at            timestamp    NOT NULL,
    updated_at            timestamp    NOT NULL,
    created_by            uuid,
    updated_by            uuid,
    created_by_ip         varchar(45),
    updated_by_ip         varchar(45),
    created_by_device     varchar(300),
    updated_by_device     varchar(300),
    last_action           varchar(30),
    action_note           varchar(500),
    CONSTRAINT uk_pin_conversation_message UNIQUE (conversation_id, message_id)
);
CREATE INDEX IF NOT EXISTS idx_pin_conversation ON conversation_pins (conversation_id);


-- ═══════════════════════════════════════════════════════════════════════════════
--  NOTE — NO CHANGES TO EXISTING TABLES
--  Chat added new values to the Java enums NotificationType / NotificationKind /
--  NotificationCategory (NEW_MESSAGE, MESSAGE_REQUEST, ADDED_TO_GROUP). Those are
--  stored as plain varchar strings in the existing notification tables, so NO
--  ALTER is required — the columns already accept the new values.
--
--  If (and only if) an earlier partial run created a chat table without a column
--  added later, use the additive form, e.g.:
--     ALTER TABLE conversations         ADD COLUMN IF NOT EXISTS group_settings jsonb;
--     ALTER TABLE conversation_members  ADD COLUMN IF NOT EXISTS muted_until timestamp;
--  (New NOT NULL columns need a DEFAULT so existing rows back-fill, e.g.
--     ALTER TABLE conversation_members  ADD COLUMN IF NOT EXISTS unread_count integer NOT NULL DEFAULT 0; )
-- ═══════════════════════════════════════════════════════════════════════════════


-- ═══════════════════════════════════════════════════════════════════════════════
--  CASSANDRA (reference only — created automatically by ChatCassandraSchemaInitializer)
--  Run against the irc keyspace if you provision Cassandra by hand.
-- ═══════════════════════════════════════════════════════════════════════════════
-- CREATE TYPE IF NOT EXISTS media_ref (
--     kind text, storage_key text, url text, thumbnail_key text, thumbnail_url text,
--     mime text, bytes bigint, width int, height int, duration_ms int,
--     waveform text, file_name text, alt_text text);
--
-- CREATE TABLE IF NOT EXISTS messages_by_conversation (
--     conversation_id uuid, bucket int, message_id bigint, sender_id uuid, type text,
--     body text, media list<frozen<media_ref>>, reply_to_id bigint, forwarded_from uuid,
--     mentions set<uuid>, edited_at timestamp, deleted boolean, system_event text,
--     created_at timestamp,
--     PRIMARY KEY ((conversation_id, bucket), message_id)
-- ) WITH CLUSTERING ORDER BY (message_id DESC);
--
-- CREATE TABLE IF NOT EXISTS message_by_id (
--     message_id bigint PRIMARY KEY, conversation_id uuid, bucket int, sender_id uuid,
--     type text, body text, media list<frozen<media_ref>>, reply_to_id bigint,
--     forwarded_from uuid, mentions set<uuid>, deleted boolean, edited_at timestamp,
--     system_event text, created_at timestamp);
--
-- CREATE TABLE IF NOT EXISTS reactions_by_message (
--     message_id bigint, user_id uuid, emoji text, created_at timestamp,
--     PRIMARY KEY ((message_id), user_id));

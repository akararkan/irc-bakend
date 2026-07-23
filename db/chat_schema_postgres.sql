-- ═══════════════════════════════════════════════════════════════════════════════
--  IRC PLATFORM — CHAT / MESSAGING SCHEMA  ·  PostgreSQL (relational)
-- ═══════════════════════════════════════════════════════════════════════════════
--
--  YOU PROBABLY DO NOT NEED TO RUN THIS BY HAND.
--  The app uses Hibernate `spring.jpa.hibernate.ddl-auto=update`, so on the next
--  boot it AUTO-CREATES every table + index + constraint below.
--
--  Run this file manually ONLY for:
--    • a reproducible fresh install / prod bootstrap (psql -f db/chat_schema_postgres.sql), or
--    • an environment where ddl-auto is disabled.
--  Every statement is idempotent (IF NOT EXISTS), so re-running is safe.
--
--  This is the PostgreSQL half (conversations, members, requests, settings, calls,
--  streams, …). The Cassandra message-log schema is the companion file:
--      db/chat_schema_cassandra.cql   (auto-created at boot by ChatCassandraSchemaInitializer)
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
    type                  varchar(8)   NOT NULL,           -- DIRECT | GROUP | CHANNEL
    title                 varchar(120),                    -- null for DIRECT
    description           varchar(500),                    -- group/channel description/topic
    disappearing_seconds  integer      NOT NULL DEFAULT 0, -- disappearing-messages timer (0 = off)
    avatar_key            varchar(255),
    handle                varchar(32),                     -- public CHANNEL @handle (UNIQUE); null otherwise
    is_public             boolean      NOT NULL DEFAULT false, -- CHANNEL discoverability
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
CREATE UNIQUE INDEX IF NOT EXISTS uk_conversation_handle ON conversations (handle);

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. conversation_members  (composite PK conversation_id + user_id)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS conversation_members (
    conversation_id       uuid         NOT NULL,
    user_id               uuid         NOT NULL,
    role                  varchar(8)   NOT NULL,           -- OWNER | ADMIN | MEMBER
    status                varchar(10)  NOT NULL,           -- ACTIVE | RESTRICTED | LEFT | REMOVED
    last_read_message_id  bigint       NOT NULL DEFAULT 0,
    last_delivered_message_id bigint   NOT NULL DEFAULT 0,   -- delivered high-water (double-tick)
    unread_count          integer      NOT NULL DEFAULT 0,
    marked_unread         boolean      NOT NULL DEFAULT false, -- explicit "mark as unread"
    muted_until           timestamp,
    pinned                boolean      NOT NULL DEFAULT false,
    archived              boolean      NOT NULL DEFAULT false,
    cleared_before_message_id bigint   NOT NULL DEFAULT 0,   -- "delete conversation for me" high-water
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

-- ─────────────────────────────────────────────────────────────────────────────
-- 6. chat_user_settings  (per-user privacy: read receipts, last-seen, typing)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS chat_user_settings (
    user_id                    uuid    PRIMARY KEY,
    read_receipts_enabled      boolean NOT NULL DEFAULT true,
    last_seen_visible          boolean NOT NULL DEFAULT true,
    typing_indicators_enabled  boolean NOT NULL DEFAULT true,
    created_at            timestamp    NOT NULL,
    updated_at            timestamp    NOT NULL,
    created_by            uuid,
    updated_by            uuid,
    created_by_ip         varchar(45),
    updated_by_ip         varchar(45),
    created_by_device     varchar(300),
    updated_by_device     varchar(300),
    last_action           varchar(30),
    action_note           varchar(500)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- 7. message_stars  (starred / bookmarked messages)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS message_stars (
    id                    uuid         PRIMARY KEY,
    user_id               uuid         NOT NULL,
    conversation_id       uuid         NOT NULL,
    message_id            bigint       NOT NULL,
    starred_at            timestamp    NOT NULL,
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
    CONSTRAINT uk_star_user_message UNIQUE (user_id, message_id)
);
CREATE INDEX IF NOT EXISTS idx_star_user ON message_stars (user_id, starred_at DESC);

-- ─────────────────────────────────────────────────────────────────────────────
-- 8. hidden_messages  ("delete message for me" — per-user hide)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS hidden_messages (
    user_id               uuid         NOT NULL,
    message_id            bigint       NOT NULL,
    conversation_id       uuid         NOT NULL,
    hidden_at             timestamp    NOT NULL,
    CONSTRAINT pk_hidden_messages PRIMARY KEY (user_id, message_id)
);
CREATE INDEX IF NOT EXISTS idx_hidden_user ON hidden_messages (user_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- 9. scheduled_messages  (send-later queue)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS scheduled_messages (
    id                    uuid         PRIMARY KEY,
    conversation_id       uuid         NOT NULL,
    sender_id             uuid         NOT NULL,
    scheduled_at          timestamp    NOT NULL,
    type                  varchar(8)   NOT NULL,
    body                  text,
    media                 jsonb,
    reply_to_id           bigint,
    client_nonce          varchar(64)  NOT NULL,
    status                varchar(10)  NOT NULL,           -- PENDING | SENT | CANCELLED | FAILED
    sent_message_id       bigint,
    created_at            timestamp    NOT NULL,
    updated_at            timestamp    NOT NULL,
    created_by            uuid,
    updated_by            uuid,
    created_by_ip         varchar(45),
    updated_by_ip         varchar(45),
    created_by_device     varchar(300),
    updated_by_device     varchar(300),
    last_action           varchar(30),
    action_note           varchar(500)
);
CREATE INDEX IF NOT EXISTS idx_scheduled_due          ON scheduled_messages (status, scheduled_at);
CREATE INDEX IF NOT EXISTS idx_scheduled_conversation ON scheduled_messages (conversation_id);
CREATE INDEX IF NOT EXISTS idx_scheduled_sender       ON scheduled_messages (sender_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- 10. call_sessions + call_participants  — voice/video call control plane
--     (signaling only; the WebRTC media flows peer-to-peer, never through here)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS call_sessions (
    id                uuid        PRIMARY KEY,
    conversation_id   uuid        NOT NULL,
    initiator_id      uuid        NOT NULL,
    type              varchar(8)  NOT NULL,               -- VOICE | VIDEO
    status            varchar(12) NOT NULL,               -- RINGING|ONGOING|ENDED|DECLINED|MISSED|CANCELLED
    started_at        timestamp   NOT NULL,
    answered_at       timestamp,
    ended_at          timestamp,
    end_reason        varchar(40),
    created_at        timestamp   NOT NULL,
    updated_at        timestamp   NOT NULL,
    created_by        uuid, updated_by uuid,
    created_by_ip     varchar(45), updated_by_ip varchar(45),
    created_by_device varchar(300), updated_by_device varchar(300),
    last_action       varchar(30), action_note varchar(500)
);
CREATE INDEX IF NOT EXISTS idx_call_conversation ON call_sessions (conversation_id);
CREATE INDEX IF NOT EXISTS idx_call_status       ON call_sessions (status);

CREATE TABLE IF NOT EXISTS call_participants (
    id                uuid        PRIMARY KEY,
    call_id           uuid        NOT NULL,
    user_id           uuid        NOT NULL,
    state             varchar(10) NOT NULL,               -- INVITED | JOINED | DECLINED | LEFT
    joined_at         timestamp,
    left_at           timestamp,
    created_at        timestamp   NOT NULL,
    updated_at        timestamp   NOT NULL,
    created_by        uuid, updated_by uuid,
    created_by_ip     varchar(45), updated_by_ip varchar(45),
    created_by_device varchar(300), updated_by_device varchar(300),
    last_action       varchar(30), action_note varchar(500),
    CONSTRAINT uk_call_participant UNIQUE (call_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_call_participant_call ON call_participants (call_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- 11. live_streams + stream_viewers  — live streaming control plane
--     (media is ingested to / served from an external server via stream_key)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS live_streams (
    id                uuid         PRIMARY KEY,
    host_id           uuid         NOT NULL,
    title             varchar(140) NOT NULL,
    description       varchar(500),
    status            varchar(10)  NOT NULL,              -- LIVE | ENDED
    stream_key        varchar(64)  NOT NULL,             -- secret ingest key (host-only)
    viewer_count      integer      NOT NULL DEFAULT 0,
    peak_viewer_count integer      NOT NULL DEFAULT 0,
    started_at        timestamp    NOT NULL,
    ended_at          timestamp,
    created_at        timestamp    NOT NULL,
    updated_at        timestamp    NOT NULL,
    created_by        uuid, updated_by uuid,
    created_by_ip     varchar(45), updated_by_ip varchar(45),
    created_by_device varchar(300), updated_by_device varchar(300),
    last_action       varchar(30), action_note varchar(500)
);
CREATE INDEX IF NOT EXISTS idx_stream_status ON live_streams (status);
CREATE INDEX IF NOT EXISTS idx_stream_host   ON live_streams (host_id);

CREATE TABLE IF NOT EXISTS stream_viewers (
    id                uuid       PRIMARY KEY,
    stream_id         uuid       NOT NULL,
    user_id           uuid       NOT NULL,
    active            boolean    NOT NULL DEFAULT true,
    joined_at         timestamp,
    left_at           timestamp,
    created_at        timestamp  NOT NULL,
    updated_at        timestamp  NOT NULL,
    created_by        uuid, updated_by uuid,
    created_by_ip     varchar(45), updated_by_ip varchar(45),
    created_by_device varchar(300), updated_by_device varchar(300),
    last_action       varchar(30), action_note varchar(500),
    CONSTRAINT uk_stream_viewer UNIQUE (stream_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_stream_viewer_active ON stream_viewers (stream_id, active);


-- ═══════════════════════════════════════════════════════════════════════════════
--  NOTE — EXISTING-TABLE CHANGES
--  Chat added new values to the Java enums NotificationType / NotificationKind /
--  NotificationCategory (NEW_MESSAGE, MESSAGE_REQUEST, ADDED_TO_GROUP). Those are
--  stored as plain varchar strings in the existing notification tables, so NO
--  ALTER is required — the columns already accept the new values.
--
--  Channels (Telegram-style broadcast) added two columns to `conversations`;
--  ddl-auto=update adds them automatically, or apply manually:
--     ALTER TABLE conversations ADD COLUMN IF NOT EXISTS handle    varchar(32);
--     ALTER TABLE conversations ADD COLUMN IF NOT EXISTS is_public boolean NOT NULL DEFAULT false;
--     CREATE UNIQUE INDEX IF NOT EXISTS uk_conversation_handle ON conversations (handle);
--
--  IMPORTANT (existing DBs only): the CHANNEL value was added to the conversation
--  `type` enum. Hibernate's auto-generated CHECK constraint on an ALREADY-EXISTING
--  `conversations` table still lists only DIRECT|GROUP, and ddl-auto=update does not
--  alter it — so channel inserts fail with `conversations_type_check` until you drop
--  it (a FRESH schema regenerates the check WITH CHANNEL and needs nothing):
--     ALTER TABLE conversations DROP CONSTRAINT IF EXISTS conversations_type_check;
--
--  If (and only if) an earlier partial run created a chat table without a column
--  added later, use the additive form, e.g.:
--     ALTER TABLE conversations         ADD COLUMN IF NOT EXISTS group_settings jsonb;
--     ALTER TABLE conversation_members  ADD COLUMN IF NOT EXISTS muted_until timestamp;
--  (New NOT NULL columns need a DEFAULT so existing rows back-fill, e.g.
--     ALTER TABLE conversation_members  ADD COLUMN IF NOT EXISTS unread_count integer NOT NULL DEFAULT 0; )
--
--  The "delete conversation for me" feature added one column; ddl-auto=update adds
--  it automatically, or apply manually:
--     ALTER TABLE conversation_members
--         ADD COLUMN IF NOT EXISTS cleared_before_message_id bigint NOT NULL DEFAULT 0;
-- ═══════════════════════════════════════════════════════════════════════════════

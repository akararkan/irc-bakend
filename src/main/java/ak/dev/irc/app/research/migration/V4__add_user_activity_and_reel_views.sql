-- ─────────────────────────────────────────────────────────────────────────────
-- V4__add_user_activity_and_reel_views.sql
-- Place in: src/main/resources/db/migration/
--
-- Reference DDL for the user-activity feature.
-- The project currently runs `spring.jpa.hibernate.ddl-auto=update`, so
-- Hibernate will auto-create these tables from the @Entity classes.
-- This file is documentation / a future Flyway baseline.
-- ─────────────────────────────────────────────────────────────────────────────

-- 1. user_activities — every reaction/comment a user makes against a post
CREATE TABLE IF NOT EXISTS user_activities (
    id              UUID        PRIMARY KEY,
    user_id         UUID        NOT NULL REFERENCES users(id)         ON DELETE CASCADE,
    activity_type   VARCHAR(40) NOT NULL,
    post_id         UUID                 REFERENCES posts(id)         ON DELETE CASCADE,
    comment_id      UUID                 REFERENCES post_comments(id) ON DELETE CASCADE,
    reaction_type   VARCHAR(30),

    -- BaseAuditEntity columns
    created_at         TIMESTAMP    NOT NULL,
    updated_at         TIMESTAMP    NOT NULL,
    created_by         UUID,
    updated_by         UUID,
    created_by_ip      VARCHAR(45),
    updated_by_ip      VARCHAR(45),
    created_by_device  VARCHAR(300),
    updated_by_device  VARCHAR(300),
    last_action        VARCHAR(30),
    action_note        VARCHAR(500)
);

CREATE INDEX IF NOT EXISTS idx_uact_user_created ON user_activities (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_uact_user_type    ON user_activities (user_id, activity_type);
CREATE INDEX IF NOT EXISTS idx_uact_post         ON user_activities (post_id);
CREATE INDEX IF NOT EXISTS idx_uact_comment      ON user_activities (comment_id);

-- 2. reel_views — watch history for reels (PostType.REEL)
CREATE TABLE IF NOT EXISTS reel_views (
    id               UUID    PRIMARY KEY,
    user_id          UUID    NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    post_id          UUID    NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    watched_seconds  INTEGER,

    -- BaseAuditEntity columns
    created_at         TIMESTAMP    NOT NULL,
    updated_at         TIMESTAMP    NOT NULL,
    created_by         UUID,
    updated_by         UUID,
    created_by_ip      VARCHAR(45),
    updated_by_ip      VARCHAR(45),
    created_by_device  VARCHAR(300),
    updated_by_device  VARCHAR(300),
    last_action        VARCHAR(30),
    action_note        VARCHAR(500)
);

CREATE INDEX IF NOT EXISTS idx_reelview_user_created ON reel_views (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_reelview_post         ON reel_views (post_id);

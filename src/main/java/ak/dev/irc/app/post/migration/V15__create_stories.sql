-- ─────────────────────────────────────────────────────────────────────────────
-- V15 — Stories, story views, highlights, polls, close friends
-- Reference migration for production Flyway runs.
-- ─────────────────────────────────────────────────────────────────────────────

-- Story highlights must exist BEFORE stories (FK reference)
CREATE TABLE IF NOT EXISTS story_highlights (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    author_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title         VARCHAR(80)  NOT NULL,
    cover_url     TEXT,
    cover_s3_key  TEXT,
    display_order INT          NOT NULL DEFAULT 0,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now(),
    created_by    UUID,
    updated_by    UUID,
    created_by_ip VARCHAR(45),
    updated_by_ip VARCHAR(45),
    created_by_device VARCHAR(300),
    updated_by_device VARCHAR(300),
    last_action   VARCHAR(30),
    action_note   VARCHAR(500)
);
CREATE INDEX IF NOT EXISTS idx_hl_author        ON story_highlights(author_id);
CREATE INDEX IF NOT EXISTS idx_hl_display_order ON story_highlights(author_id, display_order);

CREATE TABLE IF NOT EXISTS stories (
    id                       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    author_id                UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    story_type               VARCHAR(20)  NOT NULL,
    visibility               VARCHAR(20)  NOT NULL DEFAULT 'PUBLIC',
    text_content             TEXT,
    background_type          VARCHAR(20),
    background_value         VARCHAR(500),
    media_url                TEXT,
    media_s3_key             TEXT,
    thumbnail_url            TEXT,
    thumbnail_s3_key         TEXT,
    duration_seconds         INT,
    linked_content_id        UUID,
    linked_content_snapshot  TEXT,
    overlays_json            TEXT,
    highlight_id             UUID         REFERENCES story_highlights(id) ON DELETE SET NULL,
    expires_at               TIMESTAMP    NOT NULL DEFAULT (now() + INTERVAL '24 hours'),
    is_deleted               BOOLEAN      NOT NULL DEFAULT FALSE,
    deleted_at               TIMESTAMP,
    view_count               BIGINT       NOT NULL DEFAULT 0,
    reaction_count           BIGINT       NOT NULL DEFAULT 0,
    reply_count              BIGINT       NOT NULL DEFAULT 0,
    version                  BIGINT       NOT NULL DEFAULT 0,
    created_at               TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at               TIMESTAMP    NOT NULL DEFAULT now(),
    created_by               UUID,
    updated_by               UUID,
    created_by_ip            VARCHAR(45),
    updated_by_ip            VARCHAR(45),
    created_by_device        VARCHAR(300),
    updated_by_device        VARCHAR(300),
    last_action              VARCHAR(30),
    action_note              VARCHAR(500)
);
CREATE INDEX IF NOT EXISTS idx_story_author  ON stories(author_id);
CREATE INDEX IF NOT EXISTS idx_story_expires ON stories(expires_at);
CREATE INDEX IF NOT EXISTS idx_story_active  ON stories(author_id, expires_at, is_deleted);
CREATE INDEX IF NOT EXISTS idx_story_type    ON stories(story_type);
CREATE INDEX IF NOT EXISTS idx_story_hl      ON stories(highlight_id);

CREATE TABLE IF NOT EXISTS story_views (
    story_id          UUID      NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    viewer_id         UUID      NOT NULL REFERENCES users(id)   ON DELETE CASCADE,
    watch_duration_ms INT,
    reaction_emoji    VARCHAR(10),
    replied           BOOLEAN   NOT NULL DEFAULT FALSE,
    viewed_at         TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (story_id, viewer_id)
);
CREATE INDEX IF NOT EXISTS idx_sv_story  ON story_views(story_id);
CREATE INDEX IF NOT EXISTS idx_sv_viewer ON story_views(viewer_id);

CREATE TABLE IF NOT EXISTS story_polls (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    story_id      UUID         NOT NULL UNIQUE REFERENCES stories(id) ON DELETE CASCADE,
    question      VARCHAR(200) NOT NULL,
    option_a      VARCHAR(100) NOT NULL,
    option_b      VARCHAR(100) NOT NULL,
    vote_a_count  INT          NOT NULL DEFAULT 0,
    vote_b_count  INT          NOT NULL DEFAULT 0,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS story_poll_votes (
    id        UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    poll_id   UUID         NOT NULL REFERENCES story_polls(id) ON DELETE CASCADE,
    voter_id  UUID         NOT NULL REFERENCES users(id)       ON DELETE CASCADE,
    choice    VARCHAR(1)   NOT NULL CHECK (choice IN ('A','B')),
    voted_at  TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_poll_voter UNIQUE (poll_id, voter_id)
);

CREATE TABLE IF NOT EXISTS close_friends (
    owner_id   UUID      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    friend_id  UUID      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, friend_id)
);
CREATE INDEX IF NOT EXISTS idx_cf_owner ON close_friends(owner_id);

-- V16: Seed sounds (run separately after V15)
-- INSERT INTO sounds (title, artist_name, category, status, audio_url, duration_seconds, license)
-- VALUES ('Surah Al-Fatiha — Sheikh Mishary', 'Sheikh Mishary Rashid Al-Afasy',
--         'QURAN_RECITATION', 'APPROVED', 'https://cdn.irc.example.com/sounds/...', 43, 'CC-BY-4.0');

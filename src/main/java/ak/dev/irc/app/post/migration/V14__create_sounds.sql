-- ─────────────────────────────────────────────────────────────────────────────
-- V14 — Sound library + PostSound link table
-- Reference migration for production Flyway runs.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS sounds (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    title               VARCHAR(200) NOT NULL,
    artist_name         VARCHAR(150),
    description         TEXT,
    category            VARCHAR(30)  NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING_REVIEW',
    audio_url           TEXT         NOT NULL,
    audio_s3_key        TEXT,
    cover_art_url       TEXT,
    cover_art_s3_key    TEXT,
    duration_seconds    INT          NOT NULL DEFAULT 0,
    default_clip_start  INT          NOT NULL DEFAULT 0,
    mime_type           VARCHAR(50),
    file_size_bytes     BIGINT,
    waveform_data       TEXT,
    source_url          TEXT,
    license             VARCHAR(80),
    uploader_id         UUID         REFERENCES users(id) ON DELETE SET NULL,
    use_count           BIGINT       NOT NULL DEFAULT 0,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    created_by_ip       VARCHAR(45),
    updated_by_ip       VARCHAR(45),
    created_by_device   VARCHAR(300),
    updated_by_device   VARCHAR(300),
    last_action         VARCHAR(30),
    action_note         VARCHAR(500)
);
CREATE INDEX IF NOT EXISTS idx_sound_category  ON sounds(category);
CREATE INDEX IF NOT EXISTS idx_sound_status    ON sounds(status);
CREATE INDEX IF NOT EXISTS idx_sound_uploader  ON sounds(uploader_id);
CREATE INDEX IF NOT EXISTS idx_sound_use_count ON sounds(use_count DESC);

CREATE TABLE IF NOT EXISTS post_sounds (
    id                  UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id             UUID    UNIQUE REFERENCES posts(id)   ON DELETE CASCADE,
    story_id            UUID    UNIQUE REFERENCES stories(id) ON DELETE CASCADE,
    sound_id            UUID    NOT NULL REFERENCES sounds(id) ON DELETE RESTRICT,
    clip_start_seconds  INT     NOT NULL DEFAULT 0,
    volume              REAL    NOT NULL DEFAULT 0.8,
    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT chk_sound_target CHECK (
        (post_id IS NOT NULL AND story_id IS NULL) OR
        (story_id IS NOT NULL AND post_id IS NULL)
    )
);
CREATE INDEX IF NOT EXISTS idx_psound_post  ON post_sounds(post_id);
CREATE INDEX IF NOT EXISTS idx_psound_story ON post_sounds(story_id);
CREATE INDEX IF NOT EXISTS idx_psound_sound ON post_sounds(sound_id);

-- Add STORY to post_type enum if using PostgreSQL enum type
-- (Hibernate STRING strategy means this is already a varchar — no action needed)

-- ─────────────────────────────────────────────────────────────────────────────
-- V4__add_comment_hidden_columns.sql
-- Adds columns to support hiding comments by post owner/moderator
-- Place: src/main/resources/db/migration/ (Flyway)
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE research_comments
    ADD COLUMN IF NOT EXISTS is_hidden BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE research_comments
    ADD COLUMN IF NOT EXISTS hidden_at TIMESTAMPTZ NULL;

ALTER TABLE research_comments
    ADD COLUMN IF NOT EXISTS hidden_by_user_id UUID NULL REFERENCES users(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_rcomment_hidden ON research_comments (is_hidden);


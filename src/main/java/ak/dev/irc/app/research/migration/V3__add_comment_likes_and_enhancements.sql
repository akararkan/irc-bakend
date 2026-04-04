-- ─────────────────────────────────────────────────────────────────────────────
-- V3__add_comment_likes_and_enhancements.sql
-- Place in: src/main/resources/db/migration/
-- Flyway runs this automatically after V2.
-- ─────────────────────────────────────────────────────────────────────────────

-- 1. Comment likes junction table
--    Many users can like many comments. One like per user per comment (PK enforces).
CREATE TABLE IF NOT EXISTS research_comment_likes (
    comment_id  UUID        NOT NULL REFERENCES research_comments(id) ON DELETE CASCADE,
    user_id     UUID        NOT NULL REFERENCES users(id)             ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (comment_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_rcomment_likes_comment ON research_comment_likes (comment_id);
CREATE INDEX IF NOT EXISTS idx_rcomment_likes_user    ON research_comment_likes (user_id);

-- 2. Ensure citation_count column exists (may already be present from V1)
ALTER TABLE researches
    ADD COLUMN IF NOT EXISTS citation_count BIGINT NOT NULL DEFAULT 0;

-- 3. Ensure share_count column exists
ALTER TABLE researches
    ADD COLUMN IF NOT EXISTS share_count BIGINT NOT NULL DEFAULT 0;

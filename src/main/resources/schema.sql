-- ═══════════════════════════════════════════════════════════════════════════════
--  IRC Platform — Supplementary Schema
--  Runs after Hibernate DDL (spring.jpa.defer-datasource-initialization = true)
-- ═══════════════════════════════════════════════════════════════════════════════

-- ── 1. IRC sequential identifier for research publications ─────────────────────
CREATE SEQUENCE IF NOT EXISTS research_irc_seq
    START WITH 1
    INCREMENT BY 1
    NO MAXVALUE
    CACHE 1;

-- ── 2. Junction table for research comment likes ───────────────────────────────
--    (No JPA entity maps to this — accessed via native queries in
--     ResearchCommentRepository)
CREATE TABLE IF NOT EXISTS research_comment_likes (
    comment_id UUID    NOT NULL,
    user_id    UUID    NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),

    PRIMARY KEY (comment_id, user_id),

    CONSTRAINT fk_rclike_comment
        FOREIGN KEY (comment_id) REFERENCES research_comments (id) ON DELETE CASCADE,
    CONSTRAINT fk_rclike_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_rclike_comment ON research_comment_likes (comment_id);
CREATE INDEX IF NOT EXISTS idx_rclike_user    ON research_comment_likes (user_id);

-- ── 3. Full-text search support for researches (PostgreSQL tsvector + GIN) ─────
--    The column is a GENERATED ALWAYS STORED column — Postgres keeps it in
--    sync automatically whenever title, abstract_text, or keywords change.
--
--    NOTE: We use ADD COLUMN IF NOT EXISTS (Postgres 9.6+) instead of a
--    DO $$ PL/pgSQL block because Spring's ScriptUtils splits on ';' and
--    would break the $$ dollar-quoted block.

ALTER TABLE researches
    ADD COLUMN IF NOT EXISTS search_vector TSVECTOR
    GENERATED ALWAYS AS (
        setweight(to_tsvector('english', COALESCE(title, '')), 'A') ||
        setweight(to_tsvector('english', COALESCE(abstract_text, '')), 'B') ||
        setweight(to_tsvector('english', COALESCE(keywords, '')), 'C')
    ) STORED;

CREATE INDEX IF NOT EXISTS idx_research_search_vector
    ON researches USING GIN (search_vector);


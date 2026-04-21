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

-- ── 4. Q&A domain (independent questions and answers) ────────────────────────

CREATE TABLE IF NOT EXISTS questions (
    id UUID PRIMARY KEY,
    author_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title VARCHAR(500) NOT NULL,
    body TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    answer_count BIGINT NOT NULL DEFAULT 0,
    deleted_at TIMESTAMP NULL,

    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by UUID NULL,
    updated_by UUID NULL,
    created_by_ip VARCHAR(45) NULL,
    updated_by_ip VARCHAR(45) NULL,
    created_by_device VARCHAR(300) NULL,
    updated_by_device VARCHAR(300) NULL,
    last_action VARCHAR(30) NULL,
    action_note VARCHAR(500) NULL
);

CREATE INDEX IF NOT EXISTS idx_question_author ON questions (author_id);
CREATE INDEX IF NOT EXISTS idx_question_status ON questions (status);
CREATE INDEX IF NOT EXISTS idx_question_deleted ON questions (deleted_at);

CREATE TABLE IF NOT EXISTS question_answers (
    id UUID PRIMARY KEY,
    question_id UUID NOT NULL REFERENCES questions (id) ON DELETE CASCADE,
    author_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    body TEXT NOT NULL,
    is_accepted BOOLEAN NOT NULL DEFAULT FALSE,
    is_edited BOOLEAN NOT NULL DEFAULT FALSE,
    edited_at TIMESTAMP NULL,
    deleted_at TIMESTAMP NULL,

    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by UUID NULL,
    updated_by UUID NULL,
    created_by_ip VARCHAR(45) NULL,
    updated_by_ip VARCHAR(45) NULL,
    created_by_device VARCHAR(300) NULL,
    updated_by_device VARCHAR(300) NULL,
    last_action VARCHAR(30) NULL,
    action_note VARCHAR(500) NULL
);

CREATE INDEX IF NOT EXISTS idx_qanswer_question ON question_answers (question_id);
CREATE INDEX IF NOT EXISTS idx_qanswer_author ON question_answers (author_id);
CREATE INDEX IF NOT EXISTS idx_qanswer_deleted ON question_answers (deleted_at);

-- ── 5. Post comment lifecycle tracking ────────────────────────────────────

ALTER TABLE IF EXISTS post_comments
    ADD COLUMN IF NOT EXISTS is_edited BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE IF EXISTS post_comments
    ADD COLUMN IF NOT EXISTS edited_at TIMESTAMP NULL;

ALTER TABLE IF EXISTS post_comments
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP NULL;

CREATE INDEX IF NOT EXISTS idx_post_comment_deleted ON post_comments (is_deleted);
CREATE INDEX IF NOT EXISTS idx_post_comment_edited ON post_comments (is_edited);


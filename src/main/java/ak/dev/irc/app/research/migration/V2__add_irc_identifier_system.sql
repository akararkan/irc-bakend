-- ─────────────────────────────────────────────────────────────────────────────
-- V2__add_irc_identifier_system.sql
-- Place in: src/main/resources/db/migration/
-- Flyway runs this automatically on startup after V1.
-- ─────────────────────────────────────────────────────────────────────────────

-- 1. Global sequence — one counter for every research ever created.
--    Used to build IRC-YYYY-NNNNNN identifiers and DOIs.
--    Never reset. Start at 1.
CREATE SEQUENCE IF NOT EXISTS research_irc_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- 2. New columns on the researches table

-- Raw sequence number so we can rebuild the IRC ID if needed
ALTER TABLE researches
    ADD COLUMN IF NOT EXISTS irc_sequence_number BIGINT UNIQUE;

-- Human-readable IRC identifier: IRC-2026-000042
ALTER TABLE researches
    ADD COLUMN IF NOT EXISTS irc_id VARCHAR(30) UNIQUE;

-- HMAC-SHA256 tamper-proof authenticity hash (64 hex chars)
ALTER TABLE researches
    ADD COLUMN IF NOT EXISTS irc_verification_hash VARCHAR(64);

-- Public verification page URL
ALTER TABLE researches
    ADD COLUMN IF NOT EXISTS irc_verification_url TEXT;

-- Optimistic locking version counter
ALTER TABLE researches
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- 3. Populate existing rows — assign IRC IDs to any papers created before this migration
DO $$
DECLARE
    r   RECORD;
    seq BIGINT;
    yr  INT := EXTRACT(YEAR FROM NOW())::INT;
BEGIN
    FOR r IN SELECT id FROM researches WHERE irc_id IS NULL ORDER BY created_at
    LOOP
        seq := nextval('research_irc_seq');
        UPDATE researches
        SET
            irc_sequence_number = seq,
            irc_id = 'IRC-' || yr || '-' || LPAD(seq::TEXT, 6, '0')
        WHERE id = r.id;
    END LOOP;
END $$;

-- 4. Indexes

-- Fast IRC ID lookup (verification endpoint)
CREATE INDEX IF NOT EXISTS idx_research_irc_id      ON researches (irc_id);

-- Fast share token lookup
CREATE INDEX IF NOT EXISTS idx_research_share_token ON researches (share_token);

-- Partial composite index for the feed query:
-- Only indexes non-deleted published rows, sorted by publishedAt DESC.
-- The WHERE clause makes this a partial index — soft-deleted rows cost zero index space.
CREATE INDEX IF NOT EXISTS idx_research_feed
    ON researches (status, published_at DESC)
    WHERE deleted_at IS NULL;

-- 5. Full-text search using PostgreSQL GIN (much faster than LIKE '%q%')
--    The generated column is updated automatically whenever title/abstract/keywords change.
ALTER TABLE researches
    ADD COLUMN IF NOT EXISTS search_vector TSVECTOR
        GENERATED ALWAYS AS (
            to_tsvector('english',
                coalesce(title, '') || ' ' ||
                coalesce(abstract_text, '') || ' ' ||
                coalesce(keywords, '')
            )
        ) STORED;

CREATE INDEX IF NOT EXISTS idx_research_fts ON researches USING GIN (search_vector);

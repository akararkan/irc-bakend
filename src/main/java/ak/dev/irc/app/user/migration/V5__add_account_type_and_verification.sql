-- ─────────────────────────────────────────────────────────────────────────────
-- V5 — Account type, verification tier, scholar applications
-- Runs AFTER the application schema is created by Hibernate DDL-auto.
-- ─────────────────────────────────────────────────────────────────────────────

-- Add columns to users (Hibernate adds these via DDL-auto; this is the
-- reference/production Flyway script)
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS account_type        VARCHAR(30)  NOT NULL DEFAULT 'REGULAR',
    ADD COLUMN IF NOT EXISTS verification_tier   VARCHAR(30)  NOT NULL DEFAULT 'NONE',
    ADD COLUMN IF NOT EXISTS is_system_account   BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS orcid_id            VARCHAR(20),
    ADD COLUMN IF NOT EXISTS preferred_language  VARCHAR(5)   NOT NULL DEFAULT 'EN';

-- Expand role enum
ALTER TABLE users
    ALTER COLUMN role TYPE VARCHAR(20);
-- Existing SCHOLAR / RESEARCHER / ADMIN / SUPER_ADMIN rows are untouched.
-- New roles MODERATOR / EDITOR are assigned via application logic.

-- Create scholar verification applications table
CREATE TABLE IF NOT EXISTS scholar_verifications (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    claimed_tier   VARCHAR(30)  NOT NULL,
    affiliation    VARCHAR(200),
    evidence_urls  TEXT,
    orcid_id       VARCHAR(20),
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    reviewed_by    UUID         REFERENCES users(id),
    reviewer_note  TEXT,
    reviewed_at    TIMESTAMP,
    created_at     TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP    NOT NULL DEFAULT now(),
    created_by     UUID,
    updated_by     UUID,
    created_by_ip  VARCHAR(45),
    updated_by_ip  VARCHAR(45),
    created_by_device VARCHAR(300),
    updated_by_device VARCHAR(300),
    last_action    VARCHAR(30),
    action_note    VARCHAR(500)
);
CREATE INDEX IF NOT EXISTS idx_verification_user   ON scholar_verifications(user_id);
CREATE INDEX IF NOT EXISTS idx_verification_status ON scholar_verifications(status);

-- Seed the IRC platform official account (run only once)
INSERT INTO users (
    id, fname, lname, username, email, password, role,
    account_type, is_system_account, is_enabled,
    is_account_non_expired, is_account_non_locked, is_credentials_non_expired,
    email_notifications_enabled, email_social_enabled,
    email_mentions_enabled, email_system_enabled,
    created_at, updated_at
)
SELECT
    gen_random_uuid(),
    'Islamic', 'Research Center',
    'irc_official', 'platform@irc.internal',
    '$2a$12$DISABLED_ACCOUNT_CANNOT_LOGIN_xxxxxxxxxxxxxxxxxxxxxxxxxxx',
    'ADMIN', 'PLATFORM_OFFICIAL', TRUE, TRUE,
    TRUE, TRUE, TRUE, FALSE, FALSE, FALSE, TRUE,
    now(), now()
WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'irc_official');

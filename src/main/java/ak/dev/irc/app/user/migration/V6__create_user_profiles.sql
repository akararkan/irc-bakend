-- ─────────────────────────────────────────────────────────────────────────────
-- V6 — User profiles, topic specializations, FK migration for links/contacts
-- ─────────────────────────────────────────────────────────────────────────────

-- Create main profile table
CREATE TABLE IF NOT EXISTS user_profiles (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID         NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    display_name        VARCHAR(120),
    profile_bio         TEXT,
    self_describer      TEXT,
    location            VARCHAR(200),
    is_profile_locked   BOOLEAN      NOT NULL DEFAULT FALSE,
    avatar_url          TEXT,
    avatar_s3_key       TEXT,
    cover_image_url     TEXT,
    cover_image_s3_key  TEXT,
    academic_title      VARCHAR(150),
    institution_name    VARCHAR(200),
    madhhab_id          INT          REFERENCES madhhabs(id),
    website_url         TEXT,
    follower_count      BIGINT       NOT NULL DEFAULT 0,
    following_count     BIGINT       NOT NULL DEFAULT 0,
    research_count      INT          NOT NULL DEFAULT 0,
    fatwa_count         INT          NOT NULL DEFAULT 0,
    is_for_hire         BOOLEAN      NOT NULL DEFAULT FALSE,
    content_language    VARCHAR(5)   NOT NULL DEFAULT 'EN',
    profile_views       BIGINT       NOT NULL DEFAULT 0,
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
CREATE INDEX IF NOT EXISTS idx_profile_user    ON user_profiles(user_id);
CREATE INDEX IF NOT EXISTS idx_profile_madhhab ON user_profiles(madhhab_id);

-- Backfill profiles from existing users (migrate bio, location, avatar)
INSERT INTO user_profiles (user_id, profile_bio, self_describer, location, is_profile_locked, avatar_url, avatar_s3_key, display_name)
SELECT
    u.id,
    u.profile_bio,
    u.self_describer,
    u.location,
    u.is_profile_locked,
    u.profile_image,
    u.profile_image_s3_key,
    u.fname || ' ' || u.lname
FROM users u
WHERE NOT EXISTS (SELECT 1 FROM user_profiles p WHERE p.user_id = u.id);

-- Knowledge: madhhabs and topics (create if not already present)
CREATE TABLE IF NOT EXISTS madhhabs (
    id        SERIAL PRIMARY KEY,
    name_en   VARCHAR(100) NOT NULL,
    name_ar   VARCHAR(100) NOT NULL,
    name_ckb  VARCHAR(100) NOT NULL
);

CREATE TABLE IF NOT EXISTS topics (
    id        SERIAL PRIMARY KEY,
    name_en   VARCHAR(150) NOT NULL,
    name_ar   VARCHAR(150) NOT NULL,
    name_ckb  VARCHAR(150) NOT NULL
);

-- Topic specializations join table
CREATE TABLE IF NOT EXISTS user_topic_specializations (
    user_id       UUID  NOT NULL REFERENCES user_profiles(id) ON DELETE CASCADE,
    topic_id      INT   NOT NULL REFERENCES topics(id)        ON DELETE CASCADE,
    display_order INT   NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, topic_id)
);
CREATE INDEX IF NOT EXISTS idx_spec_topic ON user_topic_specializations(topic_id);

-- Migrate link/contact/attachment FKs from users → user_profiles
-- Step 1: add profile_id column
ALTER TABLE user_links       ADD COLUMN IF NOT EXISTS profile_id UUID REFERENCES user_profiles(id) ON DELETE CASCADE;
ALTER TABLE user_contacts    ADD COLUMN IF NOT EXISTS profile_id UUID REFERENCES user_profiles(id) ON DELETE CASCADE;
ALTER TABLE user_attachments ADD COLUMN IF NOT EXISTS profile_id UUID REFERENCES user_profiles(id) ON DELETE CASCADE;

-- Step 2: populate from the old user_id FK
UPDATE user_links ul       SET profile_id = (SELECT id FROM user_profiles WHERE user_id = ul.user_id) WHERE profile_id IS NULL;
UPDATE user_contacts uc    SET profile_id = (SELECT id FROM user_profiles WHERE user_id = uc.user_id) WHERE profile_id IS NULL;
UPDATE user_attachments ua SET profile_id = (SELECT id FROM user_profiles WHERE user_id = ua.user_id) WHERE profile_id IS NULL;

-- Step 3: drop the old user_id FK columns
-- (Only run this after verifying profile_id is fully populated)
-- ALTER TABLE user_links       DROP COLUMN user_id;
-- ALTER TABLE user_contacts    DROP COLUMN user_id;
-- ALTER TABLE user_attachments DROP COLUMN user_id;

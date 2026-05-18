-- ─────────────────────────────────────────────────────────────────────────────
-- V16__create_research_contributors.sql
-- Adds the named-contributor table for research publications.
-- A researcher can attach other researchers / scholars to a paper as
-- co-authors, advisors, translators, reviewers, editors, or generic
-- contributors. The "corresponding" researcher (the owner) is stored
-- on researches.researcher_id and is NEVER duplicated here.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS research_contributors (
    id                  UUID PRIMARY KEY,

    research_id         UUID NOT NULL,
    user_id             UUID NOT NULL,

    role                VARCHAR(30) NOT NULL DEFAULT 'CO_AUTHOR',
    display_order       INTEGER     NOT NULL DEFAULT 0,
    contribution_note   VARCHAR(500),

    -- Audit columns inherited from BaseAuditEntity
    created_at          TIMESTAMP   NOT NULL,
    updated_at          TIMESTAMP   NOT NULL,
    created_by          UUID,
    updated_by          UUID,
    created_by_ip       VARCHAR(45),
    updated_by_ip       VARCHAR(45),
    created_by_device   VARCHAR(300),
    updated_by_device   VARCHAR(300),
    last_action         VARCHAR(30),
    action_note         VARCHAR(500),

    CONSTRAINT fk_rcontrib_research
        FOREIGN KEY (research_id) REFERENCES researches (id)
        ON DELETE CASCADE,

    CONSTRAINT fk_rcontrib_user
        FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE CASCADE,

    CONSTRAINT uk_rcontrib_research_user
        UNIQUE (research_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_rcontrib_research
    ON research_contributors (research_id);

CREATE INDEX IF NOT EXISTS idx_rcontrib_user
    ON research_contributors (user_id);

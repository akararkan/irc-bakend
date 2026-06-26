-- ═══════════════════════════════════════════════════════════════════════════════
--  IRC PLATFORM — Cassandra schema for the POST package
--
--  Modelling philosophy:
--    Cassandra is query-first. Every table here exists to answer ONE specific
--    query in O(1) partition reads. Data is intentionally duplicated across
--    tables — Cassandra trades disk for read latency.
--
--  Hot rules:
--    • Always read by full partition key (or partition key + clustering prefix)
--    • Never use ALLOW FILTERING in production
--    • Counters live in dedicated tables — they can't share with regular cols
--    • Stories use TTL — Cassandra deletes them automatically at 24h
-- ═══════════════════════════════════════════════════════════════════════════════

CREATE KEYSPACE IF NOT EXISTS irc_keyspace
  WITH REPLICATION = { 'class': 'SimpleStrategy', 'replication_factor': 1 };

USE irc_keyspace;

-- ─────────────────────────────────────────────────────────────────────────────
-- 1.  POSTS — canonical record (lookup by post id)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS posts_by_id (
    id              uuid PRIMARY KEY,
    author_id       uuid,
    post_type       text,          -- TEXT / REEL / EMBEDDED / VOICE_POST / REPOST / STORY
    status          text,          -- DRAFT / PUBLISHED / ARCHIVED / REMOVED
    visibility      text,          -- PUBLIC / FOLLOWERS_ONLY / ONLY_ME
    text_content    text,
    audio_track_url text,
    audio_track_name text,
    location_name   text,
    location_lat    double,
    location_lng    double,
    shared_post_id  uuid,
    share_link      text,
    media_urls      list<text>,    -- denormalized media S3 URLs (small list)
    media_types     list<text>,
    created_at      timestamp,
    updated_at      timestamp
);

-- ─────────────────────────────────────────────────────────────────────────────
-- 2.  POSTS BY AUTHOR — profile feed (newest first)
--     Query: "give me page N of @user's posts"
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS posts_by_author (
    author_id   uuid,
    created_at  timestamp,
    post_id     uuid,
    post_type   text,
    visibility  text,
    text_preview text,             -- first 280 chars for list rendering
    media_url   text,              -- single cover image for the list view
    PRIMARY KEY (author_id, created_at, post_id)
) WITH CLUSTERING ORDER BY (created_at DESC, post_id ASC);

-- ─────────────────────────────────────────────────────────────────────────────
-- 3.  HOME FEED — fanout-on-write (one row per viewer per post)
--     Query: "give me page N of @user's home feed"
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS feed_by_user (
    user_id     uuid,              -- viewer
    created_at  timestamp,         -- ts of underlying post
    post_id     uuid,
    author_id   uuid,
    post_type   text,
    text_preview text,
    media_url   text,
    PRIMARY KEY (user_id, created_at, post_id)
) WITH CLUSTERING ORDER BY (created_at DESC, post_id ASC)
  AND default_time_to_live = 2592000;  -- 30 days; older posts re-hydrate on demand

-- ─────────────────────────────────────────────────────────────────────────────
-- 4.  REELS GLOBAL FEED — bucketed by day to bound partition size
--     Query: "give me reels for date X, newest first"
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS reels_by_day (
    day_bucket  text,              -- 'YYYY-MM-DD' UTC
    created_at  timestamp,
    post_id     uuid,
    author_id   uuid,
    text_preview text,
    media_url   text,
    PRIMARY KEY (day_bucket, created_at, post_id)
) WITH CLUSTERING ORDER BY (created_at DESC, post_id ASC);

-- ─────────────────────────────────────────────────────────────────────────────
-- 5.  COMMENTS — top-level comments per post (chronological)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS comments_by_post (
    post_id     uuid,
    created_at  timestamp,
    comment_id  uuid,
    author_id   uuid,
    text_content text,
    media_url   text,
    media_type  text,
    is_deleted  boolean,
    is_edited   boolean,
    PRIMARY KEY (post_id, created_at, comment_id)
) WITH CLUSTERING ORDER BY (created_at ASC, comment_id ASC);

-- 5a. Comment lookup — point-read by comment_id alone.
--     Required because every other table keys comments by their PARTITION
--     (post_id for top-levels, parent_id for replies), so an edit or delete
--     given only a comment_id has nowhere to start without this.
CREATE TABLE IF NOT EXISTS comment_lookup (
    comment_id  uuid PRIMARY KEY,
    post_id     uuid,
    parent_id   uuid,         -- null = top-level; non-null = reply (parent is a top-level comment)
    author_id   uuid,
    created_at  timestamp,
    is_reply    boolean
);

-- 5b. Comment replies (replies are flat at 1 level per project rule)
CREATE TABLE IF NOT EXISTS replies_by_comment (
    parent_id   uuid,
    created_at  timestamp,
    reply_id    uuid,
    author_id   uuid,
    post_id     uuid,
    text_content text,
    media_url   text,
    is_deleted  boolean,
    is_edited   boolean,
    PRIMARY KEY (parent_id, created_at, reply_id)
) WITH CLUSTERING ORDER BY (created_at ASC, reply_id ASC);

-- ─────────────────────────────────────────────────────────────────────────────
-- 6.  REACTIONS — only one type (LIKE) per project rule
--
--   Two tables for two query patterns:
--     (a) "Did user U react to post P?"  → reactions_by_post (PK post_id, CK user_id)
--     (b) "What did user U recently like?" → reactions_by_user (PK user_id, CK ts)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS reactions_by_post (
    post_id     uuid,
    user_id     uuid,
    created_at  timestamp,
    PRIMARY KEY (post_id, user_id)
);

CREATE TABLE IF NOT EXISTS reactions_by_user (
    user_id     uuid,
    created_at  timestamp,
    post_id     uuid,
    PRIMARY KEY (user_id, created_at, post_id)
) WITH CLUSTERING ORDER BY (created_at DESC, post_id ASC);

-- Comment reactions follow the same shape.
CREATE TABLE IF NOT EXISTS comment_reactions_by_comment (
    comment_id  uuid,
    user_id     uuid,
    created_at  timestamp,
    PRIMARY KEY (comment_id, user_id)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- 7.  COUNTERS — one row per post, increment-only writes
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS post_counters (
    post_id        uuid PRIMARY KEY,
    reaction_count counter,
    comment_count  counter,
    share_count    counter,
    view_count     counter,
    save_count     counter
);

CREATE TABLE IF NOT EXISTS comment_counters (
    comment_id     uuid PRIMARY KEY,
    reaction_count counter,
    reply_count    counter
);

-- ─────────────────────────────────────────────────────────────────────────────
-- 8.  VIEWS — unique-viewer set per post; counter handled separately
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS views_by_post (
    post_id        uuid,
    user_id        uuid,
    first_viewed_at timestamp,
    PRIMARY KEY (post_id, user_id)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- 8b. CLOSE FRIENDS — author's curated viewer list for CLOSE_FRIENDS stories
--     Query (A): list my close friends            (point partition scan)
--     Query (B): is U in A's close friends?       (point read on cluster key)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS close_friends_by_owner (
    owner_id   uuid,
    friend_id  uuid,
    added_at   timestamp,
    PRIMARY KEY (owner_id, friend_id)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- 9.  STORIES — author timeline, auto-expire in 24h via TTL
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS stories_by_author (
    author_id   uuid,
    created_at  timestamp,
    story_id    uuid,
    story_type  text,
    visibility  text,
    media_url   text,
    thumbnail_url text,
    text_content text,
    expires_at  timestamp,
    PRIMARY KEY (author_id, created_at, story_id)
) WITH CLUSTERING ORDER BY (created_at DESC, story_id ASC)
  AND default_time_to_live = 86400;  -- 24h

-- 9b. Story point-lookup — needed to DELETE a story by story_id alone,
--     since stories_by_author requires (author_id, created_at, story_id) as PK.
--     Inherits the 24h TTL from the parent table so cleanup is automatic.
CREATE TABLE IF NOT EXISTS story_lookup (
    story_id    uuid PRIMARY KEY,
    author_id   uuid,
    created_at  timestamp,
    visibility  text,
    expires_at  timestamp
) WITH default_time_to_live = 86400;

-- 9c. Story HIGHLIGHTS — permanent (no TTL) curated archives of stories.
--     Stories normally expire after 24h. Adding one to a highlight copies
--     a snapshot here so it survives forever. Order is user-controlled.
CREATE TABLE IF NOT EXISTS highlights_by_author (
    author_id     uuid,
    display_order int,
    highlight_id  uuid,
    title         text,
    cover_url     text,
    created_at    timestamp,
    PRIMARY KEY (author_id, display_order, highlight_id)
) WITH CLUSTERING ORDER BY (display_order ASC, highlight_id ASC);

-- Stories inside a highlight. created_at is the ORIGINAL story timestamp so
-- the highlight reads chronologically. Story content is denormalized here
-- so the original story_id in stories_by_author can vanish at 24h.
CREATE TABLE IF NOT EXISTS stories_in_highlight (
    highlight_id  uuid,
    created_at    timestamp,
    story_id      uuid,
    author_id     uuid,
    story_type    text,
    media_url     text,
    thumbnail_url text,
    text_content  text,
    PRIMARY KEY (highlight_id, created_at, story_id)
) WITH CLUSTERING ORDER BY (created_at ASC, story_id ASC);

CREATE TABLE IF NOT EXISTS story_views_by_story (
    story_id    uuid,
    viewed_at   timestamp,
    viewer_id   uuid,
    PRIMARY KEY (story_id, viewed_at, viewer_id)
) WITH CLUSTERING ORDER BY (viewed_at DESC, viewer_id ASC)
  AND default_time_to_live = 86400;

-- ─────────────────────────────────────────────────────────────────────────────
-- 10. SAVES — user's saved-posts list (collections supported by collection_name)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS saves_by_user (
    user_id        uuid,
    created_at     timestamp,
    post_id        uuid,
    collection_name text,
    PRIMARY KEY (user_id, created_at, post_id)
) WITH CLUSTERING ORDER BY (created_at DESC, post_id ASC);

-- "Did user U save post P?" — answered without scanning the user's full save list.
-- created_at + collection_name are duplicated here so the unsave path has the
-- cluster key it needs to remove the matching saves_by_user row in one shot
-- (Cassandra deletes require the full primary key).
CREATE TABLE IF NOT EXISTS saves_by_post_user (
    post_id          uuid,
    user_id          uuid,
    created_at       timestamp,
    collection_name  text,
    PRIMARY KEY (post_id, user_id)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- 11. SHARES (repost ledger)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS shares_by_post (
    post_id     uuid,
    created_at  timestamp,
    share_id    uuid,
    sharer_id   uuid,
    caption     text,
    PRIMARY KEY (post_id, created_at, share_id)
) WITH CLUSTERING ORDER BY (created_at DESC, share_id ASC);

-- ─────────────────────────────────────────────────────────────────────────────
-- 11a. POST MEDIA — image grids / carousels per post (Instagram-style).
--      Separate from posts_by_id.media_urls (which is the small "preview list")
--      so we can add/remove/reorder individual media items without rewriting
--      the whole post row.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS media_by_post (
    post_id            uuid,
    sort_order         int,
    media_id           uuid,
    media_type         text,     -- IMAGE / VIDEO / AUDIO_TRACK / DOCUMENT
    url                text,
    thumbnail_url      text,
    s3_key             text,
    duration_seconds   int,
    file_size_bytes    bigint,
    mime_type          text,
    alt_text           text,
    PRIMARY KEY (post_id, sort_order, media_id)
) WITH CLUSTERING ORDER BY (sort_order ASC, media_id ASC);

-- ─────────────────────────────────────────────────────────────────────────────
-- 11b. SOUND LIBRARY — TikTok-style reusable audio across reels/posts/stories.
--
--   Query patterns:
--     (A) canonical lookup by sound_id
--     (B) browse library by category (newest first)
--     (C) "show me all posts using this sound"
--     (D) trending: derived from sound_counters.use_count (denormalized by jobs)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS sounds_by_id (
    id              uuid PRIMARY KEY,
    title           text,
    artist_name     text,
    audio_url       text,
    cover_art_url   text,
    duration_seconds int,
    category        text,    -- NASHEED / QURAN_RECITATION / LECTURE_CLIP / NATURE / ORIGINAL / PLATFORM_MUSIC
    status          text,    -- PENDING_REVIEW / APPROVED / REJECTED / ARCHIVED
    uploader_id     uuid,
    created_at      timestamp,
    updated_at      timestamp
);

CREATE TABLE IF NOT EXISTS sounds_by_category (
    category    text,
    created_at  timestamp,
    sound_id    uuid,
    title       text,
    artist_name text,
    audio_url   text,
    cover_art_url text,
    duration_seconds int,
    PRIMARY KEY (category, created_at, sound_id)
) WITH CLUSTERING ORDER BY (created_at DESC, sound_id ASC);

CREATE TABLE IF NOT EXISTS posts_by_sound (
    sound_id    uuid,
    created_at  timestamp,
    post_id     uuid,
    author_id   uuid,
    PRIMARY KEY (sound_id, created_at, post_id)
) WITH CLUSTERING ORDER BY (created_at DESC, post_id ASC);

CREATE TABLE IF NOT EXISTS sound_counters (
    sound_id  uuid PRIMARY KEY,
    use_count counter
);

-- ─────────────────────────────────────────────────────────────────────────────
-- 11c. STORY POLLS — two-option polls attached to a single story.
--      Inherits the parent story's 24h TTL so polls vanish with their story.
--
--   Query patterns:
--     (A) get the poll for a story_id
--     (B) "has user U voted yet?"
--     (C) "what did user U vote?"  (lives on the same lookup row)
--     (D) authors-only: list voters for choice A / choice B
--     (E) running tallies via poll_counters
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS poll_by_story (
    story_id    uuid PRIMARY KEY,
    poll_id     uuid,
    question    text,
    option_a    text,
    option_b    text,
    author_id   uuid,
    created_at  timestamp
) WITH default_time_to_live = 86400;

CREATE TABLE IF NOT EXISTS poll_votes_by_poll_user (
    poll_id   uuid,
    voter_id  uuid,
    choice    text,            -- 'A' or 'B'
    voted_at  timestamp,
    PRIMARY KEY (poll_id, voter_id)
) WITH default_time_to_live = 86400;

CREATE TABLE IF NOT EXISTS poll_voters_by_choice (
    poll_id   uuid,
    choice    text,
    voted_at  timestamp,
    voter_id  uuid,
    PRIMARY KEY ((poll_id, choice), voted_at, voter_id)
) WITH CLUSTERING ORDER BY (voted_at DESC, voter_id ASC)
  AND default_time_to_live = 86400;

CREATE TABLE IF NOT EXISTS poll_counters (
    poll_id     uuid PRIMARY KEY,
    vote_a      counter,
    vote_b      counter
);

-- ─────────────────────────────────────────────────────────────────────────────
-- 11d. HASHTAGS + MENTIONS — entity-extracted from post text at write time.
--
--   Queries:
--     (A) "posts tagged #foo"        → partition scan on posts_by_hashtag
--     (B) "posts that mention @me"   → partition scan on mentions_by_user
--     (C) "how popular is #foo?"     → hashtag_counters point read
--     (D) trending hashtags          → derived by background job from counters
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS posts_by_hashtag (
    hashtag     text,
    created_at  timestamp,
    post_id     uuid,
    author_id   uuid,
    text_preview text,
    media_url   text,
    PRIMARY KEY (hashtag, created_at, post_id)
) WITH CLUSTERING ORDER BY (created_at DESC, post_id ASC);

CREATE TABLE IF NOT EXISTS hashtag_counters (
    hashtag    text PRIMARY KEY,
    post_count counter
);

CREATE TABLE IF NOT EXISTS mentions_by_user (
    mentioned_user_id  uuid,
    created_at         timestamp,
    post_id            uuid,
    author_id          uuid,
    text_preview       text,
    PRIMARY KEY (mentioned_user_id, created_at, post_id)
) WITH CLUSTERING ORDER BY (created_at DESC, post_id ASC);

-- ─────────────────────────────────────────────────────────────────────────────
-- 11e. NOTIFICATIONS — per-user inbox, TTL'd 90 days.
--
--   Realtime delivery is Redis pub/sub on irc:notifications:{userId}
--   (SSE subscribers pick it up). This table is the durable store so a user
--   that re-opens the app sees everything in the last 90 days.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS notifications_by_user (
    user_id           uuid,
    created_at        timestamp,
    notification_id   uuid,
    type              text,
    title             text,
    body              text,
    actor_id          uuid,
    last_actor_id     uuid,        -- coalesced: the most recent actor in an aggregate
    aggregate_count   bigint,      -- 1 for singletons; N for "X and N-1 others"
    resource_type     text,
    resource_id       uuid,
    group_key         text,
    is_read           boolean,
    PRIMARY KEY (user_id, created_at, notification_id)
) WITH CLUSTERING ORDER BY (created_at DESC, notification_id ASC)
  AND default_time_to_live = 7776000;     -- 90d

-- Active-group lookup for aggregation.
-- (user_id, group_key) → the live notification we should coalesce into.
-- TTL 60 min: after the window expires, the next same-group event starts a fresh row.
CREATE TABLE IF NOT EXISTS notif_active_group_by_user (
    user_id          uuid,
    group_key        text,
    notification_id  uuid,
    created_at       timestamp,
    PRIMARY KEY (user_id, group_key)
) WITH default_time_to_live = 3600;

-- Point-lookup so "mark read" doesn't need a partition scan.
CREATE TABLE IF NOT EXISTS notification_lookup (
    notification_id  uuid PRIMARY KEY,
    user_id          uuid,
    created_at       timestamp
) WITH default_time_to_live = 7776000;

-- Unread-count tally per user — bumped on deliver, decremented on mark-read.
CREATE TABLE IF NOT EXISTS notification_unread_counter (
    user_id  uuid PRIMARY KEY,
    unread   counter
);

-- ─────────────────────────────────────────────────────────────────────────────
-- 13. AUDIT LOG — append-only compliance trail. Admin reads only.
--     180-day TTL inherited from the previous Postgres retention policy.
--
--     Read patterns:
--       (A) "What did user U do?"          → audit_log_by_user
--       (B) "What happened to resource R?" → audit_log_by_resource
--     Multi-filter flex search is intentionally out of scope here — admins
--     scope to one of these two pivots, and the SSE stream handles realtime.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS audit_log_by_user (
    user_id       uuid,
    created_at    timestamp,
    audit_id      uuid,
    username      text,
    operation     text,
    outcome       text,
    resource_type text,
    resource_id   uuid,
    http_method   text,
    path          text,
    query_string  text,
    status_code   int,
    duration_ms   bigint,
    ip_address    text,
    user_agent    text,
    summary       text,
    error_code    text,
    PRIMARY KEY (user_id, created_at, audit_id)
) WITH CLUSTERING ORDER BY (created_at DESC, audit_id ASC)
  AND default_time_to_live = 15552000;     -- 180d

CREATE TABLE IF NOT EXISTS audit_log_by_resource (
    resource_type text,
    resource_id   uuid,
    created_at    timestamp,
    audit_id      uuid,
    user_id       uuid,
    username      text,
    operation     text,
    outcome       text,
    http_method   text,
    path          text,
    query_string  text,
    status_code   int,
    duration_ms   bigint,
    ip_address    text,
    user_agent    text,
    summary       text,
    error_code    text,
    PRIMARY KEY ((resource_type, resource_id), created_at, audit_id)
) WITH CLUSTERING ORDER BY (created_at DESC, audit_id ASC)
  AND default_time_to_live = 15552000;

-- ─────────────────────────────────────────────────────────────────────────────
-- 14. USER ACTIVITY — engagement history feed
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS activity_by_user (
    user_id             uuid,
    created_at          timestamp,
    activity_id         uuid,
    activity_type       text,
    post_id             uuid,
    comment_id          uuid,
    reaction_type       text,
    watched_seconds     int,
    query               text,
    search_scope        text,
    hit_count           int,
    target_user_id      uuid,
    question_id         uuid,
    answer_id           uuid,
    qna_reaction_type   text,
    research_id         uuid,
    research_comment_id uuid,
    PRIMARY KEY (user_id, created_at, activity_id)
) WITH CLUSTERING ORDER BY (created_at DESC, activity_id ASC);

-- Same data, partitioned by (user_id, activity_type) so the type-filter
-- ("show me just my reactions") is also a single partition scan.
CREATE TABLE IF NOT EXISTS activity_by_user_and_type (
    user_id             uuid,
    activity_type       text,
    created_at          timestamp,
    activity_id         uuid,
    post_id             uuid,
    comment_id          uuid,
    reaction_type       text,
    watched_seconds     int,
    query               text,
    search_scope        text,
    hit_count           int,
    target_user_id      uuid,
    question_id         uuid,
    answer_id           uuid,
    qna_reaction_type   text,
    research_id         uuid,
    research_comment_id uuid,
    PRIMARY KEY ((user_id, activity_type), created_at, activity_id)
) WITH CLUSTERING ORDER BY (created_at DESC, activity_id ASC);

-- Point-lookup by activity_id so deleteOne can find the full PK without
-- scanning a (potentially huge) user partition.
CREATE TABLE IF NOT EXISTS activity_lookup (
    activity_id   uuid PRIMARY KEY,
    user_id       uuid,
    activity_type text,
    created_at    timestamp
);

-- ─────────────────────────────────────────────────────────────────────────────
-- 15. REEL VIEWS — per-watch analytics (durable history of every play)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS reel_views_by_user (
    user_id         uuid,
    created_at      timestamp,
    reel_view_id    uuid,
    post_id         uuid,
    watched_seconds int,
    PRIMARY KEY (user_id, created_at, reel_view_id)
) WITH CLUSTERING ORDER BY (created_at DESC, reel_view_id ASC);

-- ─────────────────────────────────────────────────────────────────────────────
-- 16. Q&A ENGAGEMENT — reactions / views / saves on questions and answers
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS qna_reactions_by_answer (
    answer_id     uuid,
    user_id       uuid,
    reaction_type text,
    created_at    timestamp,
    PRIMARY KEY (answer_id, user_id)
);

CREATE TABLE IF NOT EXISTS qna_reactions_by_user (
    user_id       uuid,
    created_at    timestamp,
    answer_id     uuid,
    reaction_type text,
    PRIMARY KEY (user_id, created_at, answer_id)
) WITH CLUSTERING ORDER BY (created_at DESC, answer_id ASC);

CREATE TABLE IF NOT EXISTS question_views_by_question (
    question_id     uuid,
    user_id         uuid,
    first_viewed_at timestamp,
    PRIMARY KEY (question_id, user_id)
);

CREATE TABLE IF NOT EXISTS question_saves_by_user (
    user_id         uuid,
    created_at      timestamp,
    question_id     uuid,
    collection_name text,
    PRIMARY KEY (user_id, created_at, question_id)
) WITH CLUSTERING ORDER BY (created_at DESC, question_id ASC);

CREATE TABLE IF NOT EXISTS question_saves_lookup (
    question_id uuid,
    user_id     uuid,
    created_at  timestamp,
    PRIMARY KEY (question_id, user_id)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- 17. RESEARCH ENGAGEMENT — reactions / views / downloads / saves
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS research_reactions_by_research (
    research_id   uuid,
    user_id       uuid,
    reaction_type text,
    created_at    timestamp,
    PRIMARY KEY (research_id, user_id)
);

CREATE TABLE IF NOT EXISTS research_reactions_by_user (
    user_id     uuid,
    created_at  timestamp,
    research_id uuid,
    PRIMARY KEY (user_id, created_at, research_id)
) WITH CLUSTERING ORDER BY (created_at DESC, research_id ASC);

CREATE TABLE IF NOT EXISTS research_views_by_research (
    research_id     uuid,
    user_id         uuid,
    first_viewed_at timestamp,
    PRIMARY KEY (research_id, user_id)
);

CREATE TABLE IF NOT EXISTS research_downloads_by_research (
    research_id uuid,
    created_at  timestamp,
    download_id uuid,
    user_id     uuid,
    media_id    uuid,
    ip_address  text,
    PRIMARY KEY (research_id, created_at, download_id)
) WITH CLUSTERING ORDER BY (created_at DESC, download_id ASC);

CREATE TABLE IF NOT EXISTS research_saves_by_user (
    user_id         uuid,
    created_at      timestamp,
    research_id     uuid,
    collection_name text,
    PRIMARY KEY (user_id, created_at, research_id)
) WITH CLUSTERING ORDER BY (created_at DESC, research_id ASC);

CREATE TABLE IF NOT EXISTS research_saves_lookup (
    research_id uuid,
    user_id     uuid,
    created_at  timestamp,
    PRIMARY KEY (research_id, user_id)
);

CREATE TABLE IF NOT EXISTS research_comment_likes_by_comment (
    comment_id uuid,
    user_id    uuid,
    created_at timestamp,
    PRIMARY KEY (comment_id, user_id)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- 18. FRIEND SUGGESTIONS — precomputed by background job; read-cheap.
--     "score" is the # of mutual follows; higher = better suggestion.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS friend_suggestions_by_user (
    user_id      uuid,
    score        int,
    candidate_id uuid,
    reason       text,            -- e.g. "5 mutual follows"
    computed_at  timestamp,
    PRIMARY KEY (user_id, score, candidate_id)
) WITH CLUSTERING ORDER BY (score DESC, candidate_id ASC);

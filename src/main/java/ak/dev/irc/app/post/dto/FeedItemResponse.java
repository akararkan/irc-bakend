package ak.dev.irc.app.post.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model for one entry in a profile / home feed / reels list.
 * Carries the author summary inline so the UI never shows
 * "unknown user". Primary identifier is {@code id} to match
 * {@code PostResponse} and the {@code entity.id} JS convention.
 *
 * <p><b>{@code entityType}</b> is the top-level discriminator —
 * {@code POST | RESEARCH | QUESTION | CHANNEL_POST}. The frontend reads
 * it FIRST and dispatches to the right detail endpoint
 * ({@code /api/v1/posts/{id}}, {@code /api/v1/researches/{id}},
 * {@code /api/v1/questions/{id}}, {@code /api/v1/channels/{channel.id}}).
 * {@code postType} is a sub-flavour inside POST (POST / REEL / SHARE)
 * and is ignored for non-POST entries — for RESEARCH/QUESTION it carries
 * a label like "PUBLICATION" or "QUESTION" purely for UI badge convenience.</p>
 *
 * <p>Counter fields (reactionCount … shareCount) are zero for
 * RESEARCH / QUESTION entries — their counters are NOT bulk-hydrated at
 * feed read time (would create cross-domain coupling); the frontend pulls
 * them from the entity's own detail endpoint on click-through. For
 * CHANNEL_POST entries the counters are remapped: {@code viewCount} =
 * channel-post views, {@code shareCount} = forwards, {@code commentCount}
 * = discussion comments.</p>
 *
 * <p><b>Ranking fields</b> (nullable — populated only by the ranked home
 * feed): {@code source} says why the item is here
 * ({@code FOLLOWING | SELF | CHANNEL | EXPLORE}) so the UI can label
 * "Suggested for you" / channel cards; {@code rankScore} is the final
 * ranking score (debug/telemetry — ordering is already applied);
 * {@code channel} + {@code channelPostId} are set only when
 * {@code entityType == CHANNEL_POST} ({@code author} is null there —
 * channel posts are signed by the channel, Telegram-style, and
 * {@code channelPostId} is the message snowflake as a string because the
 * synthetic {@code id} UUID only exists for client-side keying).</p>
 */
public record FeedItemResponse(
        UUID    id,
        UUID    authorId,
        AuthorSummary author,
        String  entityType,
        String  postType,
        String  textPreview,
        String  mediaUrl,
        String  videoUrl,   // REEL only — first VIDEO-type URL from posts_by_id; null for all other types
        // ── Live counters from post_counters (POST entries only; 0 for research/qna) ──
        long    reactionCount,
        long    commentCount,
        long    viewCount,
        long    saveCount,
        long    shareCount,
        boolean likedByMe,
        boolean savedByMe,
        Instant createdAt,
        // ── Ranked-feed additions (null on non-ranked paths — additive, mapper-safe) ──
        String  source,
        Double  rankScore,
        ChannelSummary channel,
        String  channelPostId
) {

    /**
     * Legacy-shape constructor — every pre-ranking construction site
     * (hydrator, profile feed, reels) builds through this and gets null
     * ranking fields. Keeps the record additive for JSON consumers.
     */
    public FeedItemResponse(UUID id, UUID authorId, AuthorSummary author,
                            String entityType, String postType,
                            String textPreview, String mediaUrl, String videoUrl,
                            long reactionCount, long commentCount, long viewCount,
                            long saveCount, long shareCount,
                            boolean likedByMe, boolean savedByMe, Instant createdAt) {
        this(id, authorId, author, entityType, postType, textPreview, mediaUrl, videoUrl,
             reactionCount, commentCount, viewCount, saveCount, shareCount,
             likedByMe, savedByMe, createdAt,
             null, null, null, null);
    }

    /** Copy with ranking metadata attached (records are immutable). */
    public FeedItemResponse withRanking(String source, double rankScore) {
        return new FeedItemResponse(id, authorId, author, entityType, postType,
                textPreview, mediaUrl, videoUrl,
                reactionCount, commentCount, viewCount, saveCount, shareCount,
                likedByMe, savedByMe, createdAt,
                source, rankScore, channel, channelPostId);
    }
}

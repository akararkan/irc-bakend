package ak.dev.irc.app.research.dto.response;

import ak.dev.irc.app.research.enums.ResearchStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Lightweight projection used in feeds, search results, and cards.
 */
public record ResearchSummaryResponse(
    UUID id,
    String slug,

    /** e.g. IRC-2026-000042 — shown on cards so readers can cite the paper */
    String ircId,

    String title,
    String abstractText,
    String coverImageUrl,
    String videoPromoThumbnailUrl,

    UUID researcherId,
    String researcherFullName,
    String researcherUsername,
    String researcherProfileImage,

    ResearchStatus status,
    LocalDateTime publishedAt,

    Long viewCount,
    Long reactionCount,
    Long commentCount,
    Long downloadCount,
    Long saveCount,
    Long shareCount,
    Long citationCount,

    List<String> tags,

    /** Full public share URL e.g. https://irc.example.com/r/{shareToken} */
    String shareUrl,

    boolean currentUserReacted,
    boolean currentUserSaved
) {}

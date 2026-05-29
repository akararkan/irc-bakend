package ak.dev.irc.app.research.dto.response;

import ak.dev.irc.app.common.text.BodyFormat;
import ak.dev.irc.app.research.enums.ResearchStatus;
import ak.dev.irc.app.research.enums.ResearchVisibility;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record ResearchResponse(

    UUID id,
    String slug,

    // ── IRC Official Identifier ───────────────────────────────────────────────
    /** e.g. IRC-2026-000042 — the official paper identifier issued by IRC */
    String ircId,

    // ── Author info ──────────────────────────────────────────────────────────
    UUID researcherId,
    String researcherFullName,
    String researcherUsername,
    String researcherProfileImage,

    // ── Core ─────────────────────────────────────────────────────────────────
    String title,
    /** Author-supplied body source (Markdown / HTML / plain — see {@link #bodyFormat}). Use for the editor. */
    String description,
    /** Pre-rendered, OWASP-sanitised HTML of the body — safe for direct DOM injection. Use for read views. */
    String descriptionHtml,
    /** Author-supplied abstract source. Use for the editor. */
    String abstractText,
    /** Pre-rendered, sanitised HTML of the abstract — safe for direct DOM injection. */
    String abstractHtml,
    /** How {@link #description} / {@link #abstractText} should be parsed. Null on legacy rows (treat as PLAIN). */
    BodyFormat bodyFormat,
    String keywords,
    String citation,

    // ── Video promo ──────────────────────────────────────────────────────────
    String videoPromoUrl,
    Integer videoPromoDurationSeconds,
    String videoPromoThumbnailUrl,

    // ── Cover image ──────────────────────────────────────────────────────────
    String coverImageUrl,

    // ── Lifecycle ────────────────────────────────────────────────────────────
    ResearchStatus status,
    ResearchVisibility visibility,
    LocalDateTime scheduledPublishAt,
    LocalDateTime publishedAt,

    // ── Counters ─────────────────────────────────────────────────────────────
    Long viewCount,
    Long downloadCount,
    Long reactionCount,
    Long commentCount,
    Long saveCount,
    Long shareCount,
    Long citationCount,

    // ── Toggles ──────────────────────────────────────────────────────────────
    boolean commentsEnabled,
    boolean downloadsEnabled,

    // ── Share ─────────────────────────────────────────────────────────────────
    String shareToken,
    /** Full public share URL e.g. https://irc.example.com/r/{shareToken} */
    String shareUrl,

    // ── Children ─────────────────────────────────────────────────────────────
    List<String> tags,
    List<MediaResponse> mediaFiles,
    List<SourceResponse> sources,
    List<ContributorResponse> contributors,

    // ── Current user context (populated per request) ─────────────────────────
    boolean currentUserReacted,
    String currentUserReactionType,
    boolean currentUserSaved,

    // ── Audit ────────────────────────────────────────────────────────────────
    LocalDateTime createdAt,
    LocalDateTime updatedAt,

    // ── Display-friendly timestamps ──────────────────────────────────────
    String timeAgo,
    String formattedDate
) {}

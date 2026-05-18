package ak.dev.irc.app.research.mapper;

import ak.dev.irc.app.common.cache.CounterCache;
import ak.dev.irc.app.common.util.TimeDisplayUtil;
import ak.dev.irc.app.research.dto.response.*;
import ak.dev.irc.app.research.entity.*;
import ak.dev.irc.app.research.enums.ReactionType;
import ak.dev.irc.app.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ResearchMapper {

    @Value("${irc.base-url:https://irc.example.com}")
    private String baseUrl;

    private final CounterCache counterCache;

    private static long nz(Long v) { return v == null ? 0L : v; }

    /** Pulls all seven research counters from Redis with DB fallback in one call. */
    private long[] resolveCounters(Research r) {
        var k = CounterCache.Kind.RESEARCH;
        UUID id = r.getId();
        return new long[] {
                counterCache.getOr(k, id, CounterCache.F_VIEWS,      () -> nz(r.getViewCount())),
                counterCache.getOr(k, id, CounterCache.F_DOWNLOADS,  () -> nz(r.getDownloadCount())),
                counterCache.getOr(k, id, CounterCache.F_REACTIONS,  () -> nz(r.getReactionCount())),
                counterCache.getOr(k, id, CounterCache.F_COMMENTS,   () -> nz(r.getCommentCount())),
                counterCache.getOr(k, id, CounterCache.F_SAVES,      () -> nz(r.getSaveCount())),
                counterCache.getOr(k, id, CounterCache.F_SHARES,     () -> nz(r.getShareCount())),
                counterCache.getOr(k, id, CounterCache.F_CITATIONS,  () -> nz(r.getCitationCount())),
        };
    }

    // ── Full detail ──────────────────────────────────────────────────────────

    public ResearchResponse toResponse(Research r, UUID currentUserId) {
        return toResponse(r, currentUserId, null);
    }

    /**
     * Block-aware overload that takes a pre-resolved {@code isSavedOverride} so
     * listing endpoints can batch the save lookup instead of streaming
     * {@code r.getSaves()} (which lazy-loads every save row on every card).
     * Pass {@code null} to fall back to the lazy-collection scan.
     */
    public ResearchResponse toResponse(Research r, UUID currentUserId, Boolean isSavedOverride) {
        return toResponse(r, currentUserId, isSavedOverride, null);
    }

    /**
     * Full overload: caller supplies both {@code isSavedOverride} and
     * {@code reactedOverride}. Either can be null to fall back to the lazy
     * collection scan, but both should be supplied on hot feed paths.
     */
    public ResearchResponse toResponse(Research r, UUID currentUserId,
                                       Boolean isSavedOverride, Boolean reactedOverride) {
        User author = r.getResearcher();
        long[] c = resolveCounters(r);

        boolean reacted = false;
        String reactionType = null;
        boolean saved = false;

        if (currentUserId != null) {
            if (reactedOverride != null) {
                reacted = reactedOverride;
                reactionType = reacted ? ReactionType.LIKE.name() : null;
            } else {
                for (ResearchReaction rx : r.getReactions()) {
                    if (rx.getUser().getId().equals(currentUserId)) {
                        reacted = true;
                        reactionType = rx.getReactionType().name();
                        break;
                    }
                }
            }
            saved = isSavedOverride != null
                    ? isSavedOverride
                    : r.getSaves().stream().anyMatch(s -> s.getUser().getId().equals(currentUserId));
        }

        return new ResearchResponse(
                r.getId(),
                r.getSlug(),
                r.getIrcId(),
                author.getId(),
                author.getFullName(),
                author.getUsername(),
                author.getProfileImage(),
                r.getTitle(),
                r.getDescription(),
                r.getAbstractText(),
                r.getKeywords(),
                r.getCitation(),
                r.getDoi(),
                r.getVideoPromoUrl(),
                r.getVideoPromoDurationSeconds(),
                r.getVideoPromoThumbnailUrl(),
                r.getCoverImageUrl(),
                r.getStatus(),
                r.getVisibility(),
                r.getScheduledPublishAt(),
                r.getPublishedAt(),
                c[0], // views
                c[1], // downloads
                c[2], // reactions
                c[3], // comments
                c[4], // saves
                c[5], // shares
                c[6], // citations
                r.isCommentsEnabled(),
                r.isDownloadsEnabled(),
                r.getShareToken(),
                buildShareUrl(r.getShareToken()),
                r.getTags().stream().map(ResearchTag::getTagName).toList(),
                r.getMediaFiles().stream().map(this::toMediaResponse).toList(),
                r.getSources().stream().map(this::toSourceResponse).toList(),
                r.getContributors().stream().map(this::toContributorResponse).toList(),
                reacted,
                reactionType,
                saved,
                r.getCreatedAt(),
                r.getUpdatedAt(),
                TimeDisplayUtil.timeAgo(r.getPublishedAt() != null ? r.getPublishedAt() : r.getCreatedAt()),
                TimeDisplayUtil.formattedDate(r.getPublishedAt() != null ? r.getPublishedAt() : r.getCreatedAt())
        );
    }

    // ── Summary (feed / card) ────────────────────────────────────────────────

    public ResearchSummaryResponse toSummary(Research r, UUID currentUserId) {
        return toSummary(r, currentUserId, null);
    }

    /**
     * Block-aware overload — see {@link #toResponse(Research, UUID, Boolean)}.
     * Pass the pre-resolved {@code isSavedOverride} from a batched
     * {@code findSavedResearchIds} lookup to avoid N+1 on feed renders.
     */
    public ResearchSummaryResponse toSummary(Research r, UUID currentUserId, Boolean isSavedOverride) {
        return toSummary(r, currentUserId, isSavedOverride, null);
    }

    /**
     * Full overload: pre-resolved {@code isSavedOverride} and
     * {@code reactedOverride} from batched lookups so feed pages don't lazy-load
     * the full {@code r.getReactions()} / {@code r.getSaves()} collections per
     * card. Without {@code reactedOverride}, a user signing back in sees
     * reactions they've made counted in {@code reactionCount} but
     * {@code currentUserReacted=false}, leading to a double-react bug on tap.
     */
    public ResearchSummaryResponse toSummary(Research r, UUID currentUserId,
                                             Boolean isSavedOverride, Boolean reactedOverride) {
        User author = r.getResearcher();
        long[] c = resolveCounters(r);

        boolean reacted = false;
        boolean saved   = false;

        if (currentUserId != null) {
            reacted = reactedOverride != null
                    ? reactedOverride
                    : r.getReactions().stream().anyMatch(rx -> rx.getUser().getId().equals(currentUserId));
            saved   = isSavedOverride != null
                    ? isSavedOverride
                    : r.getSaves().stream().anyMatch(s -> s.getUser().getId().equals(currentUserId));
        }

        return new ResearchSummaryResponse(
                r.getId(),
                r.getSlug(),
                r.getIrcId(),
                r.getTitle(),
                r.getAbstractText(),
                r.getCoverImageUrl(),
                r.getVideoPromoThumbnailUrl(),
                author.getId(),
                author.getFullName(),
                author.getUsername(),
                author.getProfileImage(),
                r.getStatus(),
                r.getPublishedAt(),
                c[0], // views
                c[2], // reactions
                c[3], // comments
                c[1], // downloads
                c[4], // saves
                c[5], // shares
                c[6], // citations
                r.getTags().stream().map(ResearchTag::getTagName).toList(),
                buildShareUrl(r.getShareToken()),
                reacted,
                saved
        );
    }

    // ── Media ────────────────────────────────────────────────────────────────

    public MediaResponse toMediaResponse(ResearchMedia m) {
        return new MediaResponse(
                m.getId(), m.getFileUrl(), m.getOriginalFileName(),
                m.getMimeType(), m.getMediaType(), m.getFileSize(),
                m.getDisplayOrder(), m.getCaption(), m.getAltText(),
                m.getDurationSeconds(), m.getThumbnailUrl(),
                m.getWidthPx(), m.getHeightPx()
        );
    }

    // ── Contributor ───────────────────────────────────────────────────────────

    public ContributorResponse toContributorResponse(ResearchContributor c) {
        User u = c.getUser();
        return new ContributorResponse(
                c.getId(),
                u.getId(),
                u.getFullName(),
                u.getUsername(),
                u.getProfileImage(),
                u.getRole(),
                u.getAccountType(),
                c.getRole(),
                c.getDisplayOrder(),
                c.getContributionNote(),
                c.getCreatedAt()
        );
    }

    // ── Source ────────────────────────────────────────────────────────────────

    public SourceResponse toSourceResponse(ResearchSource s) {
        return new SourceResponse(
                s.getId(), s.getSourceType(), s.getTitle(),
                s.getCitationText(), s.getUrl(), s.getDoi(), s.getIsbn(),
                s.getFileUrl(), s.getOriginalFileName(), s.getMimeType(),
                s.getFileSize(), s.getDisplayOrder()
        );
    }

    // ── Comment ──────────────────────────────────────────────────────────────

    public CommentResponse toCommentResponse(ResearchComment c) {
        return toCommentResponse(c, false, null);
    }

    public CommentResponse toCommentResponse(ResearchComment c, boolean canViewHidden) {
        return toCommentResponse(c, canViewHidden, null);
    }

    public CommentResponse toCommentResponse(ResearchComment c, boolean canViewHidden,
                                              ak.dev.irc.app.research.enums.ReactionType myReaction) {
        User u = c.getUser();
        List<CommentResponse> replies = c.getReplies() != null
                ? c.getReplies().stream()
                .filter(r -> !r.isDeleted() && (canViewHidden || !r.isHidden()))
                .map(r -> toCommentResponse(r, canViewHidden))
                .toList()
                : Collections.emptyList();

        return new CommentResponse(
                c.getId(), c.getResearch().getId(),
                u.getId(), u.getFullName(), u.getUsername(), u.getProfileImage(),
                c.getContent(),
                c.getMediaUrl(), c.getMediaType(), c.getMediaThumbnailUrl(),
                c.getLikeCount(), c.getReplyCount(),
                myReaction,
                c.isEdited(), c.getEditedAt(),
                c.isHidden(), c.getHiddenAt(),
                c.getParent() != null ? c.getParent().getId() : null,
                replies,
                c.getCreatedAt(),
                TimeDisplayUtil.timeAgo(c.getCreatedAt()),
                TimeDisplayUtil.formattedDate(c.getCreatedAt())
        );
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String buildShareUrl(String shareToken) {
        if (shareToken == null) return null;
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + "/r/" + shareToken;
    }
}
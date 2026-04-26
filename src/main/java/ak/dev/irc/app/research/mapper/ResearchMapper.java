package ak.dev.irc.app.research.mapper;

import ak.dev.irc.app.common.util.TimeDisplayUtil;
import ak.dev.irc.app.research.dto.response.*;
import ak.dev.irc.app.research.entity.*;
import ak.dev.irc.app.user.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Component
public class ResearchMapper {

    @Value("${irc.base-url:https://irc.example.com}")
    private String baseUrl;

    // ── Full detail ──────────────────────────────────────────────────────────

    public ResearchResponse toResponse(Research r, UUID currentUserId) {
        User author = r.getResearcher();

        boolean reacted = false;
        String reactionType = null;
        boolean saved = false;

        if (currentUserId != null) {
            for (ResearchReaction rx : r.getReactions()) {
                if (rx.getUser().getId().equals(currentUserId)) {
                    reacted = true;
                    reactionType = rx.getReactionType().name();
                    break;
                }
            }
            saved = r.getSaves().stream()
                    .anyMatch(s -> s.getUser().getId().equals(currentUserId));
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
                r.getViewCount(),
                r.getDownloadCount(),
                r.getReactionCount(),
                r.getCommentCount(),
                r.getSaveCount(),
                r.getShareCount(),
                r.getCitationCount(),
                r.isCommentsEnabled(),
                r.isDownloadsEnabled(),
                r.getShareToken(),
                buildShareUrl(r.getShareToken()),
                r.getTags().stream().map(ResearchTag::getTagName).toList(),
                r.getMediaFiles().stream().map(this::toMediaResponse).toList(),
                r.getSources().stream().map(this::toSourceResponse).toList(),
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
        User author = r.getResearcher();

        boolean reacted = false;
        boolean saved   = false;

        if (currentUserId != null) {
            reacted = r.getReactions().stream()
                    .anyMatch(rx -> rx.getUser().getId().equals(currentUserId));
            saved   = r.getSaves().stream()
                    .anyMatch(s -> s.getUser().getId().equals(currentUserId));
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
                r.getViewCount(),
                r.getReactionCount(),
                r.getCommentCount(),
                r.getDownloadCount(),
                r.getSaveCount(),
                r.getShareCount(),
                r.getCitationCount(),
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
        return toCommentResponse(c, false);
    }

    public CommentResponse toCommentResponse(ResearchComment c, boolean canViewHidden) {
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
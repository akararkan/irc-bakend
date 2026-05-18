package ak.dev.irc.app.research.service;

import ak.dev.irc.app.research.dto.request.*;
import ak.dev.irc.app.research.dto.response.*;
import ak.dev.irc.app.research.enums.ReactionType;
import ak.dev.irc.app.share.ShareLinkInfo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface ResearchService {

    // ── CRUD (researcher only) ───────────────────────────────────────────────

    ResearchResponse create(CreateResearchRequest req,
                            List<MultipartFile> files,
                            UUID researcherId) ;

    ResearchResponse update(UUID researchId, UpdateResearchRequest request, UUID researcherId);

    void delete(UUID researchId, UUID researcherId);

    ResearchResponse publish(UUID researchId, UUID researcherId);

    // ── Lifecycle ────────────────────────────────────────────────────────────

    ResearchResponse unpublish(UUID researchId, UUID researcherId);

    ResearchResponse archive(UUID researchId, UUID researcherId);

    ResearchResponse retract(UUID researchId, UUID researcherId);

    // ── Scheduled auto-publish ────────────────────────────────────────────────

    void processScheduledPublications();

    // ── Media upload (Cloudflare R2) ─────────────────────────────────────────

    /**
     * Upload a video promo. Duration is extracted server-side from the file —
     * the client does not pass it. Returns the updated research with
     * {@code videoPromoDurationSeconds} populated (or {@code null} if
     * extraction failed; the upload still succeeds in that case).
     */
    ResearchResponse uploadVideoPromo(UUID researchId, MultipartFile video,
                                      MultipartFile thumbnail, UUID researcherId);

    ResearchResponse removeVideoPromo(UUID researchId, UUID researcherId);

    ResearchResponse uploadCoverImage(UUID researchId, MultipartFile image, UUID researcherId);

    ResearchResponse removeCoverImage(UUID researchId, UUID researcherId);

    MediaResponse addMediaFile(UUID researchId, MultipartFile file, String caption,
                               String altText, Integer displayOrder, UUID researcherId);

    MediaResponse updateMediaMetadata(UUID researchId, UUID mediaId,
                                      UpdateMediaRequest request, UUID researcherId);

    void removeMediaFile(UUID researchId, UUID mediaId, UUID researcherId);

    // ── Source file upload ────────────────────────────────────────────────────

    SourceResponse updateSource(UUID researchId, UUID sourceId,
                                UpdateSourceRequest request, UUID researcherId);

    SourceResponse uploadSourceFile(UUID researchId, UUID sourceId,
                                    MultipartFile file, UUID researcherId);

    // ── Read (everyone) ──────────────────────────────────────────────────────

    ResearchResponse getById(UUID researchId, UUID currentUserId);

    ResearchResponse getBySlug(String slug, UUID currentUserId);

    ResearchResponse getByShareToken(String shareToken, UUID currentUserId);

    Page<ResearchSummaryResponse> getFeed(Pageable pageable, UUID currentUserId);

    Page<ResearchSummaryResponse> getFollowingFeed(UUID userId, Pageable pageable);

    Page<ResearchSummaryResponse> getByResearcher(UUID researcherId, Pageable pageable, UUID currentUserId);

    Page<ResearchSummaryResponse> search(String query, Pageable pageable, UUID currentUserId);

    Page<ResearchSummaryResponse> fullTextSearch(String query, Pageable pageable, UUID currentUserId);

    Page<ResearchSummaryResponse> searchByTags(List<String> tags, Pageable pageable, UUID currentUserId);

    // ── Researcher dashboard ──────────────────────────────────────────────────

    Page<ResearchSummaryResponse> getMyDrafts(UUID researcherId, Pageable pageable);

    Page<ResearchSummaryResponse> getMyResearches(UUID researcherId, Pageable pageable);

    // ── Contributors (research owner only) ───────────────────────────────────

    /**
     * Add a single contributor to the research. The target user must carry role
     * RESEARCHER or SCHOLAR. Idempotency: re-adding the same user is rejected
     * with {@link ak.dev.irc.app.common.exception.ConflictException}. Only the
     * corresponding researcher (owner of the paper) may add contributors.
     */
    ContributorResponse addContributor(UUID researchId, ContributorRequest request, UUID researcherId);

    /**
     * Replace the full contributor list for a research. The supplied list becomes
     * the new state — anything previously stored that is not in the new list is
     * removed. Pass an empty list to clear all contributors.
     */
    List<ContributorResponse> replaceContributors(UUID researchId,
                                                   List<ContributorRequest> contributors,
                                                   UUID researcherId);

    /** Update a single contributor's role / order / note. */
    ContributorResponse updateContributor(UUID researchId, UUID contributorId,
                                          UpdateContributorRequest request, UUID researcherId);

    /** Remove a contributor by their {@code contributor_id} (not user-id). */
    void removeContributor(UUID researchId, UUID contributorId, UUID researcherId);

    /** Public listing of contributors on a research, ordered by displayOrder. */
    List<ContributorResponse> getContributors(UUID researchId);

    // ── Reactions (any user) ─────────────────────────────────────────────────

    void react(UUID researchId, ReactRequest request, UUID userId);

    /**
     * Remove the viewer's reaction on a research and return the updated detail
     * payload with {@code reactionCount} decremented and
     * {@code currentUserReacted=false} so a front-end gets the new state in one
     * round-trip — no follow-up GET, no reliance on the SSE echo.
     */
    ResearchResponse removeReaction(UUID researchId, UUID userId);

    Map<ReactionType, Long> getReactionBreakdown(UUID researchId);

    // ── Comments (any user) ──────────────────────────────────────────────────

    CommentResponse addComment(UUID researchId, AddCommentRequest request, UUID userId);

    CommentResponse addCommentWithMedia(UUID researchId, AddCommentRequest request, UUID userId,
                                        MultipartFile media, MultipartFile voice);

    CommentResponse editComment(UUID researchId, UUID commentId, EditCommentRequest request, UUID userId);

    void deleteComment(UUID researchId, UUID commentId, UUID userId);

    Page<CommentResponse> getComments(UUID researchId, Pageable pageable, UUID currentUserId);

    /** @deprecated use {@link #reactToComment} with {@code ReactionType.LIKE}. Kept idempotent for back-compat. */
    @Deprecated
    void likeComment(UUID researchId, UUID commentId, UUID userId);

    /** @deprecated use {@link #removeCommentReaction}. Kept for back-compat. */
    @Deprecated
    CommentResponse unlikeComment(UUID researchId, UUID commentId, UUID userId);

    /**
     * Add or change the viewer's reaction on a comment. Mirrors
     * {@code PostCommentService.reactToComment} — idempotent on same-type
     * (counter unchanged), updates type on different-type (counter unchanged),
     * inserts on first-time (counter +1). Broadcasts {@code COMMENT_REACTION_ADDED}
     * or {@code COMMENT_REACTION_CHANGED} on the research realtime channel.
     */
    void reactToComment(UUID researchId, UUID commentId, ReactRequest request, UUID userId);

    /**
     * Remove the viewer's reaction on a comment. Broadcasts
     * {@code COMMENT_REACTION_REMOVED}. Returns the updated comment with
     * {@code likeCount} decremented and {@code myReaction=null} so the
     * front-end has the new state in the response body.
     */
    CommentResponse removeCommentReaction(UUID researchId, UUID commentId, UUID userId);

    void hideComment(UUID researchId, UUID commentId, UUID userId);

    void unhideComment(UUID researchId, UUID commentId, UUID userId);

    // ── Save / Bookmark ──────────────────────────────────────────────────────

    /**
     * Save (bookmark) a research paper into a collection. Idempotent — saving
     * a paper that is already bookmarked is a no-op at the DB layer but still
     * returns the updated payload so the front-end can call this blindly.
     */
    ResearchResponse saveResearch(UUID researchId, String collectionName, UUID userId);

    /**
     * Remove the viewer's bookmark on a research paper. Idempotent — unsaving
     * a paper that is not bookmarked is a no-op. Returns the updated payload
     * with {@code currentUserSaved=false} and decremented {@code saveCount}.
     */
    ResearchResponse unsaveResearch(UUID researchId, UUID userId);

    Page<ResearchSummaryResponse> getSavedResearches(UUID userId, Pageable pageable);

    Page<ResearchSummaryResponse> getSavedByCollection(UUID userId, String collectionName, Pageable pageable);

    List<String> getUserCollections(UUID userId);

    void renameCollection(UUID userId, String oldName, String newName);

    // ── View & Download tracking ─────────────────────────────────────────────

    void recordView(UUID researchId, UUID viewerId, String viewerKey);

    String recordDownload(UUID researchId, UUID mediaId, UUID userId, String ipAddress);

    // ── Share & Citations ─────────────────────────────────────────────────────

    /** Legacy: returns plain share URL string. Kept for back-compat. */
    String getShareLink(UUID researchId);

    /** Returns full share info without bumping the counter. */
    ShareLinkInfo previewShareLink(UUID researchId, String baseUrl);

    /** Bumps the share counter and returns full share info. */
    ShareLinkInfo recordShare(UUID researchId, UUID requesterId, String baseUrl);

    void incrementCitationCount(UUID researchId);

    // ── Trending tags ────────────────────────────────────────────────────────

    List<String> getTrendingTags(int limit);
}

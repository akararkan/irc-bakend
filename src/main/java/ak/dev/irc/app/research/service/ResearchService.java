package ak.dev.irc.app.research.service;

import ak.dev.irc.app.research.dto.request.*;
import ak.dev.irc.app.research.dto.response.*;
import ak.dev.irc.app.research.enums.ReactionType;
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

    ResearchResponse uploadVideoPromo(UUID researchId, MultipartFile video, UUID researcherId);

    ResearchResponse removeVideoPromo(UUID researchId, UUID researcherId);

    ResearchResponse uploadCoverImage(UUID researchId, MultipartFile image, UUID researcherId);

    ResearchResponse removeCoverImage(UUID researchId, UUID researcherId);

    MediaResponse addMediaFile(UUID researchId, MultipartFile file, String caption,
                               String altText, Integer displayOrder, UUID researcherId);

    MediaResponse updateMediaMetadata(UUID researchId, UUID mediaId,
                                      UpdateMediaRequest request, UUID researcherId);

    void removeMediaFile(UUID researchId, UUID mediaId, UUID researcherId);

    // ── Source file upload ────────────────────────────────────────────────────

    SourceResponse uploadSourceFile(UUID researchId, UUID sourceId,
                                    MultipartFile file, UUID researcherId);

    // ── Read (everyone) ──────────────────────────────────────────────────────

    ResearchResponse getById(UUID researchId, UUID currentUserId);

    ResearchResponse getBySlug(String slug, UUID currentUserId);

    ResearchResponse getByShareToken(String shareToken, UUID currentUserId);

    Page<ResearchSummaryResponse> getFeed(Pageable pageable, UUID currentUserId);

    Page<ResearchSummaryResponse> getByResearcher(UUID researcherId, Pageable pageable, UUID currentUserId);

    Page<ResearchSummaryResponse> search(String query, Pageable pageable, UUID currentUserId);

    Page<ResearchSummaryResponse> fullTextSearch(String query, Pageable pageable, UUID currentUserId);

    Page<ResearchSummaryResponse> searchByTags(List<String> tags, Pageable pageable, UUID currentUserId);

    // ── Researcher dashboard ──────────────────────────────────────────────────

    Page<ResearchSummaryResponse> getMyDrafts(UUID researcherId, Pageable pageable);

    Page<ResearchSummaryResponse> getMyResearches(UUID researcherId, Pageable pageable);

    // ── Reactions (any user) ─────────────────────────────────────────────────

    void react(UUID researchId, ReactRequest request, UUID userId);

    void removeReaction(UUID researchId, UUID userId);

    Map<ReactionType, Long> getReactionBreakdown(UUID researchId);

    // ── Comments (any user) ──────────────────────────────────────────────────

    CommentResponse addComment(UUID researchId, AddCommentRequest request, UUID userId);

    CommentResponse editComment(UUID researchId, UUID commentId, EditCommentRequest request, UUID userId);

    void deleteComment(UUID researchId, UUID commentId, UUID userId);

    Page<CommentResponse> getComments(UUID researchId, Pageable pageable);

    void likeComment(UUID researchId, UUID commentId, UUID userId);

    void unlikeComment(UUID researchId, UUID commentId, UUID userId);

    // ── Save / Bookmark ──────────────────────────────────────────────────────

    void saveResearch(UUID researchId, String collectionName, UUID userId);

    void unsaveResearch(UUID researchId, UUID userId);

    Page<ResearchSummaryResponse> getSavedResearches(UUID userId, Pageable pageable);

    Page<ResearchSummaryResponse> getSavedByCollection(UUID userId, String collectionName, Pageable pageable);

    List<String> getUserCollections(UUID userId);

    // ── View & Download tracking ─────────────────────────────────────────────

    void recordView(UUID researchId, UUID userId, String ipAddress, String userAgent);

    String recordDownload(UUID researchId, UUID mediaId, UUID userId, String ipAddress);

    // ── Share & Citations ─────────────────────────────────────────────────────

    String getShareLink(UUID researchId);

    void incrementCitationCount(UUID researchId);

    // ── Trending tags ────────────────────────────────────────────────────────

    List<String> getTrendingTags(int limit);
}

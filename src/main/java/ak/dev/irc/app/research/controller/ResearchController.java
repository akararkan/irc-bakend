package ak.dev.irc.app.research.controller;

import ak.dev.irc.app.research.dto.request.*;
import ak.dev.irc.app.research.dto.response.*;
import ak.dev.irc.app.research.enums.ReactionType;
import ak.dev.irc.app.research.service.ResearchService;
import ak.dev.irc.app.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for all research-related endpoints.
 *
 * <p>Base path: {@code /api/v1/researches}
 *
 * <h3>Create flow (single multipart call):</h3>
 * <pre>
 * POST /api/v1/researches
 * Content-Type: multipart/form-data
 *
 * Part "data"      → JSON (CreateResearchRequest)
 * Part "files[]"   → one or more binary files  (optional)
 * </pre>
 *
 * <p>The {@code files[]} parts are matched to the {@code mediaFiles} metadata
 * list inside the JSON body by index position.
 */
@RestController
@RequestMapping("/api/v1/researches")
@RequiredArgsConstructor
public class ResearchController {

    private final ResearchService researchService;

    // ══════════════════════════════════════════════════════════════════════════
    //  CREATE — single multipart/form-data call, files included
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Create a new research draft, optionally including all media files
     * in the same request.
     *
     * <h4>Multipart parts</h4>
     * <ul>
     *   <li>{@code data}    — required — JSON body ({@link CreateResearchRequest})</li>
     *   <li>{@code files[]} — optional — one or more binary files</li>
     * </ul>
     *
     * <h4>Example (curl)</h4>
     * <pre>{@code
     * curl -X POST https://api.irc.example.com/api/v1/researches \
     *   -H "Authorization: Bearer <token>" \
     *   -F 'data={"title":"...","tags":["ai"],...};type=application/json' \
     *   -F 'files[]=@paper.pdf;type=application/pdf' \
     *   -F 'files[]=@figure1.png;type=image/png'
     * }</pre>
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('SCHOLAR', 'RESEARCHER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ResearchResponse> create(
            @RequestPart("data") @Valid CreateResearchRequest request,
            @RequestPart(value = "files[]", required = false) List<MultipartFile> files,
            @AuthenticationPrincipal User user) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(researchService.create(request, files, user.getId()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  UPDATE
    // ══════════════════════════════════════════════════════════════════════════

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('SCHOLAR', 'RESEARCHER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ResearchResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateResearchRequest request,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(researchService.update(id, request, user.getId()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════════

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('SCHOLAR', 'RESEARCHER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ResearchResponse> publish(
            @PathVariable UUID id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(researchService.publish(id, user.getId()));
    }

    @PostMapping("/{id}/unpublish")
    @PreAuthorize("hasAnyRole('SCHOLAR', 'RESEARCHER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ResearchResponse> unpublish(
            @PathVariable UUID id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(researchService.unpublish(id, user.getId()));
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAnyRole('SCHOLAR', 'RESEARCHER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ResearchResponse> archive(
            @PathVariable UUID id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(researchService.archive(id, user.getId()));
    }

    @PostMapping("/{id}/retract")
    @PreAuthorize("hasAnyRole('SCHOLAR', 'RESEARCHER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ResearchResponse> retract(
            @PathVariable UUID id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(researchService.retract(id, user.getId()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SCHOLAR', 'RESEARCHER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id, @AuthenticationPrincipal User user) {
        researchService.delete(id, user.getId());
        return ResponseEntity.noContent().build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  VIDEO PROMO
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Upload a video promo with optional thumbnail.
     *
     * <p>Video duration is automatically extracted from the file (MP4/MOV).
     * If extraction fails (e.g. WebM format), pass {@code durationSeconds} as a fallback.
     *
     * <h4>Multipart parts</h4>
     * <ul>
     *   <li>{@code video}     — required — the video file (mp4, webm, quicktime)</li>
     *   <li>{@code thumbnail} — optional — a thumbnail image for the video</li>
     * </ul>
     *
     * <h4>Query parameters</h4>
     * <ul>
     *   <li>{@code durationSeconds} — optional — fallback if server-side extraction fails</li>
     * </ul>
     */
    @PostMapping(value = "/{id}/video-promo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('SCHOLAR', 'RESEARCHER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ResearchResponse> uploadVideoPromo(
            @PathVariable UUID id,
            @RequestPart("video") MultipartFile video,
            @RequestPart(value = "thumbnail", required = false) MultipartFile thumbnail,
            @RequestParam(value = "durationSeconds", required = false) Integer durationSeconds,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(researchService.uploadVideoPromo(id, video, thumbnail, durationSeconds, user.getId()));
    }

    @DeleteMapping("/{id}/video-promo")
    @PreAuthorize("hasAnyRole('SCHOLAR', 'RESEARCHER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ResearchResponse> removeVideoPromo(
            @PathVariable UUID id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(researchService.removeVideoPromo(id, user.getId()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  COVER IMAGE
    // ══════════════════════════════════════════════════════════════════════════

    @PostMapping(value = "/{id}/cover-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('SCHOLAR', 'RESEARCHER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ResearchResponse> uploadCoverImage(
            @PathVariable UUID id,
            @RequestPart("image") MultipartFile image,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(researchService.uploadCoverImage(id, image, user.getId()));
    }

    @DeleteMapping("/{id}/cover-image")
    @PreAuthorize("hasAnyRole('SCHOLAR', 'RESEARCHER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ResearchResponse> removeCoverImage(
            @PathVariable UUID id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(researchService.removeCoverImage(id, user.getId()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  MEDIA FILES (post-creation additions)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Add a single media file to an existing research.
     * Use this after creation if you need to add more files later.
     */
    @PostMapping(value = "/{id}/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('SCHOLAR', 'RESEARCHER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<MediaResponse> addMedia(
            @PathVariable UUID id,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String caption,
            @RequestParam(required = false) String altText,
            @RequestParam(required = false) Integer displayOrder,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(researchService.addMediaFile(id, file, caption, altText, displayOrder, user.getId()));
    }

    @PatchMapping("/{id}/media/{mediaId}")
    @PreAuthorize("hasAnyRole('SCHOLAR', 'RESEARCHER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<MediaResponse> updateMediaMetadata(
            @PathVariable UUID id,
            @PathVariable UUID mediaId,
            @Valid @RequestBody UpdateMediaRequest request,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(researchService.updateMediaMetadata(id, mediaId, request, user.getId()));
    }

    @DeleteMapping("/{id}/media/{mediaId}")
    @PreAuthorize("hasAnyRole('SCHOLAR', 'RESEARCHER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<Void> removeMedia(
            @PathVariable UUID id,
            @PathVariable UUID mediaId,
            @AuthenticationPrincipal User user) {
        researchService.removeMediaFile(id, mediaId, user.getId());
        return ResponseEntity.noContent().build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  SOURCE FILE UPLOAD
    // ══════════════════════════════════════════════════════════════════════════

    @PatchMapping("/{id}/sources/{sourceId}")
    @PreAuthorize("hasAnyRole('SCHOLAR', 'RESEARCHER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<SourceResponse> updateSource(
            @PathVariable UUID id,
            @PathVariable UUID sourceId,
            @Valid @RequestBody UpdateSourceRequest request,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(researchService.updateSource(id, sourceId, request, user.getId()));
    }

    @PostMapping(value = "/{id}/sources/{sourceId}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('SCHOLAR', 'RESEARCHER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<SourceResponse> uploadSourceFile(
            @PathVariable UUID id,
            @PathVariable UUID sourceId,
            @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(researchService.uploadSourceFile(id, sourceId, file, user.getId()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  READ — single item
    // ══════════════════════════════════════════════════════════════════════════

    /** Get a full research detail by its UUID. */
    @GetMapping("/{id}")
    public ResponseEntity<ResearchResponse> getById(
            @PathVariable UUID id,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(researchService.getById(id, user != null ? user.getId() : null));
    }

    /** Get a full research detail by its URL slug. */
    @GetMapping("/slug/{slug}")
    public ResponseEntity<ResearchResponse> getBySlug(
            @PathVariable String slug,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(researchService.getBySlug(slug, user != null ? user.getId() : null));
    }

    /** Resolve a share-token short link to the full research detail. */
    @GetMapping("/share/{shareToken}")
    public ResponseEntity<ResearchResponse> getByShareToken(
            @PathVariable String shareToken,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(researchService.getByShareToken(shareToken, user != null ? user.getId() : null));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  READ — paginated lists
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Public feed — published researches ordered by publish date descending.
     *
     * <p>Supports Spring Data pagination params:
     * {@code ?page=0&size=20&sort=publishedAt,desc}
     */
    @GetMapping("/feed")
    public ResponseEntity<Page<ResearchSummaryResponse>> getFeed(
            @PageableDefault(size = 20, sort = "publishedAt") Pageable pageable,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(researchService.getFeed(pageable, user != null ? user.getId() : null));
    }

    /**
     * Following feed — published researches from researchers that the
     * authenticated user follows, ordered by publish date descending.
     * Excludes blocked users in both directions.
     */
    @GetMapping("/feed/following")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<ResearchSummaryResponse>> getFollowingFeed(
            @PageableDefault(size = 20, sort = "publishedAt") Pageable pageable,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(researchService.getFollowingFeed(user.getId(), pageable));
    }

    /** All published researches by a specific researcher. */
    @GetMapping("/researcher/{researcherId}")
    public ResponseEntity<Page<ResearchSummaryResponse>> getByResearcher(
            @PathVariable UUID researcherId,
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(
                researchService.getByResearcher(researcherId, pageable, user != null ? user.getId() : null));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  RESEARCHER DASHBOARD
    // ══════════════════════════════════════════════════════════════════════════

    /** The authenticated researcher's draft researches. */
    @GetMapping("/me/drafts")
    @PreAuthorize("hasAnyRole('SCHOLAR', 'RESEARCHER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<Page<ResearchSummaryResponse>> getMyDrafts(
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(researchService.getMyDrafts(user.getId(), pageable));
    }

    /** All researches (all statuses) belonging to the authenticated researcher. */
    @GetMapping("/me/all")
    @PreAuthorize("hasAnyRole('SCHOLAR', 'RESEARCHER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<Page<ResearchSummaryResponse>> getMyResearches(
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(researchService.getMyResearches(user.getId(), pageable));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  SEARCH
    // ══════════════════════════════════════════════════════════════════════════

    /** Basic LIKE search across title, abstract, and keywords. */
    @GetMapping("/search")
    public ResponseEntity<Page<ResearchSummaryResponse>> search(
            @RequestParam String q,
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(researchService.search(q, pageable, user != null ? user.getId() : null));
    }

    /** PostgreSQL full-text search (ranked by relevance). */
    @GetMapping("/search/fts")
    public ResponseEntity<Page<ResearchSummaryResponse>> fullTextSearch(
            @RequestParam String q,
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(researchService.fullTextSearch(q, pageable, user != null ? user.getId() : null));
    }

    /** Filter published researches by one or more tags. */
    @GetMapping("/search/tags")
    public ResponseEntity<Page<ResearchSummaryResponse>> searchByTags(
            @RequestParam List<String> tags,
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(
                researchService.searchByTags(tags, pageable, user != null ? user.getId() : null));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  TAGS
    // ══════════════════════════════════════════════════════════════════════════

    /** Most-used tags across all published researches. */
    @GetMapping("/tags/trending")
    public ResponseEntity<List<String>> getTrendingTags(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(researchService.getTrendingTags(limit));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  REACTIONS
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Add or update a reaction on a research.
     * If the user has already reacted, the reaction type is updated.
     */
    @PostMapping("/{id}/react")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> react(
            @PathVariable UUID id,
            @Valid @RequestBody ReactRequest request,
            @AuthenticationPrincipal User user) {
        researchService.react(id, request, user.getId());
        return ResponseEntity.ok().build();
    }

    /** Remove the authenticated user's reaction from a research. */
    @DeleteMapping("/{id}/react")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> removeReaction(
            @PathVariable UUID id,
            @AuthenticationPrincipal User user) {
        researchService.removeReaction(id, user.getId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Get a breakdown of reactions by type.
     * Returns a map of {@code ReactionType → count}.
     *
     * <p>Example response: {@code {"LIKE": 87, "INSIGHTFUL": 12, "LOVE": 3}}
     */
    @GetMapping("/{id}/reactions")
    public ResponseEntity<Map<ReactionType, Long>> getReactionBreakdown(@PathVariable UUID id) {
        return ResponseEntity.ok(researchService.getReactionBreakdown(id));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  SAVE / BOOKMARK  — read-only "me" queries live here;
    //  mutating save/unsave is in ResearchSocialController
    // ══════════════════════════════════════════════════════════════════════════

    /** All saved researches for the authenticated user. */
    @GetMapping("/me/saved")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<ResearchSummaryResponse>> getSavedResearches(
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(researchService.getSavedResearches(user.getId(), pageable));
    }

    /** Saved researches filtered by a specific collection name. */
    @GetMapping("/me/saved/collection")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<ResearchSummaryResponse>> getSavedByCollection(
            @RequestParam String name,
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(researchService.getSavedByCollection(user.getId(), name, pageable));
    }

    /** All collection names the user has saved researches into. */
    @GetMapping("/me/saved/collections")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<String>> getUserCollections(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(researchService.getUserCollections(user.getId()));
    }

    /** Rename a save collection. */
    @PatchMapping("/me/saved/collections")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> renameCollection(
            @RequestParam String oldName,
            @RequestParam String newName,
            @AuthenticationPrincipal User user) {
        researchService.renameCollection(user.getId(), oldName, newName);
        return ResponseEntity.ok().build();
    }


    // ══════════════════════════════════════════════════════════════════════════
    //  SHARE & CITATIONS
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Increment share counter and return the full public share URL.
     *
     * <p>Example response: {@code "https://irc.example.com/r/fX9kR2mQpLzT4nYw"}
     */
    @PostMapping("/{id}/share")
    public ResponseEntity<String> getShareLink(@PathVariable UUID id) {
        return ResponseEntity.ok(researchService.getShareLink(id));
    }

    /**
     * Record an external citation event — increments {@code citationCount} on
     * the research. Called by the DOI resolver or third-party citation services.
     */
    @PostMapping("/{id}/cite")
    public ResponseEntity<Void> recordCitation(@PathVariable UUID id) {
        researchService.incrementCitationCount(id);
        return ResponseEntity.ok().build();
    }
}
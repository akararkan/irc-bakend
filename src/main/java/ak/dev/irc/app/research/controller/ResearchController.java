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
    private final ak.dev.irc.app.research.realtime.ResearchRealtimeService researchRealtimeService;

    /**
     * Live event stream for a single research publication.
     *
     * <p>Subscribers receive every reaction, comment, reply, view-count,
     * download-count, share-count, save-count, and citation-count update on
     * this research in near real time. Event names mirror
     * {@code ResearchRealtimeEventType}; payload schema is
     * {@code ResearchRealtimeEvent}. A {@code connected} handshake fires on
     * subscribe and a {@code heartbeat} every 25 s.</p>
     */
    @org.springframework.web.bind.annotation.GetMapping(
            value = "/{researchId}/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter streamResearch(
            @org.springframework.web.bind.annotation.PathVariable java.util.UUID researchId,
            @AuthenticationPrincipal User user) {
        return researchRealtimeService.subscribe(researchId, user != null ? user.getId() : null);
    }

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
     * <p>Video duration is extracted server-side from the uploaded file
     * (MP4/MOV/QuickTime via mp4parser). The client does <strong>not</strong>
     * send a duration — there is no {@code durationSeconds} parameter. If
     * extraction can't determine the duration (rare; unsupported container
     * variant), the field is stored as {@code null} and the upload still
     * succeeds — the client {@code <video>} element can read the duration at
     * playback time.</p>
     *
     * <h4>Multipart parts</h4>
     * <ul>
     *   <li>{@code video}     — required — the video file (mp4, webm, quicktime)</li>
     *   <li>{@code thumbnail} — optional — a thumbnail image for the video</li>
     * </ul>
     */
    @PostMapping(value = "/{id}/video-promo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('SCHOLAR', 'RESEARCHER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ResearchResponse> uploadVideoPromo(
            @PathVariable UUID id,
            @RequestPart("video") MultipartFile video,
            @RequestPart(value = "thumbnail", required = false) MultipartFile thumbnail,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(researchService.uploadVideoPromo(id, video, thumbnail, user.getId()));
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
    //  CONTRIBUTORS — managed by the corresponding researcher only
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Add a single contributor (co-author, advisor, translator, etc.) to a
     * research. The target user must already exist and hold role
     * {@code RESEARCHER} or {@code SCHOLAR}.
     */
    @PostMapping("/{id}/contributors")
    @PreAuthorize("hasAnyRole('SCHOLAR', 'RESEARCHER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ContributorResponse> addContributor(
            @PathVariable UUID id,
            @Valid @RequestBody ContributorRequest request,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(researchService.addContributor(id, request, user.getId()));
    }

    /**
     * Replace the entire contributor list. Pass an empty list to clear.
     * Use this when the UI lets the researcher edit the full co-author table
     * in one form and submit it as a single PUT.
     */
    @PutMapping("/{id}/contributors")
    @PreAuthorize("hasAnyRole('SCHOLAR', 'RESEARCHER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<List<ContributorResponse>> replaceContributors(
            @PathVariable UUID id,
            @Valid @RequestBody List<ContributorRequest> contributors,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(
                researchService.replaceContributors(id, contributors, user.getId()));
    }

    /** Update a single contributor's role / order / note. */
    @PatchMapping("/{id}/contributors/{contributorId}")
    @PreAuthorize("hasAnyRole('SCHOLAR', 'RESEARCHER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<ContributorResponse> updateContributor(
            @PathVariable UUID id,
            @PathVariable UUID contributorId,
            @Valid @RequestBody UpdateContributorRequest request,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(
                researchService.updateContributor(id, contributorId, request, user.getId()));
    }

    /** Remove a contributor row by its id. */
    @DeleteMapping("/{id}/contributors/{contributorId}")
    @PreAuthorize("hasAnyRole('SCHOLAR', 'RESEARCHER', 'ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<Void> removeContributor(
            @PathVariable UUID id,
            @PathVariable UUID contributorId,
            @AuthenticationPrincipal User user) {
        researchService.removeContributor(id, contributorId, user.getId());
        return ResponseEntity.noContent().build();
    }

    /** Public — list all contributors for a research. */
    @GetMapping("/{id}/contributors")
    public ResponseEntity<List<ContributorResponse>> getContributors(@PathVariable UUID id) {
        return ResponseEntity.ok(researchService.getContributors(id));
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
    //
    //  Full-text search lives on the unified endpoint GET /api/v1/search
    //  (see GlobalSearchController). Pass {@code types=RESEARCH} to scope it
    //  to research only. Tag filtering remains here because it's a structured
    //  Postgres query, not a relevance search.
    // ══════════════════════════════════════════════════════════════════════════

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
    //  SAVE / BOOKMARK  — read-only "me" queries live here;
    //  mutating save/unsave + reactions live in ResearchSocialController
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
     * Returns the unified share-link info without bumping the counter — for the
     * inline share UI before the user actually copies the link.
     */
    @GetMapping("/{id}/share-link")
    public ResponseEntity<ak.dev.irc.app.share.ShareLinkInfo> previewShareLink(
            @PathVariable UUID id,
            jakarta.servlet.http.HttpServletRequest request) {
        return ResponseEntity.ok(
                researchService.previewShareLink(id, ak.dev.irc.app.share.OriginUtil.origin(request)));
    }

    /**
     * Atomically bumps {@code shareCount} and returns the unified share-link
     * info. Called when the user actually copies / sends the link.
     */
    @PostMapping("/{id}/share")
    public ResponseEntity<ak.dev.irc.app.share.ShareLinkInfo> shareResearch(
            @PathVariable UUID id,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    ak.dev.irc.app.user.entity.User user,
            jakarta.servlet.http.HttpServletRequest request) {
        UUID requesterId = user != null ? user.getId() : null;
        return ResponseEntity.ok(
                researchService.recordShare(id, requesterId, ak.dev.irc.app.share.OriginUtil.origin(request)));
    }

    /**
     * Record a citation — increments {@code citationCount} on the research.
     * Requires authentication and is de-duplicated per (research, citer) so the
     * public counter can't be trivially inflated (E3).
     */
    @PostMapping("/{id}/cite")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> recordCitation(@PathVariable UUID id,
                                               @AuthenticationPrincipal User user) {
        researchService.incrementCitationCount(id, user != null ? user.getId() : null);
        return ResponseEntity.ok().build();
    }
}
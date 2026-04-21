package ak.dev.irc.app.research.controller;

import ak.dev.irc.app.research.dto.request.AddCommentRequest;
import ak.dev.irc.app.research.dto.request.EditCommentRequest;
import ak.dev.irc.app.research.dto.request.ReactRequest;
import ak.dev.irc.app.research.dto.response.CommentResponse;
import ak.dev.irc.app.research.enums.ReactionType;
import ak.dev.irc.app.research.service.ResearchService;
import ak.dev.irc.app.user.entity.User;
import jakarta.servlet.http.HttpServletRequest;
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

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/researches/{researchId}")
@RequiredArgsConstructor
public class ResearchSocialController {

    private final ResearchService researchService;

    // ══════════════════════════════════════════════════════════════════════════
    //  Reactions
    // ══════════════════════════════════════════════════════════════════════════

    @PostMapping("/reactions")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> react(
            @PathVariable UUID researchId,
            @Valid @RequestBody ReactRequest request,
            @AuthenticationPrincipal User user) {
        researchService.react(researchId, request, user.getId());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/reactions")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> removeReaction(
            @PathVariable UUID researchId,
            @AuthenticationPrincipal User user) {
        researchService.removeReaction(researchId, user.getId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/reactions/breakdown")
    public ResponseEntity<Map<ReactionType, Long>> getReactionBreakdown(
            @PathVariable UUID researchId) {
        return ResponseEntity.ok(researchService.getReactionBreakdown(researchId));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Comments
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/comments")
    public ResponseEntity<Page<CommentResponse>> getComments(
            @PathVariable UUID researchId,
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal User user) {
        UUID uid = user != null ? user.getId() : null;
        return ResponseEntity.ok(researchService.getComments(researchId, pageable, uid));
    }

    @PostMapping("/comments")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CommentResponse> addComment(
            @PathVariable UUID researchId,
            @Valid @RequestBody AddCommentRequest request,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(researchService.addComment(researchId, request, user.getId()));
    }

    @PostMapping(value = "/comments/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CommentResponse> addCommentWithMedia(
            @PathVariable UUID researchId,
            @Valid @RequestPart("data") AddCommentRequest request,
            @RequestPart(value = "media", required = false) MultipartFile media,
            @RequestPart(value = "voice", required = false) MultipartFile voice,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(researchService.addCommentWithMedia(researchId, request, user.getId(), media, voice));
    }

    @PatchMapping("/comments/{commentId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CommentResponse> editComment(
            @PathVariable UUID researchId,
            @PathVariable UUID commentId,
            @Valid @RequestBody EditCommentRequest request,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(researchService.editComment(researchId, commentId, request, user.getId()));
    }

    @DeleteMapping("/comments/{commentId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteComment(
            @PathVariable UUID researchId,
            @PathVariable UUID commentId,
            @AuthenticationPrincipal User user) {
        researchService.deleteComment(researchId, commentId, user.getId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/comments/{commentId}/hide")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> hideComment(
            @PathVariable UUID researchId,
            @PathVariable UUID commentId,
            @AuthenticationPrincipal User user) {
        researchService.hideComment(researchId, commentId, user.getId());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @PostMapping("/comments/{commentId}/unhide")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> unhideComment(
            @PathVariable UUID researchId,
            @PathVariable UUID commentId,
            @AuthenticationPrincipal User user) {
        researchService.unhideComment(researchId, commentId, user.getId());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    // ── Comment likes ─────────────────────────────────────────────────────────

    @PostMapping("/comments/{commentId}/like")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> likeComment(
            @PathVariable UUID researchId,
            @PathVariable UUID commentId,
            @AuthenticationPrincipal User user) {
        researchService.likeComment(researchId, commentId, user.getId());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/comments/{commentId}/like")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> unlikeComment(
            @PathVariable UUID researchId,
            @PathVariable UUID commentId,
            @AuthenticationPrincipal User user) {
        researchService.unlikeComment(researchId, commentId, user.getId());
        return ResponseEntity.noContent().build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Save / Bookmark
    // ══════════════════════════════════════════════════════════════════════════

    @PostMapping("/save")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> save(
            @PathVariable UUID researchId,
            @RequestParam(required = false) String collection,
            @AuthenticationPrincipal User user) {
        researchService.saveResearch(researchId, collection, user.getId());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/save")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> unsave(
            @PathVariable UUID researchId,
            @AuthenticationPrincipal User user) {
        researchService.unsaveResearch(researchId, user.getId());
        return ResponseEntity.noContent().build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Views
    // ══════════════════════════════════════════════════════════════════════════

    @PostMapping("/view")
    public ResponseEntity<Void> recordView(
            @PathVariable UUID researchId,
            @AuthenticationPrincipal User user,
            HttpServletRequest request) {
        UUID uid = user != null ? user.getId() : null;
        researchService.recordView(researchId, uid, extractIp(request), request.getHeader("User-Agent"));
        return ResponseEntity.ok().build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Downloads
    // ══════════════════════════════════════════════════════════════════════════

    @PostMapping("/download")
    public ResponseEntity<String> recordDownload(
            @PathVariable UUID researchId,
            @RequestParam(required = false) UUID mediaId,
            @AuthenticationPrincipal User user,
            HttpServletRequest request) {
        UUID uid = user != null ? user.getId() : null;
        String downloadUrl = researchService.recordDownload(researchId, mediaId, uid, extractIp(request));
        return ResponseEntity.ok(downloadUrl);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Helper
    // ══════════════════════════════════════════════════════════════════════════

    private String extractIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank()) return ip.split(",")[0].trim();
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isBlank()) return ip.trim();
        return request.getRemoteAddr();
    }
}

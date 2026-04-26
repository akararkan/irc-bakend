package ak.dev.irc.app.post.controller;



import ak.dev.irc.app.post.dto.CreatePostRequest;
import ak.dev.irc.app.post.dto.UpdatePostRequest;
import ak.dev.irc.app.post.dto.ReactToPostRequest;
import ak.dev.irc.app.post.dto.PostResponse;
import ak.dev.irc.app.post.service.PostService;
import ak.dev.irc.app.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    // ── Create ────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<PostResponse> createPost(
            @Valid @RequestBody CreatePostRequest req,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(postService.createPost(req, user.getId()));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PostResponse> createPostWithFiles(
            @Valid @RequestPart("data") CreatePostRequest req,
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(postService.createPostWithFiles(req, user.getId(), files));
    }

    // ── Update ────────────────────────────────────────────────

    @PatchMapping("/{postId}")
    public ResponseEntity<PostResponse> updatePost(
            @PathVariable UUID postId,
            @Valid @RequestBody UpdatePostRequest req,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(postService.updatePost(postId, req, user.getId()));
    }

    // ── Read ──────────────────────────────────────────────────

    @GetMapping("/{postId}")
    public ResponseEntity<PostResponse> getPost(
            @PathVariable UUID postId,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(postService.getPost(postId, user != null ? user.getId() : null));
    }

    @GetMapping("/feed")
    public ResponseEntity<Page<PostResponse>> getPublicFeed(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(postService.getPublicFeed(pageable));
    }

    @GetMapping("/feed/following")
    public ResponseEntity<Page<PostResponse>> getFollowingFeed(
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(postService.getFollowingFeed(user.getId(), pageable));
    }

    @GetMapping("/feed/reels")
    public ResponseEntity<Page<PostResponse>> getReelFeed(
            @PageableDefault(size = 10) Pageable pageable) {
        return ResponseEntity.ok(postService.getReelFeed(pageable));
    }

    @GetMapping("/feed/reels/following")
    public ResponseEntity<Page<PostResponse>> getFollowingReelFeed(
            @PageableDefault(size = 10) Pageable pageable,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(postService.getFollowingReelFeed(user.getId(), pageable));
    }

    @GetMapping("/user/{authorId}")
    public ResponseEntity<Page<PostResponse>> getUserPosts(
            @PathVariable UUID authorId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(postService.getUserPosts(authorId, pageable));
    }

    @GetMapping("/search")
    public ResponseEntity<Page<PostResponse>> searchPosts(
            @RequestParam String q,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(postService.searchPosts(q, pageable));
    }

    // ── React ─────────────────────────────────────────────────

    @PostMapping("/{postId}/react")
    public ResponseEntity<PostResponse> react(
            @PathVariable UUID postId,
            @Valid @RequestBody ReactToPostRequest req,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(postService.reactToPost(postId, user.getId(), req));
    }

    @DeleteMapping("/{postId}/react")
    public ResponseEntity<Void> removeReaction(
            @PathVariable UUID postId,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        postService.removeReaction(postId, user.getId());
        return ResponseEntity.noContent().build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  REPOST / RESHARE
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Repost a post (Facebook-style). Creates a new post in the sharer's feed
     * with optional caption. The original post is embedded in the response.
     */
    @PostMapping("/{postId}/repost")
    public ResponseEntity<PostResponse> repostPost(
            @PathVariable UUID postId,
            @RequestParam(required = false) String caption,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(postService.repostPost(postId, user.getId(), caption));
    }

    /**
     * Undo a repost. Removes the repost from the user's feed.
     */
    @DeleteMapping("/{postId}/repost")
    public ResponseEntity<Void> undoRepost(
            @PathVariable UUID postId,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        postService.undoRepost(postId, user.getId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Legacy share endpoint — now delegates to repost.
     */
    @PostMapping("/{postId}/share")
    public ResponseEntity<PostResponse> sharePost(
            @PathVariable UUID postId,
            @RequestParam(required = false) String caption,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(postService.sharePost(postId, user.getId(), caption));
    }

    // ── Delete ────────────────────────────────────────────────

    @DeleteMapping("/{postId}")
    public ResponseEntity<Void> deletePost(
            @PathVariable UUID postId,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        postService.deletePost(postId, user.getId());
        return ResponseEntity.noContent().build();
    }
}

package ak.dev.irc.app.post.controller;


import ak.dev.irc.app.post.dto.CreateCommentRequest;
import ak.dev.irc.app.post.dto.EditCommentRequest;
import ak.dev.irc.app.post.dto.ReactToPostRequest;
import ak.dev.irc.app.post.dto.CommentResponse;
import ak.dev.irc.app.post.service.PostCommentService;
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

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/posts/{postId}/comments")
@RequiredArgsConstructor
public class PostCommentController {

    private final PostCommentService commentService;

    @PostMapping
    public ResponseEntity<CommentResponse> addComment(
            @PathVariable UUID postId,
            @Valid @RequestBody CreateCommentRequest req,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(commentService.addComment(postId, user.getId(), req));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CommentResponse> addCommentWithMedia(
            @PathVariable UUID postId,
            @Valid @RequestPart("data") CreateCommentRequest req,
            @RequestPart(value = "media", required = false) MultipartFile media,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(commentService.addCommentWithMedia(postId, user.getId(), req, media));
    }

    @GetMapping
    public ResponseEntity<Page<CommentResponse>> getComments(
            @PathVariable UUID postId,
            @AuthenticationPrincipal User user,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(commentService.getTopLevelComments(postId,
                user != null ? user.getId() : null, pageable));
    }

    @GetMapping("/{commentId}/replies")
    public ResponseEntity<Page<CommentResponse>> getReplies(
            @PathVariable UUID postId,
            @PathVariable UUID commentId,
            @AuthenticationPrincipal User user,
            @PageableDefault(size = 10) Pageable pageable) {
        return ResponseEntity.ok(commentService.getReplies(commentId,
                user != null ? user.getId() : null, pageable));
    }

    @PostMapping("/{commentId}/react")
    public ResponseEntity<CommentResponse> reactToComment(
            @PathVariable UUID postId,
            @PathVariable UUID commentId,
            @Valid @RequestBody ReactToPostRequest req,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(commentService.reactToComment(commentId, user.getId(), req));
    }

    @DeleteMapping("/{commentId}/react")
    public ResponseEntity<Void> removeCommentReaction(
            @PathVariable UUID postId,
            @PathVariable UUID commentId,
            @AuthenticationPrincipal User user) {
        commentService.removeCommentReaction(commentId, user.getId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{commentId}")
    public ResponseEntity<Void> deleteComment(
            @PathVariable UUID postId,
            @PathVariable UUID commentId,
            @AuthenticationPrincipal User user) {
        commentService.deleteComment(postId, commentId, user.getId());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{commentId}")
    public ResponseEntity<CommentResponse> editComment(
            @PathVariable UUID postId,
            @PathVariable UUID commentId,
            @Valid @RequestBody EditCommentRequest req,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(commentService.editComment(postId, commentId, user.getId(), req));
    }
}

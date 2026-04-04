package ak.dev.irc.app.post.controller;


import ak.dev.irc.app.post.dto.CreateCommentRequest;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

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

    @DeleteMapping("/{commentId}")
    public ResponseEntity<Void> deleteComment(
            @PathVariable UUID postId,
            @PathVariable UUID commentId,
            @AuthenticationPrincipal User user) {
        commentService.deleteComment(commentId, user.getId());
        return ResponseEntity.noContent().build();
    }
}

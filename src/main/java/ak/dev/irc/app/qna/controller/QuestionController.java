package ak.dev.irc.app.qna.controller;

import ak.dev.irc.app.qna.dto.request.CreateAnswerRequest;
import ak.dev.irc.app.qna.dto.request.CreateQuestionRequest;
import ak.dev.irc.app.qna.dto.request.EditAnswerRequest;
import ak.dev.irc.app.qna.dto.request.EditQuestionRequest;
import ak.dev.irc.app.qna.dto.response.QuestionAnswerResponse;
import ak.dev.irc.app.qna.dto.response.QuestionResponse;
import ak.dev.irc.app.qna.service.QuestionService;
import ak.dev.irc.app.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/questions")
@RequiredArgsConstructor
public class QuestionController {

    private final QuestionService questionService;

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<QuestionResponse> createQuestion(
            @Valid @RequestBody CreateQuestionRequest request,
            @AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(questionService.createQuestion(request, user.getId()));
    }

    @GetMapping
    public ResponseEntity<Page<QuestionResponse>> getFeed(@PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(questionService.getFeed(pageable));
    }

    @GetMapping("/feed/following")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<QuestionResponse>> getFollowingFeed(
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(questionService.getFollowingFeed(user.getId(), pageable));
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<QuestionResponse>> getMyQuestions(
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(questionService.getMyQuestions(user.getId(), pageable));
    }

    @PatchMapping("/{questionId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<QuestionResponse> editQuestion(
            @PathVariable UUID questionId,
            @Valid @RequestBody EditQuestionRequest request,
            @AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(questionService.editQuestion(questionId, request, user.getId()));
    }

    @GetMapping("/{questionId}")
    public ResponseEntity<QuestionResponse> getQuestion(@PathVariable UUID questionId) {
        return ResponseEntity.ok(questionService.getQuestion(questionId));
    }

    @GetMapping("/{questionId}/answers")
    public ResponseEntity<Page<QuestionAnswerResponse>> getAnswers(
            @PathVariable UUID questionId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(questionService.getAnswers(questionId, pageable));
    }

    @PostMapping("/{questionId}/answers")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<QuestionAnswerResponse> addAnswer(
            @PathVariable UUID questionId,
            @Valid @RequestBody CreateAnswerRequest request,
            @AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(questionService.addAnswer(questionId, request, user.getId()));
    }

    @PatchMapping("/{questionId}/answers/{answerId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<QuestionAnswerResponse> editAnswer(
            @PathVariable UUID questionId,
            @PathVariable UUID answerId,
            @Valid @RequestBody EditAnswerRequest request,
            @AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(questionService.editAnswer(questionId, answerId, request, user.getId()));
    }

    @DeleteMapping("/{questionId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteQuestion(
            @PathVariable UUID questionId,
            @AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        questionService.deleteQuestion(questionId, user.getId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{questionId}/answers/{answerId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteAnswer(
            @PathVariable UUID questionId,
            @PathVariable UUID answerId,
            @AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        questionService.deleteAnswer(questionId, answerId, user.getId());
        return ResponseEntity.noContent().build();
    }
}
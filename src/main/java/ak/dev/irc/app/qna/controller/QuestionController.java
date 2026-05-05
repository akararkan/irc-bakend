package ak.dev.irc.app.qna.controller;

import ak.dev.irc.app.post.dto.CursorPage;
import ak.dev.irc.app.qna.dto.request.*;
import ak.dev.irc.app.qna.dto.response.*;
import ak.dev.irc.app.qna.realtime.QnaRealtimeService;
import ak.dev.irc.app.qna.service.QuestionService;
import ak.dev.irc.app.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/questions")
@RequiredArgsConstructor
public class QuestionController {

    private final QuestionService questionService;
    private final QnaRealtimeService qnaRealtimeService;

    // ══════════════════════════════════════════════════════════════════════════
    //  QUESTIONS
    // ══════════════════════════════════════════════════════════════════════════

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<QuestionResponse> createQuestion(
            @Valid @RequestBody CreateQuestionRequest request,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(questionService.createQuestion(request, user.getId()));
    }

    @GetMapping
    public ResponseEntity<Page<QuestionResponse>> getFeed(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(questionService.getFeed(pageable));
    }

    /**
     * Cursor-paginated question feed (preferred for infinite-scroll clients).
     * - First page: omit {@code cursor}.
     * - Next page: pass {@code nextCursor} from the previous response.
     * - End of feed: response body has {@code nextCursor: null} and {@code hasMore: false}.
     */
    @GetMapping("/feed/cursor")
    public ResponseEntity<CursorPage<QuestionResponse>> getFeedCursor(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(questionService.getFeedCursor(cursor, limit));
    }

    @GetMapping("/feed/following")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<QuestionResponse>> getFollowingFeed(
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(questionService.getFollowingFeed(user.getId(), pageable));
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<QuestionResponse>> getMyQuestions(
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(questionService.getMyQuestions(user.getId(), pageable));
    }

    @PatchMapping("/{questionId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<QuestionResponse> editQuestion(
            @PathVariable UUID questionId,
            @Valid @RequestBody EditQuestionRequest request,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(questionService.editQuestion(questionId, request, user.getId()));
    }

    @GetMapping("/{questionId}")
    public ResponseEntity<QuestionResponse> getQuestion(@PathVariable UUID questionId) {
        return ResponseEntity.ok(questionService.getQuestion(questionId));
    }

    /**
     * Live event stream for a single question.
     *
     * <p>Subscribers receive every answer, reanswer, reaction, accept/unaccept,
     * feedback and lifecycle update on this question in near real time.
     * Event names mirror {@code QnaRealtimeEventType}; payload schema is
     * {@code QnaRealtimeEvent}. A {@code connected} handshake fires on
     * subscribe and a {@code heartbeat} every 25 s.</p>
     */
    @GetMapping(value = "/{questionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamQuestion(
            @PathVariable UUID questionId,
            @AuthenticationPrincipal User user) {
        // Touch the question first so a missing/deleted question fails fast
        // with the standard 404 instead of opening a zombie SSE.
        questionService.getQuestion(questionId);
        return qnaRealtimeService.subscribe(questionId, user != null ? user.getId() : null);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ANSWER CONTROLS
    // ══════════════════════════════════════════════════════════════════════════

    @PostMapping("/{questionId}/lock-answers")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<QuestionResponse> lockAnswers(
            @PathVariable UUID questionId,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(questionService.lockAnswers(questionId, user.getId()));
    }

    @DeleteMapping("/{questionId}/lock-answers")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<QuestionResponse> unlockAnswers(
            @PathVariable UUID questionId,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(questionService.unlockAnswers(questionId, user.getId()));
    }

    @PatchMapping("/{questionId}/answer-limit")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<QuestionResponse> setAnswerLimit(
            @PathVariable UUID questionId,
            @RequestParam(required = false) Integer maxAnswers,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(questionService.setAnswerLimit(questionId, maxAnswers, user.getId()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ANSWERS
    // ══════════════════════════════════════════════════════════════════════════

    @GetMapping("/{questionId}/answers")
    public ResponseEntity<Page<QuestionAnswerResponse>> getAnswers(
            @PathVariable UUID questionId,
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal User user) {
        UUID requesterId = user != null ? user.getId() : null;
        return ResponseEntity.ok(questionService.getAnswers(questionId, requesterId, pageable));
    }

    @PostMapping("/{questionId}/answers")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<QuestionAnswerResponse> addAnswer(
            @PathVariable UUID questionId,
            @Valid @RequestBody CreateAnswerRequest request,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(questionService.addAnswer(questionId, request, user.getId()));
    }

    /**
     * Comment-style one-shot create — upload an inline media file (image or
     * video) and an optional voice note alongside the answer body in a single
     * multipart request. Same shape as {@code POST /api/v1/posts/{id}/comments/upload}
     * so the front-end can reuse its comment composer for answers.
     */
    @PostMapping(value = "/{questionId}/answers/upload",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<QuestionAnswerResponse> addAnswerWithMedia(
            @PathVariable UUID questionId,
            @Valid @RequestPart("data") CreateAnswerRequest request,
            @RequestPart(value = "media", required = false) org.springframework.web.multipart.MultipartFile media,
            @RequestPart(value = "voice", required = false) org.springframework.web.multipart.MultipartFile voice,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(questionService.addAnswerWithMedia(questionId, request, user.getId(), media, voice));
    }

    /** Reanswer multipart variant — same shape, sets {@code parentAnswerId} on the request. */
    @PostMapping(value = {
            "/{questionId}/answers/{answerId}/reanswers/upload",
            "/{questionId}/answers/{answerId}/replies/upload"
    }, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<QuestionAnswerResponse> addReanswerWithMedia(
            @PathVariable UUID questionId,
            @PathVariable UUID answerId,
            @Valid @RequestPart("data") CreateAnswerRequest request,
            @RequestPart(value = "media", required = false) org.springframework.web.multipart.MultipartFile media,
            @RequestPart(value = "voice", required = false) org.springframework.web.multipart.MultipartFile voice,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        request.setParentAnswerId(answerId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(questionService.addAnswerWithMedia(questionId, request, user.getId(), media, voice));
    }

    @GetMapping({
            "/{questionId}/answers/{answerId}/reanswers",
            "/{questionId}/answers/{answerId}/replies"
    })
    public ResponseEntity<Page<QuestionAnswerResponse>> getReanswers(
            @PathVariable UUID questionId,
            @PathVariable UUID answerId,
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal User user) {
        UUID requesterId = user != null ? user.getId() : null;
        return ResponseEntity.ok(questionService.getReanswers(questionId, answerId, requesterId, pageable));
    }

    @PostMapping({
            "/{questionId}/answers/{answerId}/reanswers",
            "/{questionId}/answers/{answerId}/replies"
    })
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<QuestionAnswerResponse> addReanswer(
            @PathVariable UUID questionId,
            @PathVariable UUID answerId,
            @Valid @RequestBody CreateAnswerRequest request,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        request.setParentAnswerId(answerId);
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
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(questionService.editAnswer(questionId, answerId, request, user.getId()));
    }

    @DeleteMapping("/{questionId}/answers/{answerId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteAnswer(
            @PathVariable UUID questionId,
            @PathVariable UUID answerId,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        questionService.deleteAnswer(questionId, answerId, user.getId());
        return ResponseEntity.noContent().build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  REACTIONS (apply to top-level answers AND reanswers)
    // ══════════════════════════════════════════════════════════════════════════

    @PostMapping("/{questionId}/answers/{answerId}/react")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<QuestionAnswerResponse> reactToAnswer(
            @PathVariable UUID questionId,
            @PathVariable UUID answerId,
            @Valid @RequestBody ReactToAnswerRequest request,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(questionService.reactToAnswer(questionId, answerId, request, user.getId()));
    }

    @DeleteMapping("/{questionId}/answers/{answerId}/react")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> removeAnswerReaction(
            @PathVariable UUID questionId,
            @PathVariable UUID answerId,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        questionService.removeAnswerReaction(questionId, answerId, user.getId());
        return ResponseEntity.noContent().build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ACCEPT / UNACCEPT (multiple best answers)
    // ══════════════════════════════════════════════════════════════════════════

    @PostMapping("/{questionId}/answers/{answerId}/accept")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<QuestionAnswerResponse> acceptAnswer(
            @PathVariable UUID questionId,
            @PathVariable UUID answerId,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(questionService.acceptAnswer(questionId, answerId, user.getId()));
    }

    @DeleteMapping("/{questionId}/answers/{answerId}/accept")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<QuestionAnswerResponse> unacceptAnswer(
            @PathVariable UUID questionId,
            @PathVariable UUID answerId,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(questionService.unacceptAnswer(questionId, answerId, user.getId()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  FEEDBACK
    // ══════════════════════════════════════════════════════════════════════════

    @PostMapping("/{questionId}/answers/{answerId}/feedback")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AnswerFeedbackResponse> addFeedback(
            @PathVariable UUID questionId,
            @PathVariable UUID answerId,
            @Valid @RequestBody AddFeedbackRequest request,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(questionService.addFeedback(questionId, answerId, request, user.getId()));
    }

    @PatchMapping("/{questionId}/answers/{answerId}/feedback/{feedbackId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AnswerFeedbackResponse> editFeedback(
            @PathVariable UUID questionId,
            @PathVariable UUID answerId,
            @PathVariable UUID feedbackId,
            @Valid @RequestBody AddFeedbackRequest request,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(questionService.editFeedback(questionId, answerId, feedbackId, request, user.getId()));
    }

    @GetMapping("/{questionId}/answers/{answerId}/feedback")
    public ResponseEntity<List<AnswerFeedbackResponse>> getFeedback(
            @PathVariable UUID questionId,
            @PathVariable UUID answerId) {
        return ResponseEntity.ok(questionService.getFeedback(questionId, answerId));
    }

    @DeleteMapping("/{questionId}/answers/{answerId}/feedback/{feedbackId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteFeedback(
            @PathVariable UUID questionId,
            @PathVariable UUID answerId,
            @PathVariable UUID feedbackId,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        questionService.deleteFeedback(questionId, answerId, feedbackId, user.getId());
        return ResponseEntity.noContent().build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ATTACHMENTS (file uploads per answer)
    // ══════════════════════════════════════════════════════════════════════════

    @PostMapping(value = "/{questionId}/answers/{answerId}/attachments",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AnswerAttachmentResponse> uploadAttachment(
            @PathVariable UUID questionId,
            @PathVariable UUID answerId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "caption", required = false) String caption,
            @RequestParam(value = "displayOrder", required = false) Integer displayOrder,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(questionService.uploadAttachment(questionId, answerId, file, caption, displayOrder, user.getId()));
    }

    @GetMapping("/{questionId}/answers/{answerId}/attachments")
    public ResponseEntity<List<AnswerAttachmentResponse>> getAttachments(
            @PathVariable UUID questionId,
            @PathVariable UUID answerId) {
        return ResponseEntity.ok(questionService.getAttachments(questionId, answerId));
    }

    @PatchMapping("/{questionId}/answers/{answerId}/attachments/{attachmentId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AnswerAttachmentResponse> updateAttachment(
            @PathVariable UUID questionId,
            @PathVariable UUID answerId,
            @PathVariable UUID attachmentId,
            @Valid @RequestBody UpdateAnswerAttachmentRequest request,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(questionService.updateAttachment(questionId, answerId, attachmentId, request, user.getId()));
    }

    @DeleteMapping("/{questionId}/answers/{answerId}/attachments/{attachmentId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteAttachment(
            @PathVariable UUID questionId,
            @PathVariable UUID answerId,
            @PathVariable UUID attachmentId,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        questionService.deleteAttachment(questionId, answerId, attachmentId, user.getId());
        return ResponseEntity.noContent().build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  SOURCES / REFERENCES (per answer)
    // ══════════════════════════════════════════════════════════════════════════

    @PostMapping("/{questionId}/answers/{answerId}/sources")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AnswerSourceResponse> addSource(
            @PathVariable UUID questionId,
            @PathVariable UUID answerId,
            @Valid @RequestBody CreateAnswerSourceRequest request,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(questionService.addSource(questionId, answerId, request, user.getId()));
    }

    @GetMapping("/{questionId}/answers/{answerId}/sources")
    public ResponseEntity<List<AnswerSourceResponse>> getSources(
            @PathVariable UUID questionId,
            @PathVariable UUID answerId) {
        return ResponseEntity.ok(questionService.getSources(questionId, answerId));
    }

    @PatchMapping("/{questionId}/answers/{answerId}/sources/{sourceId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AnswerSourceResponse> updateSource(
            @PathVariable UUID questionId,
            @PathVariable UUID answerId,
            @PathVariable UUID sourceId,
            @Valid @RequestBody UpdateAnswerSourceRequest request,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(questionService.updateSource(questionId, answerId, sourceId, request, user.getId()));
    }

    @DeleteMapping("/{questionId}/answers/{answerId}/sources/{sourceId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteSource(
            @PathVariable UUID questionId,
            @PathVariable UUID answerId,
            @PathVariable UUID sourceId,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        questionService.deleteSource(questionId, answerId, sourceId, user.getId());
        return ResponseEntity.noContent().build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  DELETE QUESTION
    // ══════════════════════════════════════════════════════════════════════════

    @DeleteMapping("/{questionId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteQuestion(
            @PathVariable UUID questionId,
            @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        questionService.deleteQuestion(questionId, user.getId());
        return ResponseEntity.noContent().build();
    }
}

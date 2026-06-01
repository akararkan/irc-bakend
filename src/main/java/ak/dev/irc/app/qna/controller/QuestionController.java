package ak.dev.irc.app.qna.controller;

import ak.dev.irc.app.post.dto.CursorPage;
import ak.dev.irc.app.qna.dto.request.*;
import ak.dev.irc.app.qna.dto.response.*;
import ak.dev.irc.app.qna.realtime.QnaRealtimeService;
import ak.dev.irc.app.qna.service.QuestionService;
import ak.dev.irc.app.share.OriginUtil;
import ak.dev.irc.app.share.ShareLinkInfo;
import ak.dev.irc.app.user.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import ak.dev.irc.app.common.exception.UnauthorizedException;
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
        if (user == null) throw new UnauthorizedException("Authentication required");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(questionService.createQuestion(request, user.getId()));
    }

    @GetMapping
    public ResponseEntity<Page<QuestionResponse>> getFeed(
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal User user) {
        UUID viewerId = user != null ? user.getId() : null;
        return ResponseEntity.ok(questionService.getFeed(viewerId, pageable));
    }

    // Full-text search lives on GET /api/v1/search?types=QUESTION (see GlobalSearchController).

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
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal User user) {
        UUID viewerId = user != null ? user.getId() : null;
        return ResponseEntity.ok(questionService.getFeedCursor(viewerId, cursor, limit));
    }

    @GetMapping("/feed/following")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<QuestionResponse>> getFollowingFeed(
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        return ResponseEntity.ok(questionService.getFollowingFeed(user.getId(), pageable));
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<QuestionResponse>> getMyQuestions(
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        return ResponseEntity.ok(questionService.getMyQuestions(user.getId(), pageable));
    }

    @PatchMapping("/{questionId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<QuestionResponse> editQuestion(
            @PathVariable UUID questionId,
            @Valid @RequestBody EditQuestionRequest request,
            @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        return ResponseEntity.ok(questionService.editQuestion(questionId, request, user.getId()));
    }

    @GetMapping("/{questionId}")
    public ResponseEntity<QuestionResponse> getQuestion(
            @PathVariable UUID questionId,
            @AuthenticationPrincipal User user,
            HttpServletRequest request) {
        UUID viewerId = user != null ? user.getId() : null;
        // Dedupe key: authenticated viewers by user id, anonymous by client IP
        // so refreshing the same tab doesn't inflate the counter.
        String viewerKey = viewerId != null ? viewerId.toString() : clientFingerprint(request);
        return ResponseEntity.ok(questionService.getQuestion(questionId, viewerId, viewerKey));
    }

    /**
     * Best-effort client fingerprint for view-count dedupe of anonymous viewers.
     * Honours {@code X-Forwarded-For} so we don't deduplicate every visitor down
     * to a single proxy IP behind a load balancer.
     */
    private static String clientFingerprint(HttpServletRequest request) {
        if (request == null) return null;
        String fwd = request.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
        return request.getRemoteAddr();
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
        if (user == null) throw new UnauthorizedException("Authentication required");
        return ResponseEntity.ok(questionService.lockAnswers(questionId, user.getId()));
    }

    @DeleteMapping("/{questionId}/lock-answers")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<QuestionResponse> unlockAnswers(
            @PathVariable UUID questionId,
            @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        return ResponseEntity.ok(questionService.unlockAnswers(questionId, user.getId()));
    }

    @PatchMapping("/{questionId}/answer-limit")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<QuestionResponse> setAnswerLimit(
            @PathVariable UUID questionId,
            @RequestParam(required = false) Integer maxAnswers,
            @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
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
        if (user == null) throw new UnauthorizedException("Authentication required");
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
        if (user == null) throw new UnauthorizedException("Authentication required");
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
        if (user == null) throw new UnauthorizedException("Authentication required");
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
        if (user == null) throw new UnauthorizedException("Authentication required");
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
        if (user == null) throw new UnauthorizedException("Authentication required");
        return ResponseEntity.ok(questionService.editAnswer(questionId, answerId, request, user.getId()));
    }

    @DeleteMapping("/{questionId}/answers/{answerId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteAnswer(
            @PathVariable UUID questionId,
            @PathVariable UUID answerId,
            @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
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
            @RequestBody(required = false) ReactToAnswerRequest request,
            @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        return ResponseEntity.ok(questionService.reactToAnswer(
                questionId, answerId,
                request != null ? request : new ReactToAnswerRequest(),
                user.getId()));
    }

    @DeleteMapping("/{questionId}/answers/{answerId}/react")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<QuestionAnswerResponse> removeAnswerReaction(
            @PathVariable UUID questionId,
            @PathVariable UUID answerId,
            @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        return ResponseEntity.ok(
                questionService.removeAnswerReaction(questionId, answerId, user.getId()));
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
        if (user == null) throw new UnauthorizedException("Authentication required");
        return ResponseEntity.ok(questionService.acceptAnswer(questionId, answerId, user.getId()));
    }

    @DeleteMapping("/{questionId}/answers/{answerId}/accept")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<QuestionAnswerResponse> unacceptAnswer(
            @PathVariable UUID questionId,
            @PathVariable UUID answerId,
            @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        return ResponseEntity.ok(questionService.unacceptAnswer(questionId, answerId, user.getId()));
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
        if (user == null) throw new UnauthorizedException("Authentication required");
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
        if (user == null) throw new UnauthorizedException("Authentication required");
        return ResponseEntity.ok(questionService.updateAttachment(questionId, answerId, attachmentId, request, user.getId()));
    }

    @DeleteMapping("/{questionId}/answers/{answerId}/attachments/{attachmentId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteAttachment(
            @PathVariable UUID questionId,
            @PathVariable UUID answerId,
            @PathVariable UUID attachmentId,
            @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
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
        if (user == null) throw new UnauthorizedException("Authentication required");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(questionService.addSource(questionId, answerId, request, user.getId()));
    }

    /** Attach (or replace) the binary file for a MEDIA_FILE source. Part name: {@code file}. */
    @PostMapping(value = "/{questionId}/answers/{answerId}/sources/{sourceId}/file",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AnswerSourceResponse> uploadSourceFile(
            @PathVariable UUID questionId,
            @PathVariable UUID answerId,
            @PathVariable UUID sourceId,
            @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        return ResponseEntity.ok(
                questionService.uploadSourceFile(questionId, answerId, sourceId, file, user.getId()));
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
        if (user == null) throw new UnauthorizedException("Authentication required");
        return ResponseEntity.ok(questionService.updateSource(questionId, answerId, sourceId, request, user.getId()));
    }

    @DeleteMapping("/{questionId}/answers/{answerId}/sources/{sourceId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteSource(
            @PathVariable UUID questionId,
            @PathVariable UUID answerId,
            @PathVariable UUID sourceId,
            @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        questionService.deleteSource(questionId, answerId, sourceId, user.getId());
        return ResponseEntity.noContent().build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  SAVE / BOOKMARK   (mirrors /posts and /researches save endpoints)
    // ══════════════════════════════════════════════════════════════════════════

    /** Save (bookmark) the question into a collection. Idempotent. */
    @PostMapping("/{questionId}/save")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<QuestionResponse> saveQuestion(
            @PathVariable UUID questionId,
            @RequestParam(required = false) String collection,
            @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(questionService.saveQuestion(questionId, user.getId(), collection));
    }

    /** Remove the viewer's bookmark on the question. Idempotent. */
    @DeleteMapping("/{questionId}/save")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<QuestionResponse> unsaveQuestion(
            @PathVariable UUID questionId,
            @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        return ResponseEntity.ok(questionService.unsaveQuestion(questionId, user.getId()));
    }

    /** Page of the viewer's saved questions, newest first. */
    @GetMapping("/me/saved")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<QuestionResponse>> getSavedQuestions(
            @AuthenticationPrincipal User user,
            @PageableDefault(size = 20) Pageable pageable) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        return ResponseEntity.ok(questionService.getSavedQuestions(user.getId(), pageable));
    }

    /** Page of saved questions filtered by {@code ?name=...}. */
    @GetMapping("/me/saved/collection")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<QuestionResponse>> getSavedQuestionsByCollection(
            @RequestParam String name,
            @AuthenticationPrincipal User user,
            @PageableDefault(size = 20) Pageable pageable) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        return ResponseEntity.ok(questionService.getSavedQuestionsByCollection(user.getId(), name, pageable));
    }

    /** Distinct collection names the viewer has used. */
    @GetMapping("/me/saved/collections")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<String>> getSavedQuestionCollections(
            @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        return ResponseEntity.ok(questionService.getSavedQuestionCollections(user.getId()));
    }

    /** Rename a save collection across every save row owned by the viewer. */
    @PatchMapping("/me/saved/collections")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> renameSavedQuestionCollection(
            @RequestParam String oldName,
            @RequestParam String newName,
            @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        questionService.renameSavedQuestionCollection(user.getId(), oldName, newName);
        return ResponseEntity.noContent().build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  SHARE  — mirrors PostController copy-link / share-link
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Returns the share URL info without bumping the counter — for the inline
     * share UI before the user actually copies the link.
     */
    @GetMapping("/{questionId}/share-link")
    public ResponseEntity<ShareLinkInfo> previewShareLink(
            @PathVariable UUID questionId,
            HttpServletRequest request) {
        return ResponseEntity.ok(
                questionService.previewShareLink(questionId, OriginUtil.origin(request)));
    }

    /**
     * Atomically bumps {@code shareCount} and returns the share URL info.
     * Called when the user actually copies / sends the link.
     */
    @PostMapping("/{questionId}/share")
    public ResponseEntity<ShareLinkInfo> share(
            @PathVariable UUID questionId,
            @AuthenticationPrincipal User user,
            HttpServletRequest request) {
        UUID requesterId = user != null ? user.getId() : null;
        return ResponseEntity.ok(
                questionService.recordShare(questionId, requesterId, OriginUtil.origin(request)));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  DELETE QUESTION
    // ══════════════════════════════════════════════════════════════════════════

    @DeleteMapping("/{questionId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteQuestion(
            @PathVariable UUID questionId,
            @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        questionService.deleteQuestion(questionId, user.getId());
        return ResponseEntity.noContent().build();
    }
}

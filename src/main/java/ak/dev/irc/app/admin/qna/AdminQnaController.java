package ak.dev.irc.app.admin.qna;

import ak.dev.irc.app.admin.moderation.ModerationRecorder;
import ak.dev.irc.app.admin.support.AdminAuditor;
import ak.dev.irc.app.admin.support.RequiresStepUp;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.util.Pages;
import ak.dev.irc.app.qna.entity.Question;
import ak.dev.irc.app.qna.entity.QuestionAnswer;
import ak.dev.irc.app.qna.enums.QuestionStatus;
import ak.dev.irc.app.qna.repository.QuestionAnswerRepository;
import ak.dev.irc.app.qna.repository.QuestionRepository;
import ak.dev.irc.app.qna.service.QuestionService;
import ak.dev.irc.app.user.entity.User;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Admin Q&A oversight (blueprint §3.4). Unlike research, the QnA service layer
 * already carries the ADMIN bypass ({@code canManageQuestion}/{@code
 * canManageAnswer}) — these endpoints make those hidden powers explicit,
 * audited, and reachable, and give CLOSED/ARCHIVED their first writers.
 */
@RestController
@RequestMapping("/api/v1/admin/qna")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")   // §6 matrix: moderation parts of QnA are MODERATOR RW
public class AdminQnaController {

    private final QuestionRepository questionRepository;
    private final QuestionAnswerRepository answerRepository;
    private final QuestionService questionService;
    private final ModerationRecorder moderationRecorder;
    private final AdminAuditor adminAuditor;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminQuestionRow(UUID id, String title, String status, UUID authorId,
                                   String authorUsername, Long answerCount,
                                   Long acceptedAnswerCount, boolean answersLocked,
                                   Integer maxAnswers, Long viewCount, LocalDateTime createdAt) {
    }

    public record ReasonBody(@Size(max = 500) String reason) {
    }

    @GetMapping("/questions")
    public ResponseEntity<Page<AdminQuestionRow>> browse(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID authorId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean unanswered,
            @RequestParam(required = false) Boolean lockedOnly,
            @PageableDefault(size = 25) Pageable pageable) {
        QuestionStatus parsed = parseStatus(status);
        Page<Question> page = questionRepository.adminBrowse(parsed, authorId,
                q == null || q.isBlank() ? null : q.trim(), lockedOnly, unanswered,
                PageRequest.of(Math.max(0, pageable.getPageNumber()),
                        Pages.clamp(pageable.getPageSize())));
        return ResponseEntity.ok(page.map(AdminQnaController::toRow));
    }

    @PostMapping("/questions/{id}/close")
    public ResponseEntity<Void> close(@PathVariable UUID id,
                                      @RequestBody(required = false) ReasonBody body,
                                      @AuthenticationPrincipal User admin) {
        questionService.closeQuestion(id, admin.getId(), body != null ? body.reason() : null);
        moderationRecorder.decision("QUESTION", id.toString(), "ADMIN_QUESTION_CLOSE",
                body != null ? body.reason() : null, null, null);
        adminAuditor.record(AuditOperation.UPDATE, "Question", id, "ADMIN_QUESTION_CLOSE",
                body != null ? body.reason() : null);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/questions/{id}/reopen")
    public ResponseEntity<Void> reopen(@PathVariable UUID id, @AuthenticationPrincipal User admin) {
        questionService.reopenQuestion(id, admin.getId());
        adminAuditor.record(AuditOperation.UPDATE, "Question", id, "ADMIN_QUESTION_REOPEN");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/questions/{id}/archive")
    public ResponseEntity<Void> archive(@PathVariable UUID id, @AuthenticationPrincipal User admin) {
        questionService.archiveQuestion(id, admin.getId());
        moderationRecorder.decision("QUESTION", id.toString(), "ADMIN_QUESTION_ARCHIVE",
                null, null, null);
        adminAuditor.record(AuditOperation.UPDATE, "Question", id, "ADMIN_QUESTION_ARCHIVE");
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/questions/{id}")
    @RequiresStepUp
    public ResponseEntity<Void> deleteQuestion(@PathVariable UUID id,
                                               @RequestBody(required = false) ReasonBody body,
                                               @AuthenticationPrincipal User admin) {
        moderationRecorder.decision("QUESTION", id.toString(), "ADMIN_QUESTION_DELETE",
                body != null ? body.reason() : null, null, null);
        questionService.deleteQuestion(id, admin.getId());
        adminAuditor.record(AuditOperation.DELETE, "Question", id, "ADMIN_QUESTION_DELETE",
                body != null ? body.reason() : null);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/answers/{answerId}")
    @RequiresStepUp
    public ResponseEntity<Void> deleteAnswer(@PathVariable UUID answerId,
                                             @RequestBody(required = false) ReasonBody body,
                                             @AuthenticationPrincipal User admin) {
        QuestionAnswer answer = answerRepository.findByIdAndDeletedAtIsNull(answerId)
                .orElseThrow(() -> new ResourceNotFoundException("Answer", "id", answerId));
        UUID questionId = answer.getQuestion().getId();
        moderationRecorder.decision("ANSWER", answerId.toString(), "ADMIN_ANSWER_DELETE",
                body != null ? body.reason() : null, null, "questionId=" + questionId);
        questionService.deleteAnswer(questionId, answerId, admin.getId());
        adminAuditor.record(AuditOperation.DELETE, "QuestionAnswer", answerId,
                "ADMIN_ANSWER_DELETE", body != null ? body.reason() : null);
        return ResponseEntity.noContent().build();
    }

    private static AdminQuestionRow toRow(Question q) {
        return new AdminQuestionRow(q.getId(), q.getTitle(),
                q.getStatus() != null ? q.getStatus().name() : null,
                q.getAuthor() != null ? q.getAuthor().getId() : null,
                q.getAuthor() != null ? q.getAuthor().getUsername() : null,
                q.getAnswerCount(), q.getAcceptedAnswerCount(), q.isAnswersLocked(),
                q.getMaxAnswers(), q.getViewCount(), q.getCreatedAt());
    }

    private static QuestionStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return QuestionStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown status. Allowed: "
                    + java.util.Arrays.toString(QuestionStatus.values()), "INVALID_STATUS");
        }
    }
}

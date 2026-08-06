package ak.dev.irc.app.admin.chat;

import ak.dev.irc.app.admin.support.AdminAuditor;
import ak.dev.irc.app.admin.support.RequiresStepUp;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.chat.cassandra.entity.MessageByConversationEntity;
import ak.dev.irc.app.chat.cassandra.repository.MessageByConversationRepository;
import ak.dev.irc.app.chat.entity.Conversation;
import ak.dev.irc.app.chat.repository.ConversationRepository;
import ak.dev.irc.app.chat.util.ChatBuckets;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ConflictException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.messages.AdminOpsMessages;
import ak.dev.irc.app.common.util.Pages;
import ak.dev.irc.app.security.SecurityUtils;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chat legal holds (chat-oversight.md §8) — the dual-control message-content
 * release path. ADMIN-only end to end, step-up on every mutation:
 *
 * <ol>
 *   <li><b>open</b> — any admin, with a case reference;</li>
 *   <li><b>approve / reject</b> — a DIFFERENT admin (dual control is the
 *       point: no single account can read private messages alone);</li>
 *   <li><b>execute</b> — releases the newest ≤500 messages of that one
 *       conversation, once. The read is bucket-walked newest-first, never a
 *       scan, and the hold flips to EXECUTED so it cannot be replayed.</li>
 * </ol>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/chat/legal-holds")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class LegalHoldController {

    /** Hard cap on released messages — the "bounded" in bounded read. */
    private static final int EXECUTE_CAP = 500;
    /** Safety valve on the bucket walk for very old conversations. */
    private static final int MAX_BUCKETS = 1000;

    private final LegalHoldRepository holdRepository;
    private final ConversationRepository conversationRepository;
    private final MessageByConversationRepository messageRepository;
    private final AdminAuditor adminAuditor;

    public record OpenBody(UUID conversationId, @NotBlank String reason) {}
    public record DecisionBody(String note) {}

    @GetMapping
    public ResponseEntity<Page<LegalHold>> list(
            @RequestParam(required = false) String status,
            @PageableDefault(size = 25) Pageable pageable) {
        LegalHold.Status parsed = null;
        if (status != null && !status.isBlank()) {
            try {
                parsed = LegalHold.Status.valueOf(status.trim().toUpperCase());
            } catch (Exception e) {
                throw new BadRequestException(AdminOpsMessages.INVALID_STATUS_HOLD_MSG,
                        AdminOpsMessages.INVALID_STATUS);
            }
        }
        return ResponseEntity.ok(holdRepository.browse(parsed,
                PageRequest.of(Math.max(0, pageable.getPageNumber()),
                        Pages.clamp(pageable.getPageSize()))));
    }

    @PostMapping
    @RequiresStepUp
    public ResponseEntity<LegalHold> open(@RequestBody OpenBody body) {
        if (body.conversationId() == null) {
            throw new BadRequestException(AdminOpsMessages.INVALID_INPUT_CONVERSATION_MSG,
                    AdminOpsMessages.INVALID_INPUT);
        }
        if (body.reason() == null || body.reason().isBlank()) {
            throw new BadRequestException(
                    AdminOpsMessages.INVALID_INPUT_CASE_REF_MSG, AdminOpsMessages.INVALID_INPUT);
        }
        conversationRepository.findById(body.conversationId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Conversation", "id", body.conversationId()));
        UUID adminId = SecurityUtils.getCurrentUserId().orElse(null);
        LegalHold hold = holdRepository.save(LegalHold.builder()
                .conversationId(body.conversationId())
                .reason(body.reason().trim())
                .openedBy(adminId)
                .build());
        adminAuditor.record(AuditOperation.CREATE, "LegalHold", hold.getId(),
                "LEGAL_HOLD_OPEN", "conversation=" + body.conversationId()
                        + " reason=\"" + hold.getReason() + "\"");
        return ResponseEntity.status(201).body(hold);
    }

    /** Second-admin approval — the opener can never approve their own hold. */
    @PostMapping("/{id}/approve")
    @RequiresStepUp
    public ResponseEntity<LegalHold> approve(@PathVariable UUID id,
                                             @RequestBody(required = false) DecisionBody body) {
        LegalHold hold = require(id);
        requireStatus(hold, LegalHold.Status.OPEN, "approve");
        UUID adminId = SecurityUtils.getCurrentUserId().orElse(null);
        if (adminId != null && adminId.equals(hold.getOpenedBy())) {
            throw new ConflictException(AdminOpsMessages.LEGAL_HOLD_DUAL_CONTROL_MSG);
        }
        hold.setStatus(LegalHold.Status.APPROVED);
        hold.setApprovedBy(adminId);
        hold.setApprovedAt(LocalDateTime.now());
        if (body != null && body.note() != null) hold.setDecisionNote(trim500(body.note()));
        hold = holdRepository.save(hold);
        adminAuditor.record(AuditOperation.UPDATE, "LegalHold", id, "LEGAL_HOLD_APPROVE",
                "conversation=" + hold.getConversationId());
        return ResponseEntity.ok(hold);
    }

    @PostMapping("/{id}/reject")
    @RequiresStepUp
    public ResponseEntity<LegalHold> reject(@PathVariable UUID id,
                                            @RequestBody(required = false) DecisionBody body) {
        LegalHold hold = require(id);
        requireStatus(hold, LegalHold.Status.OPEN, "reject");
        UUID adminId = SecurityUtils.getCurrentUserId().orElse(null);
        hold.setStatus(LegalHold.Status.REJECTED);
        hold.setApprovedBy(adminId);
        hold.setApprovedAt(LocalDateTime.now());
        if (body != null && body.note() != null) hold.setDecisionNote(trim500(body.note()));
        hold = holdRepository.save(hold);
        adminAuditor.record(AuditOperation.UPDATE, "LegalHold", id, "LEGAL_HOLD_REJECT",
                "conversation=" + hold.getConversationId());
        return ResponseEntity.ok(hold);
    }

    /**
     * The content release. APPROVED holds only, once; the newest ≤500 messages
     * of the held conversation, bucket-walked newest-first.
     */
    @PostMapping("/{id}/execute")
    @RequiresStepUp
    public ResponseEntity<Map<String, Object>> execute(@PathVariable UUID id) {
        LegalHold hold = require(id);
        requireStatus(hold, LegalHold.Status.APPROVED, "execute");
        UUID adminId = SecurityUtils.getCurrentUserId().orElse(null);

        Conversation conversation = conversationRepository.findById(hold.getConversationId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Conversation", "id", hold.getConversationId()));

        List<Map<String, Object>> messages = boundedRead(conversation);

        hold.setStatus(LegalHold.Status.EXECUTED);
        hold.setExecutedBy(adminId);
        hold.setExecutedAt(LocalDateTime.now());
        hold.setMessageCount(messages.size());
        holdRepository.save(hold);
        adminAuditor.record(AuditOperation.READ, "LegalHold", id, "LEGAL_HOLD_EXECUTE",
                "conversation=" + hold.getConversationId()
                        + " released=" + messages.size() + " messages");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("holdId", id);
        body.put("conversationId", hold.getConversationId());
        body.put("reason", hold.getReason());
        body.put("openedBy", hold.getOpenedBy());
        body.put("approvedBy", hold.getApprovedBy());
        body.put("executedBy", adminId);
        body.put("messageCount", messages.size());
        body.put("capped", messages.size() >= EXECUTE_CAP);
        body.put("messages", messages);
        body.put("warning", AdminOpsMessages.WARN_LEGAL_HOLD_EXPORT.formatted(hold.getReason()));
        return ResponseEntity.ok(body);
    }

    // ── bounded newest-first bucket walk ────────────────────────────────

    private List<Map<String, Object>> boundedRead(Conversation conversation) {
        List<Map<String, Object>> out = new ArrayList<>(Math.min(EXECUTE_CAP, 128));
        int minBucket = conversation.getCreatedAt() != null
                ? ChatBuckets.bucketForTimestamp(
                        conversation.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli())
                : 0;
        int bucket = ChatBuckets.currentBucket();
        int walked = 0;
        while (bucket >= minBucket && out.size() < EXECUTE_CAP && walked++ < MAX_BUCKETS) {
            try {
                List<MessageByConversationEntity> page = messageRepository.firstPage(
                        conversation.getId(), bucket, EXECUTE_CAP - out.size());
                for (MessageByConversationEntity m : page) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("messageId", m.getMessageId());
                    row.put("senderId", m.getSenderId());
                    row.put("type", m.getType());
                    row.put("body", m.getBody());
                    row.put("createdAt", m.getCreatedAt());
                    row.put("editedAt", m.getEditedAt());
                    row.put("deleted", Boolean.TRUE.equals(m.getDeleted()));
                    row.put("replyToId", m.getReplyToId());
                    row.put("forwardedFrom", m.getForwardedFrom());
                    if (m.getMedia() != null && !m.getMedia().isEmpty()) {
                        row.put("mediaCount", m.getMedia().size());
                    }
                    out.add(row);
                    if (out.size() >= EXECUTE_CAP) break;
                }
            } catch (Exception e) {
                log.warn("[LEGAL-HOLD] bucket {} read failed for {}: {}",
                        bucket, conversation.getId(), e.getMessage());
            }
            bucket--;
        }
        return out;
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private LegalHold require(UUID id) {
        return holdRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("LegalHold", "id", id));
    }

    private static void requireStatus(LegalHold hold, LegalHold.Status expected, String verb) {
        if (hold.getStatus() != expected) {
            throw new BadRequestException(AdminOpsMessages.LEGAL_HOLD_WRONG_STATE_MSG
                    .formatted(verb, hold.getStatus(), expected, verb),
                    AdminOpsMessages.LEGAL_HOLD_WRONG_STATE);
        }
    }

    private static String trim500(String s) {
        String t = s.trim();
        return t.length() > 500 ? t.substring(0, 500) : t;
    }
}

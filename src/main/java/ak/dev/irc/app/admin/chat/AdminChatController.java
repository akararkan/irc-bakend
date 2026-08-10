package ak.dev.irc.app.admin.chat;

import ak.dev.irc.app.chat.entity.CallSession;
import ak.dev.irc.app.chat.entity.Conversation;
import ak.dev.irc.app.chat.enums.CallStatus;
import ak.dev.irc.app.chat.enums.CallType;
import ak.dev.irc.app.chat.enums.ConversationType;
import ak.dev.irc.app.chat.enums.MessageRequestStatus;
import ak.dev.irc.app.chat.repository.CallSessionRepository;
import ak.dev.irc.app.chat.repository.ConversationRepository;
import ak.dev.irc.app.chat.repository.MessageRequestRepository;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.messages.AdminOpsMessages;
import ak.dev.irc.app.common.util.Pages;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chat-plane metadata oversight (blueprint §3.5, chat-channels-live.md
 * §4.8/§5): conversation aggregates + metadata browse (channels/groups —
 * never a DM enumeration, never {@code last_message_preview}), call-session
 * metadata (no content exists — calls are P2P), and message-request
 * quarantine stats (the platform's best organic spam signal).
 */
@RestController
@RequestMapping("/api/v1/admin/chat")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")   // §6 matrix: chat metadata oversight is MODERATOR-visible
public class AdminChatController {

    private final ConversationRepository conversationRepository;
    private final CallSessionRepository callSessionRepository;
    private final MessageRequestRepository messageRequestRepository;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminConversationRow(UUID id, String type, String title, String handle,
                                       boolean publicChannel, boolean verified, int memberCount,
                                       int postCount, UUID ownerId, Integer disappearingSeconds,
                                       LocalDateTime createdAt, LocalDateTime deletedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminCallRow(UUID id, UUID conversationId, UUID initiatorId, String type,
                               String status, String endReason,
                               Instant startedAt, Instant answeredAt, Instant endedAt) {
    }

    /**
     * Metadata browse over GROUP/CHANNEL conversations. DIRECT conversations
     * are deliberately not enumerable ("who talks to whom" is surveillance
     * metadata — aggregate-only per §5.1); requesting them 400s.
     */
    @GetMapping("/conversations")
    public ResponseEntity<Page<AdminConversationRow>> conversations(
            @RequestParam(defaultValue = "CHANNEL") String type,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID ownerId,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            @PageableDefault(size = 25) Pageable pageable) {
        ConversationType parsed = parseType(type);
        if (parsed == ConversationType.DIRECT) {
            throw new BadRequestException(
                    AdminOpsMessages.DM_BROWSE_FORBIDDEN_MSG,
                    AdminOpsMessages.DM_BROWSE_FORBIDDEN);
        }
        Page<Conversation> page = conversationRepository.adminBrowse(parsed,
                q == null || q.isBlank() ? null : q.trim(), null, null, null,
                includeDeleted, ownerId,
                PageRequest.of(Math.max(0, pageable.getPageNumber()),
                        Pages.clamp(pageable.getPageSize())));
        return ResponseEntity.ok(page.map(c -> new AdminConversationRow(
                c.getId(), c.getType() != null ? c.getType().name() : null,
                c.getTitle(), c.getHandle(), c.isPublicChannel(), c.isVerified(),
                c.getMemberCount(), c.getPostCount(), c.getOwnerId(),
                c.getDisappearingSeconds() > 0 ? c.getDisappearingSeconds() : null,
                c.getCreatedAt(), c.getDeletedAt())));
    }

    /** Conversation totals by type — the aggregate DM view (§5.1). */
    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview() {
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Long> byType = new LinkedHashMap<>();
        for (ConversationType t : ConversationType.values()) byType.put(t.name(), 0L);
        for (Object[] row : conversationRepository.countLiveGroupedByType()) {
            byType.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        body.put("conversationsByType", byType);
        body.put("verifiedChannels",
                conversationRepository.countByTypeAndVerifiedTrueAndDeletedAtIsNull(ConversationType.CHANNEL));
        body.put("pendingMessageRequests",
                messageRequestRepository.countByStatus(MessageRequestStatus.PENDING));
        return ResponseEntity.ok(body);
    }

    // ── calls ───────────────────────────────────────────────────────────

    @GetMapping("/calls")
    public ResponseEntity<Page<AdminCallRow>> calls(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @PageableDefault(size = 25) Pageable pageable) {
        Page<CallSession> page = callSessionRepository.adminBrowse(
                parseCallType(type), parseCallStatus(status),
                from == null ? null : from.toInstant(ZoneOffset.UTC),
                to == null ? null : to.toInstant(ZoneOffset.UTC),
                PageRequest.of(Math.max(0, pageable.getPageNumber()),
                        Pages.clamp(pageable.getPageSize())));
        return ResponseEntity.ok(page.map(c -> new AdminCallRow(
                c.getId(), c.getConversationId(), c.getInitiatorId(),
                c.getType() != null ? c.getType().name() : null,
                c.getStatus() != null ? c.getStatus().name() : null,
                c.getEndReason(), c.getStartedAt(), c.getAnsweredAt(), c.getEndedAt())));
    }

    @GetMapping("/calls/stats")
    public ResponseEntity<Map<String, Object>> callStats(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        Instant f = (from == null ? LocalDateTime.now().minusDays(30) : from).toInstant(ZoneOffset.UTC);
        Instant t = (to == null ? LocalDateTime.now() : to).toInstant(ZoneOffset.UTC);
        Map<String, Long> byStatus = new LinkedHashMap<>();
        long total = 0, answered = 0, missed = 0;
        for (Object[] row : callSessionRepository.countByStatusBetween(f, t)) {
            String status = String.valueOf(row[0]);
            long n = ((Number) row[1]).longValue();
            byStatus.put(status, n);
            total += n;
            if ("ENDED".equals(status) || "ONGOING".equals(status)) answered += n;
            if ("MISSED".equals(status)) missed += n;
        }
        Map<String, Long> byType = new LinkedHashMap<>();
        for (Object[] row : callSessionRepository.countByTypeBetween(f, t)) {
            byType.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", f);
        body.put("to", t);
        body.put("total", total);
        body.put("byStatus", byStatus);
        body.put("byType", byType);
        body.put("answerRate", total == 0 ? 0.0 : Math.round(answered * 1000.0 / total) / 10.0);
        body.put("missedRate", total == 0 ? 0.0 : Math.round(missed * 1000.0 / total) / 10.0);
        return ResponseEntity.ok(body);
    }

    // ── message-request quarantine ──────────────────────────────────────

    @GetMapping("/message-requests/stats")
    public ResponseEntity<Map<String, Object>> messageRequestStats(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "20") int topRequesters) {
        LocalDateTime f = from == null ? LocalDateTime.now().minusDays(30) : from;
        LocalDateTime t = to == null ? LocalDateTime.now() : to;

        Map<String, Long> funnel = new LinkedHashMap<>();
        for (MessageRequestStatus s : MessageRequestStatus.values()) funnel.put(s.name(), 0L);
        for (Object[] row : messageRequestRepository.countGroupedByStatus()) {
            funnel.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        Map<String, Long> windowed = new LinkedHashMap<>();
        for (Object[] row : messageRequestRepository.countByStatusBetween(f, t)) {
            windowed.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        List<Map<String, Object>> spam = new ArrayList<>();
        for (Object[] row : messageRequestRepository.topBlockedRequesters(Pages.clamp(topRequesters))) {
            spam.add(Map.of("requesterId", row[0],
                    "blockedByDistinctRecipients", ((Number) row[1]).longValue()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("funnelAllTime", funnel);
        body.put("windowed", windowed);
        body.put("topBlockedRequesters", spam);
        return ResponseEntity.ok(body);
    }

    // ── parse helpers ───────────────────────────────────────────────────

    private static ConversationType parseType(String raw) {
        try {
            return ConversationType.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            throw new BadRequestException(AdminOpsMessages.INVALID_TYPE_CONVERSATION_MSG,
                    AdminOpsMessages.INVALID_TYPE);
        }
    }

    private static CallType parseCallType(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return CallType.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            throw new BadRequestException(AdminOpsMessages.INVALID_TYPE_CALL_MSG,
                    AdminOpsMessages.INVALID_TYPE);
        }
    }

    private static CallStatus parseCallStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return CallStatus.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            throw new BadRequestException(AdminOpsMessages.INVALID_STATUS_CALL_MSG.formatted(
                    java.util.Arrays.toString(CallStatus.values())),
                    AdminOpsMessages.INVALID_STATUS);
        }
    }
}

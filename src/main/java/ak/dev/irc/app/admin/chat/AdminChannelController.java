package ak.dev.irc.app.admin.chat;

import ak.dev.irc.app.admin.moderation.ModerationRecorder;
import ak.dev.irc.app.admin.support.AdminAuditor;
import ak.dev.irc.app.admin.support.RequiresStepUp;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.chat.dto.response.ChannelStatsResponse;
import ak.dev.irc.app.chat.entity.Conversation;
import ak.dev.irc.app.chat.enums.ConversationType;
import ak.dev.irc.app.chat.repository.ConversationInviteRepository;
import ak.dev.irc.app.chat.repository.ConversationRepository;
import ak.dev.irc.app.chat.search.service.ChannelSearchService;
import ak.dev.irc.app.chat.service.ChannelService;
import ak.dev.irc.app.chat.service.ChannelStatsService;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.messages.AdminOpsMessages;
import ak.dev.irc.app.common.util.Pages;
import ak.dev.irc.app.user.service.NotificationService;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Channel administration (blueprint §3.5, chat-channels-live.md §6): the
 * re-homed verified toggle, the directory browse that never existed, the
 * member-gate-free stats override, takedown/restore (channels previously had
 * NO delete path at all), unlist, freeze, and invite force-revoke.
 * <p>
 * Privacy: projections exclude {@code last_message_preview} — the known
 * metadata→content leak edge — and never any message body.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/channels")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")   // §6 matrix: chat/live moderation is MODERATOR RW
public class AdminChannelController {

    private final ConversationRepository conversationRepository;
    private final ConversationInviteRepository inviteRepository;
    private final ChannelService channelService;
    private final ChannelStatsService channelStatsService;
    private final ChannelSearchService channelSearchService;
    private final ModerationRecorder moderationRecorder;
    private final AdminAuditor adminAuditor;
    private final NotificationService notificationService;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminChannelRow(UUID id, String handle, String title, String category,
                                  boolean publicChannel, boolean verified, boolean frozenByAdmin,
                                  int memberCount, int postCount, UUID ownerId,
                                  LocalDateTime createdAt, LocalDateTime deletedAt) {
    }

    public record ReasonBody(@Size(max = 500) String reason, UUID reportId) {
    }

    // ── reads ───────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<Page<AdminChannelRow>> browse(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean verified,
            @RequestParam(name = "public", required = false) Boolean publicOnly,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) UUID ownerId,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            @PageableDefault(size = 25) Pageable pageable) {
        Page<Conversation> page = conversationRepository.adminBrowse(
                ConversationType.CHANNEL,
                q == null || q.isBlank() ? null : q.trim(),
                verified, publicOnly,
                category == null || category.isBlank() ? null : category.trim().toLowerCase(),
                includeDeleted, ownerId,
                PageRequest.of(Math.max(0, pageable.getPageNumber()),
                        Pages.clamp(pageable.getPageSize())));
        return ResponseEntity.ok(page.map(AdminChannelController::toRow));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AdminChannelRow> detail(@PathVariable UUID id) {
        return ResponseEntity.ok(toRow(requireChannel(id)));
    }

    /** The member-gate-free stats override (non-member admins got 403 before). */
    @GetMapping("/{id}/stats")
    public ResponseEntity<ChannelStatsResponse> stats(@PathVariable UUID id) {
        return ResponseEntity.ok(channelStatsService.statsAsAdmin(id));
    }

    // ── mutations ───────────────────────────────────────────────────────

    /** Re-homed alias of the stray {@code PUT /api/v1/channels/{id}/verified}. */
    @PatchMapping("/{id}/verified")
    public ResponseEntity<Void> setVerified(@PathVariable UUID id, @RequestParam boolean verified) {
        channelService.setVerified(id, verified);
        adminAuditor.record(AuditOperation.UPDATE, "Channel", id,
                "ADMIN_CHANNEL_VERIFY", "verified=" + verified);
        return ResponseEntity.noContent().build();
    }

    /** Channels previously had NO delete path — this is the first takedown writer. */
    @PostMapping("/{id}/takedown")
    @RequiresStepUp
    public ResponseEntity<Void> takedown(@PathVariable UUID id,
                                         @RequestBody(required = false) ReasonBody body) {
        Conversation c = requireChannel(id);
        if (c.getDeletedAt() == null) {
            conversationRepository.softDelete(id, LocalDateTime.now());
            channelSearchService.deleteAsync(id);
        }
        String reason = body != null ? body.reason() : null;
        moderationRecorder.decision("CHANNEL", id.toString(), "ADMIN_CHANNEL_TAKEDOWN",
                reason, body != null ? body.reportId() : null,
                "members=" + c.getMemberCount());
        adminAuditor.record(AuditOperation.DELETE, "Channel", id, "ADMIN_CHANNEL_TAKEDOWN", reason);
        notifyOwner(c, AdminOpsMessages.NOTIF_CHANNEL_TAKEDOWN_TITLE,
                "Your channel \"" + c.getTitle() + "\" was removed by platform moderation"
                        + (reason != null && !reason.isBlank() ? " (" + reason + ")" : "") + ".");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/restore")
    @RequiresStepUp
    public ResponseEntity<Void> restore(@PathVariable UUID id) {
        Conversation c = conversationRepository.findById(id)
                .filter(Conversation::isChannel)
                .orElseThrow(() -> new ResourceNotFoundException("Channel", "id", id));
        c.setDeletedAt(null);
        conversationRepository.save(c);
        channelSearchService.indexAsync(c);
        adminAuditor.record(AuditOperation.UPDATE, "Channel", id, "ADMIN_CHANNEL_RESTORE");
        notifyOwner(c, AdminOpsMessages.NOTIF_CHANNEL_RESTORED_TITLE,
                AdminOpsMessages.NOTIF_CHANNEL_RESTORED_BODY.formatted(c.getTitle()));
        return ResponseEntity.noContent().build();
    }

    /** Remove from public discovery without deleting ({@code is_public=false}). */
    @PostMapping("/{id}/unlist")
    public ResponseEntity<Void> unlist(@PathVariable UUID id,
                                       @RequestBody(required = false) ReasonBody body) {
        Conversation c = requireChannel(id);
        c.setPublicChannel(false);
        conversationRepository.save(c);
        channelSearchService.indexAsync(c);   // private channels de-index themselves
        adminAuditor.record(AuditOperation.UPDATE, "Channel", id, "ADMIN_CHANNEL_UNLIST",
                body != null ? body.reason() : null);
        return ResponseEntity.noContent().build();
    }

    /** Platform posting freeze — checked in the channel-send authorization funnel. */
    @PostMapping("/{id}/freeze")
    @RequiresStepUp
    public ResponseEntity<Void> freeze(@PathVariable UUID id,
                                       @RequestBody(required = false) ReasonBody body) {
        setFrozen(id, true);
        adminAuditor.record(AuditOperation.UPDATE, "Channel", id, "ADMIN_CHANNEL_FREEZE",
                body != null ? body.reason() : null);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/unfreeze")
    public ResponseEntity<Void> unfreeze(@PathVariable UUID id) {
        setFrozen(id, false);
        adminAuditor.record(AuditOperation.UPDATE, "Channel", id, "ADMIN_CHANNEL_UNFREEZE");
        return ResponseEntity.noContent().build();
    }

    /** Force-revoke an invite link (invite-abuse response). */
    @PostMapping("/{id}/invite-links/{inviteId}/revoke")
    public ResponseEntity<Void> revokeInvite(@PathVariable UUID id, @PathVariable UUID inviteId,
                                             @RequestBody(required = false) ReasonBody body) {
        var invite = inviteRepository.findById(inviteId)
                .filter(i -> id.equals(i.getConversationId()))
                .orElseThrow(() -> new ResourceNotFoundException("ConversationInvite", "id", inviteId));
        invite.setRevoked(true);
        inviteRepository.save(invite);
        adminAuditor.record(AuditOperation.UPDATE, "Channel", id,
                "ADMIN_CHANNEL_INVITE_REVOKE", "invite=" + inviteId
                        + (body != null && body.reason() != null ? ", " + body.reason() : ""));
        return ResponseEntity.noContent().build();
    }

    /** Non-revoked invite links of a channel (token hashes only — safe). */
    @GetMapping("/{id}/invite-links")
    public ResponseEntity<List<?>> inviteLinks(@PathVariable UUID id) {
        requireChannel(id);
        return ResponseEntity.ok(inviteRepository.findByConversationIdAndRevokedFalse(id));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private void setFrozen(UUID id, boolean frozen) {
        Conversation c = requireChannel(id);
        var settings = c.channelSettingsOrDefaults();
        settings.setFrozenByAdmin(frozen);
        c.setChannelSettings(settings);
        conversationRepository.save(c);
    }

    private Conversation requireChannel(UUID id) {
        return conversationRepository.findById(id)
                .filter(x -> x.isChannel() && x.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Channel", "id", id));
    }

    private void notifyOwner(Conversation c, String title, String body) {
        if (c.getOwnerId() == null) return;
        try {
            notificationService.sendSystemNotification(c.getOwnerId(), title, body);
        } catch (Exception e) {
            log.debug("[ADMIN-CHANNEL] owner notification skipped: {}", e.getMessage());
        }
    }

    private static AdminChannelRow toRow(Conversation c) {
        boolean frozen = c.getChannelSettings() != null && c.getChannelSettings().isFrozenByAdmin();
        return new AdminChannelRow(c.getId(), c.getHandle(), c.getTitle(), c.getCategory(),
                c.isPublicChannel(), c.isVerified(), frozen,
                c.getMemberCount(), c.getPostCount(), c.getOwnerId(),
                c.getCreatedAt(), c.getDeletedAt());
    }
}

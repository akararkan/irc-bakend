package ak.dev.irc.app.admin.notification;

import ak.dev.irc.app.admin.support.AdminAuditor;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.messages.AdminOpsMessages;
import ak.dev.irc.app.common.notification.NotificationKind;
import ak.dev.irc.app.post.cassandra.service.CassandraNotificationService;
import ak.dev.irc.app.post.cassandra.service.CassandraNotificationService.DeliverRequest;
import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.enums.Role;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The announcement composer's engine (notifications-email.md §4): audience
 * resolution + dry-run counting + the batched all-user fan-out. Uses
 * {@code deliverAllAsync} — one executor task per 500-recipient batch — so a
 * platform-wide send cannot flood the async pool the way per-recipient
 * {@code deliverAsync} would. Kind is SYSTEM_ANNOUNCEMENT (honors the SYSTEM
 * email category); the fat-finger guard requires an explicit confirmation
 * flag once the audience crosses half the platform.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementService {

    private static final int BATCH = 500;
    private static final int MAX_PAGES = 4000;   // 2M users backstop

    private final UserRepository userRepository;
    private final PlatformAnnouncementRepository announcementRepository;
    private final CassandraNotificationService notifications;
    private final AdminAuditor adminAuditor;

    public record ComposeRequest(String title, String body, Role audienceRole,
                                 Integer activeSinceDays, boolean dryRun,
                                 boolean confirmLargeAudience,
                                 ak.dev.irc.app.common.enums.Language audienceLanguage,
                                 LocalDateTime scheduledAt) {
    }

    public record ComposeResult(UUID announcementId, boolean dryRun, long audience,
                                LocalDateTime scheduledAt) {
    }

    public ComposeResult compose(ComposeRequest req) {
        if (req.title() == null || req.title().isBlank()
                || req.body() == null || req.body().isBlank()) {
            throw new BadRequestException(AdminOpsMessages.INVALID_INPUT_TITLE_BODY_MSG,
                    AdminOpsMessages.INVALID_INPUT);
        }
        if (req.scheduledAt() != null && req.scheduledAt().isBefore(LocalDateTime.now())) {
            throw new BadRequestException(
                    AdminOpsMessages.INVALID_SCHEDULE_PAST_MSG,
                    AdminOpsMessages.INVALID_SCHEDULE);
        }
        long audience = countAudience(req);
        if (req.dryRun()) {
            return new ComposeResult(null, true, audience, req.scheduledAt());
        }
        long total = Math.max(1, userRepository.countByDeletedAtIsNull());
        if (audience * 2 >= total && !req.confirmLargeAudience()) {
            throw new BadRequestException(
                    AdminOpsMessages.LARGE_AUDIENCE_CONFIRMATION_REQUIRED_MSG
                            .formatted(audience, total),
                    AdminOpsMessages.LARGE_AUDIENCE_CONFIRMATION_REQUIRED);
        }

        boolean scheduled = req.scheduledAt() != null;
        PlatformAnnouncement announcement = announcementRepository.save(
                PlatformAnnouncement.builder()
                        .title(req.title().trim())
                        .body(req.body().trim())
                        .audienceRole(req.audienceRole())
                        .activeSinceDays(req.activeSinceDays())
                        .audienceLanguage(req.audienceLanguage())
                        .scheduledAt(req.scheduledAt())
                        .status(scheduled ? PlatformAnnouncement.Status.SCHEDULED
                                          : PlatformAnnouncement.Status.SENDING)
                        .targetedCount(audience)
                        .createdBy(SecurityUtils.getCurrentUserId().orElse(null))
                        .build());
        adminAuditor.record(AuditOperation.CREATE, "Announcement", announcement.getId(),
                scheduled ? "ADMIN_ANNOUNCEMENT_SCHEDULE" : "ADMIN_ANNOUNCEMENT_SEND",
                "\"" + announcement.getTitle() + "\" → ~" + audience + " users"
                        + (scheduled ? " at " + req.scheduledAt() : ""));
        if (!scheduled) {
            fanOut(announcement.getId());
        }
        return new ComposeResult(announcement.getId(), false, audience, req.scheduledAt());
    }

    /** Cancel a still-scheduled announcement (SENDING can no longer be stopped). */
    public void cancelScheduled(UUID announcementId) {
        PlatformAnnouncement a = announcementRepository.findById(announcementId)
                .orElseThrow(() -> new ak.dev.irc.app.common.exception.ResourceNotFoundException(
                        "Announcement", "id", announcementId));
        if (a.getStatus() != PlatformAnnouncement.Status.SCHEDULED) {
            throw new BadRequestException(
                    AdminOpsMessages.ANNOUNCEMENT_NOT_SCHEDULED_MSG.formatted(a.getStatus()),
                    AdminOpsMessages.ANNOUNCEMENT_NOT_SCHEDULED);
        }
        a.setStatus(PlatformAnnouncement.Status.CANCELLED);
        a.setCompletedAt(LocalDateTime.now());
        announcementRepository.save(a);
        adminAuditor.record(AuditOperation.UPDATE, "Announcement", announcementId,
                "ADMIN_ANNOUNCEMENT_CANCEL", "\"" + a.getTitle() + "\"");
    }

    /** Minute sweep: fire scheduled announcements whose time has come. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void fireDueScheduled() {
        List<PlatformAnnouncement> due;
        try {
            due = announcementRepository.dueScheduled(LocalDateTime.now());
        } catch (Exception e) {
            log.warn("[ANNOUNCEMENT] scheduled sweep failed: {}", e.getMessage());
            return;
        }
        for (PlatformAnnouncement a : due) {
            a.setStatus(PlatformAnnouncement.Status.SENDING);
            announcementRepository.save(a);
            log.info("[ANNOUNCEMENT] firing scheduled announcement {} (\"{}\")",
                    a.getId(), a.getTitle());
            fanOut(a.getId());
        }
    }

    @Async
    public void fanOut(UUID announcementId) {
        PlatformAnnouncement a = announcementRepository.findById(announcementId).orElse(null);
        if (a == null) return;
        long delivered = 0;
        try {
            if (a.getAudienceRole() == null && a.getActiveSinceDays() == null
                    && a.getAudienceLanguage() == null) {
                delivered = fanOutAllUsers(a);
            } else {
                delivered = fanOutFiltered(a);
            }
            a.setStatus(PlatformAnnouncement.Status.SENT);
        } catch (Exception e) {
            log.error("[ANNOUNCEMENT] fan-out failed for {}: {}", announcementId, e.getMessage(), e);
            a.setStatus(PlatformAnnouncement.Status.FAILED);
        }
        a.setDeliveredCount(delivered);
        a.setCompletedAt(LocalDateTime.now());
        announcementRepository.save(a);
        log.info("[ANNOUNCEMENT] {} finished — delivered={}", announcementId, delivered);
    }

    private long fanOutAllUsers(PlatformAnnouncement a) {
        long delivered = 0;
        UUID cursor = new UUID(0L, 0L);
        for (int page = 0; page < MAX_PAGES; page++) {
            List<UUID> ids = userRepository.findActiveUserIdsAfter(cursor, PageRequest.of(0, BATCH));
            if (ids.isEmpty()) break;
            deliverBatch(a, ids);
            delivered += ids.size();
            cursor = ids.get(ids.size() - 1);
            if (ids.size() < BATCH) break;
        }
        return delivered;
    }

    private long fanOutFiltered(PlatformAnnouncement a) {
        long delivered = 0;
        LocalDateTime activeCutoff = a.getActiveSinceDays() == null ? null
                : LocalDateTime.now().minusDays(a.getActiveSinceDays());
        List<Role> roles = a.getAudienceRole() == null
                ? List.of(Role.values()) : List.of(a.getAudienceRole());
        int page = 0;
        while (page < MAX_PAGES) {
            var slice = userRepository.findActiveByRoles(roles, PageRequest.of(page, BATCH));
            List<UUID> ids = new ArrayList<>();
            for (User u : slice.getContent()) {
                if (activeCutoff != null
                        && (u.getLastLoginAt() == null || u.getLastLoginAt().isBefore(activeCutoff))) {
                    continue;
                }
                if (a.getAudienceLanguage() != null
                        && u.getPreferredLanguage() != a.getAudienceLanguage()) {
                    continue;
                }
                ids.add(u.getId());
            }
            deliverBatch(a, ids);
            delivered += ids.size();
            if (!slice.hasNext()) break;
            page++;
        }
        return delivered;
    }

    private void deliverBatch(PlatformAnnouncement a, List<UUID> ids) {
        if (ids.isEmpty()) return;
        List<UUID> copy = List.copyOf(ids);
        notifications.deliverAllAsync(() -> {
            List<DeliverRequest> batch = new ArrayList<>(copy.size());
            for (UUID userId : copy) {
                batch.add(new DeliverRequest(userId, NotificationKind.SYSTEM_ANNOUNCEMENT,
                        a.getTitle(), a.getBody(), null,
                        "Announcement", a.getId(), "ANNOUNCEMENT:" + a.getId()));
            }
            return batch;
        });
    }

    private long countAudience(ComposeRequest req) {
        if (req.audienceRole() == null && req.activeSinceDays() == null
                && req.audienceLanguage() == null) {
            return userRepository.countByDeletedAtIsNull();
        }
        LocalDateTime activeCutoff = req.activeSinceDays() == null ? null
                : LocalDateTime.now().minusDays(req.activeSinceDays());
        List<Role> roles = req.audienceRole() == null
                ? List.of(Role.values()) : List.of(req.audienceRole());
        long count = 0;
        int page = 0;
        while (page < MAX_PAGES) {
            var slice = userRepository.findActiveByRoles(roles, PageRequest.of(page, BATCH));
            for (User u : slice.getContent()) {
                if (activeCutoff != null
                        && (u.getLastLoginAt() == null || u.getLastLoginAt().isBefore(activeCutoff))) {
                    continue;
                }
                if (req.audienceLanguage() != null
                        && u.getPreferredLanguage() != req.audienceLanguage()) {
                    continue;
                }
                count++;
            }
            if (!slice.hasNext()) break;
            page++;
        }
        return count;
    }
}

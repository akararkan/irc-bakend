package ak.dev.irc.app.admin.safety;

import ak.dev.irc.app.admin.support.AdminAuditor;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ConflictException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.messages.AdminOpsMessages;
import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.settings.safety.entity.Report;
import ak.dev.irc.app.settings.safety.entity.UserStrike;
import ak.dev.irc.app.settings.safety.enums.ReportState;
import ak.dev.irc.app.settings.safety.enums.Resolution;
import ak.dev.irc.app.settings.safety.repository.ReportRepository;
import ak.dev.irc.app.settings.safety.repository.UserStrikeRepository;
import ak.dev.irc.app.settings.safety.service.StrikeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The moderator half of the report state machine — the transitions that never
 * had a writer (nothing ever advanced a report past SUBMITTED/APPEALED):
 * triage, dismiss, action-with-resolution, appeal uphold/reverse.
 *
 * <p>Guards mirror the enum's documented lifecycle: triage only from
 * SUBMITTED; action/dismiss from SUBMITTED or TRIAGED; uphold/reverse only
 * from APPEALED. {@code wholeGroup=true} applies the transition to every open
 * sibling of the {@code group_key} in one transaction — the queue is
 * group-first. Resolution EXECUTION (takedown/suspend) deliberately delegates
 * to the sibling admin endpoints; this service records the verdict.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportModerationService {

    private final ReportRepository reportRepository;
    private final UserStrikeRepository strikeRepository;
    private final StrikeService strikeService;
    private final AdminAuditor adminAuditor;

    @Transactional
    public Report triage(UUID reportId, String note, boolean wholeGroup) {
        Report r = require(reportId);
        requireState(r, "triage", ReportState.SUBMITTED);
        forGroup(r, wholeGroup, ReportState.SUBMITTED, sibling -> {
            sibling.setState(ReportState.TRIAGED);
            sibling.setTriagedBy(currentAdmin());
            sibling.setTriagedAt(LocalDateTime.now());
            appendNote(sibling, note);
        });
        adminAuditor.record(AuditOperation.UPDATE, "Report", reportId, "ADMIN_REPORT_TRIAGE", note);
        return reportRepository.findById(reportId).orElse(r);
    }

    @Transactional
    public Report dismiss(UUID reportId, String note, boolean wholeGroup) {
        Report r = require(reportId);
        requireState(r, "dismiss", ReportState.SUBMITTED, ReportState.TRIAGED);
        forGroup(r, wholeGroup, null, sibling -> {
            if (sibling.getState() != ReportState.SUBMITTED
                    && sibling.getState() != ReportState.TRIAGED) return;
            sibling.setState(ReportState.DISMISSED);
            sibling.setResolution(Resolution.NO_ACTION);
            sibling.setActionedBy(currentAdmin());
            sibling.setActedAt(LocalDateTime.now());
            appendNote(sibling, note);
        });
        adminAuditor.record(AuditOperation.UPDATE, "Report", reportId, "ADMIN_REPORT_DISMISS", note);
        return reportRepository.findById(reportId).orElse(r);
    }

    @Transactional
    public Report action(UUID reportId, Resolution resolution, String note,
                         boolean issueStrike, String strikeReason, boolean wholeGroup) {
        if (resolution == null || resolution == Resolution.NONE) {
            throw new BadRequestException(
                    AdminOpsMessages.INVALID_RESOLUTION_MSG,
                    AdminOpsMessages.INVALID_RESOLUTION);
        }
        Report r = require(reportId);
        requireState(r, "action", ReportState.SUBMITTED, ReportState.TRIAGED);
        forGroup(r, wholeGroup, null, sibling -> {
            if (sibling.getState() != ReportState.SUBMITTED
                    && sibling.getState() != ReportState.TRIAGED) return;
            sibling.setState(ReportState.ACTIONED);
            sibling.setResolution(resolution);
            sibling.setActionedBy(currentAdmin());
            sibling.setActedAt(LocalDateTime.now());
            appendNote(sibling, note);
        });

        // Optional strike against the CONTENT AUTHOR — only sensible for
        // USER-target reports without content resolution context; for content
        // targets the author id comes from the evidence panel, so the strike
        // stays an explicit admin decision, not automation.
        if (issueStrike) {
            UUID strikeTarget = r.getTargetType()
                    == ak.dev.irc.app.settings.safety.enums.ReportTargetType.USER
                    ? r.getTargetId() : null;
            if (strikeTarget == null) {
                throw new BadRequestException(
                        AdminOpsMessages.STRIKE_TARGET_AMBIGUOUS_MSG,
                        AdminOpsMessages.STRIKE_TARGET_AMBIGUOUS);
            }
            strikeService.issueStrike(strikeTarget, r.getId(),
                    strikeReason != null && !strikeReason.isBlank()
                            ? strikeReason : "Report actioned: " + resolution);
        }

        adminAuditor.record(AuditOperation.UPDATE, "Report", reportId,
                "ADMIN_REPORT_ACTION", resolution + (note != null ? " — " + note : ""));
        return reportRepository.findById(reportId).orElse(r);
    }

    @Transactional
    public Report uphold(UUID reportId, String note) {
        Report r = require(reportId);
        requireState(r, "uphold", ReportState.APPEALED);
        r.setState(ReportState.UPHELD);
        r.setActionedBy(currentAdmin());
        r.setActedAt(LocalDateTime.now());
        appendNote(r, note);
        adminAuditor.record(AuditOperation.UPDATE, "Report", reportId, "ADMIN_APPEAL_UPHOLD", note);
        return reportRepository.save(r);
    }

    /** Reverse also auto-revokes the strike linked via {@code user_strikes.report_id}. */
    @Transactional
    public Report reverse(UUID reportId, String note) {
        Report r = require(reportId);
        requireState(r, "reverse", ReportState.APPEALED);
        r.setState(ReportState.REVERSED);
        r.setActionedBy(currentAdmin());
        r.setActedAt(LocalDateTime.now());

        List<UserStrike> linked = strikeRepository.findByReportId(reportId);
        for (UserStrike strike : linked) {
            if (strike.isActive()) {
                strike.setExpiresAt(LocalDateTime.now());   // decay-consistent revoke
                strikeRepository.save(strike);
            }
        }
        appendNote(r, (note == null ? "" : note)
                + (linked.isEmpty() ? "" : " [revoked strike(s): " + linked.size() + "]"));
        adminAuditor.record(AuditOperation.UPDATE, "Report", reportId, "ADMIN_APPEAL_REVERSE", note);
        return reportRepository.save(r);
    }

    @Transactional
    public void revokeStrike(UUID strikeId, String note) {
        UserStrike strike = strikeRepository.findById(strikeId)
                .orElseThrow(() -> new ResourceNotFoundException("UserStrike", "id", strikeId));
        if (!strike.isActive()) {
            throw new ConflictException(AdminOpsMessages.STRIKE_NOT_ACTIVE_MSG,
                    AdminOpsMessages.STRIKE_NOT_ACTIVE);
        }
        strike.setExpiresAt(LocalDateTime.now());   // rows are never deleted
        strikeRepository.save(strike);
        adminAuditor.record(AuditOperation.UPDATE, "UserStrike", strikeId,
                "ADMIN_STRIKE_REVOKE", note);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private Report require(UUID reportId) {
        return reportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Report", "id", reportId));
    }

    private static void requireState(Report r, String verb, ReportState... allowed) {
        for (ReportState s : allowed) {
            if (r.getState() == s) return;
        }
        throw new ConflictException(
                AdminOpsMessages.REPORT_STATE_INVALID_MSG.formatted(verb, r.getState()),
                AdminOpsMessages.REPORT_STATE_INVALID);
    }

    private void forGroup(Report anchor, boolean wholeGroup, ReportState onlyFrom,
                          java.util.function.Consumer<Report> transition) {
        List<Report> targets = wholeGroup
                ? reportRepository.findByGroupKeyOrderByCreatedAtAsc(anchor.getGroupKey())
                : List.of(anchor);
        for (Report sibling : targets) {
            if (onlyFrom != null && sibling.getState() != onlyFrom) continue;
            transition.accept(sibling);
            reportRepository.save(sibling);
        }
    }

    private static void appendNote(Report r, String note) {
        if (note == null || note.isBlank()) return;
        String existing = r.getModeratorNote();
        String merged = existing == null || existing.isBlank() ? note : existing + " | " + note;
        r.setModeratorNote(merged.length() > 1000 ? merged.substring(0, 1000) : merged);
    }

    private static UUID currentAdmin() {
        return SecurityUtils.getCurrentUserId().orElse(null);
    }
}

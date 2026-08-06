package ak.dev.irc.app.settings.safety.service;

import ak.dev.irc.app.common.cache.RateLimiter;
import ak.dev.irc.app.common.exception.ConflictException;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.settings.safety.entity.Report;
import ak.dev.irc.app.settings.safety.enums.ReportReason;
import ak.dev.irc.app.settings.safety.enums.ReportState;
import ak.dev.irc.app.settings.safety.enums.ReportTargetType;
import ak.dev.irc.app.settings.safety.enums.Resolution;
import ak.dev.irc.app.settings.safety.repository.ReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Report intake and the reporter-facing side of the moderation state machine
 * (spec §18). The reporter only ever sees their own reports and a coarse
 * outcome; the concrete {@link Resolution} taken against the target stays
 * private.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    private static final List<ReportState> OPEN_STATES =
            List.of(ReportState.SUBMITTED, ReportState.TRIAGED);

    private final ReportRepository repo;
    private final RateLimiter rateLimiter;
    private final ReportEvidenceService evidenceService;

    @Transactional
    public Report submit(UUID reporterId, ReportTargetType targetType, UUID targetId,
                         ReportReason reason, String details) {
        return submit(reporterId, targetType, targetId, null, reason, details);
    }

    /**
     * Full intake — {@code targetRef} carries non-UUID targets (chat message
     * Snowflakes), fixing the defect where a MESSAGE report could not
     * reference its target at all. Exactly one of id/ref must be present.
     */
    @Transactional
    public Report submit(UUID reporterId, ReportTargetType targetType, UUID targetId,
                         String targetRef, ReportReason reason, String details) {
        rateLimiter.check("safety:report", reporterId, 20, Duration.ofHours(1));

        if (targetId == null && (targetRef == null || targetRef.isBlank())) {
            throw new ak.dev.irc.app.common.exception.BadRequestException(
                    "targetId (or targetRef for MESSAGE targets) is required.", "TARGET_REQUIRED");
        }
        String ref = targetId != null ? targetId.toString() : targetRef.trim();
        String groupKey = ref + ":" + reason.name();

        // Dedup: an open report by this reporter for the same group is reused
        // rather than duplicated — one queue item, not many identical rows.
        Report report = repo.findFirstByReporterIdAndGroupKeyAndStateIn(reporterId, groupKey, OPEN_STATES)
                .orElseGet(() -> repo.save(Report.builder()
                        .reporterId(reporterId)
                        .targetType(targetType)
                        .targetId(targetId)
                        .targetRef(targetId == null ? ref : null)
                        .reason(reason)
                        .details(details != null && details.length() > 1000
                                ? details.substring(0, 1000) : details)
                        .state(ReportState.SUBMITTED)
                        .resolution(Resolution.NONE)
                        .groupKey(groupKey)
                        .build()));

        // Evidence freezes at submit — comments hard-delete, stories TTL out,
        // profiles are editable. One snapshot per group (first report wins).
        evidenceService.snapshotIfAbsent(report);
        return report;
    }

    @Transactional(readOnly = true)
    public Page<Report> myReports(UUID reporterId, Pageable pageable) {
        return repo.findByReporterIdOrderByCreatedAtDesc(reporterId, pageable);
    }

    @Transactional
    public Report appeal(UUID reporterId, UUID reportId) {
        Report r = repo.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("Report", "id", reportId));
        if (!r.getReporterId().equals(reporterId)) {
            throw new ForbiddenException("You can only appeal your own reports.", "NOT_REPORTER");
        }
        if (r.getState() != ReportState.ACTIONED && r.getState() != ReportState.DISMISSED) {
            throw new ConflictException(
                    "This report cannot be appealed in its current state.", "REPORT_NOT_APPEALABLE");
        }
        r.setState(ReportState.APPEALED);
        return repo.save(r);
    }
}

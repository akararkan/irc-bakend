package ak.dev.irc.app.settings.safety.controller;

import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.settings.safety.dto.SafetyDtos.*;
import ak.dev.irc.app.settings.safety.service.ReportService;
import ak.dev.irc.app.settings.safety.service.SecurityScoreService;
import ak.dev.irc.app.settings.safety.service.StrikeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Safety Center (spec §18): submit reports, view your own reports (coarse
 * outcome only), your active strikes, your derived security score, and appeal
 * a resolved report.
 */
@RestController
@RequestMapping("/api/v1/safety")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class SafetyController {

    private final ReportService reportService;
    private final StrikeService strikeService;
    private final SecurityScoreService scoreService;

    @PostMapping("/reports")
    public ResponseEntity<ReportResponse> submit(@Valid @RequestBody SubmitReportRequest req) {
        UUID me = SecurityUtils.requireCurrentUserId();
        return ResponseEntity.ok(ReportResponse.of(
                reportService.submit(me, req.targetType(), req.targetId(), req.targetRef(),
                        req.reason(), req.details())));
    }

    @GetMapping("/reports")
    public ResponseEntity<Page<ReportResponse>> myReports(Pageable pageable) {
        UUID me = SecurityUtils.requireCurrentUserId();
        return ResponseEntity.ok(reportService.myReports(me, pageable).map(ReportResponse::of));
    }

    @PostMapping("/reports/{id}/appeal")
    public ResponseEntity<ReportResponse> appeal(@PathVariable UUID id) {
        UUID me = SecurityUtils.requireCurrentUserId();
        return ResponseEntity.ok(ReportResponse.of(reportService.appeal(me, id)));
    }

    @GetMapping("/strikes")
    public ResponseEntity<List<StrikeResponse>> strikes() {
        UUID me = SecurityUtils.requireCurrentUserId();
        return ResponseEntity.ok(strikeService.myStrikes(me).stream().map(StrikeResponse::of).toList());
    }

    @GetMapping("/score")
    public ResponseEntity<SecurityScoreResponse> score() {
        UUID me = SecurityUtils.requireCurrentUserId();
        return ResponseEntity.ok(scoreService.computeScore(me));
    }
}

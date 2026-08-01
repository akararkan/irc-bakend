package ak.dev.irc.app.settings.data.controller;

import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.settings.data.dto.DataDtos.DeletionStatusResponse;
import ak.dev.irc.app.settings.data.dto.DataDtos.ExportJobResponse;
import ak.dev.irc.app.settings.data.entity.AccountDeletionRequest;
import ak.dev.irc.app.settings.data.service.AccountLifecycleService;
import ak.dev.irc.app.settings.data.service.DataExportService;
import ak.dev.irc.app.settings.data.service.HistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Data & Privacy surface (spec §16): export, account deletion, history deletion.
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class DataPrivacyController {

    private final DataExportService exportService;
    private final AccountLifecycleService lifecycleService;
    private final HistoryService historyService;

    // ── Export ──────────────────────────────────────────────────────────────────

    @PostMapping("/api/v1/privacy/export")
    public ResponseEntity<ExportJobResponse> requestExport() {
        UUID userId = SecurityUtils.requireCurrentUserId();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ExportJobResponse.of(exportService.requestExport(userId)));
    }

    @GetMapping("/api/v1/privacy/export/{jobId}")
    public ResponseEntity<ExportJobResponse> exportStatus(@PathVariable UUID jobId) {
        UUID userId = SecurityUtils.requireCurrentUserId();
        return ResponseEntity.ok(ExportJobResponse.of(exportService.getJob(userId, jobId)));
    }

    @GetMapping("/api/v1/privacy/export/{jobId}/download")
    public ResponseEntity<Resource> download(@PathVariable UUID jobId) {
        UUID userId = SecurityUtils.requireCurrentUserId();
        DataExportService.Download dl = exportService.openDownload(userId, jobId);
        Resource body = new InputStreamResource(dl.stream());
        ResponseEntity.BodyBuilder b = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"irc-export.zip\"")
                .contentType(MediaType.parseMediaType("application/zip"));
        if (dl.length() >= 0) b.contentLength(dl.length());
        return b.body(body);
    }

    // ── History deletion ──────────────────────────────────────────────────────────

    @DeleteMapping("/api/v1/privacy/history/{type}")
    public ResponseEntity<Void> deleteHistory(@PathVariable String type) {
        historyService.deleteHistory(SecurityUtils.requireCurrentUserId(), type);
        return ResponseEntity.noContent().build();
    }

    // ── Account deletion ──────────────────────────────────────────────────────────

    // SEAM: this should require step-up authentication (password/fresh OTP) — a
    // stolen open session must not be able to schedule account deletion (§22.3).
    @PostMapping("/api/v1/account/deletion/request")
    public ResponseEntity<DeletionStatusResponse> requestDeletion() {
        UUID userId = SecurityUtils.requireCurrentUserId();
        AccountDeletionRequest req = lifecycleService.requestDeletion(userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(DeletionStatusResponse.of(req));
    }

    @PostMapping("/api/v1/account/deletion/cancel")
    public ResponseEntity<DeletionStatusResponse> cancelDeletion() {
        UUID userId = SecurityUtils.requireCurrentUserId();
        return ResponseEntity.ok(DeletionStatusResponse.of(lifecycleService.cancelDeletion(userId)));
    }
}

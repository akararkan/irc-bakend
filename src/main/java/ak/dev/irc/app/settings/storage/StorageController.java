package ak.dev.irc.app.settings.storage;

import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.settings.storage.StorageUsageService.StorageUsage;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Storage management (spec §15). Only the server-side footprint is exposed —
 * device cache / downloaded media / temp files are client-owned [C] and the
 * backend has no visibility into the device's disk.
 */
@RestController
@RequestMapping("/api/v1/storage")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class StorageController {

    private final StorageUsageService storageUsageService;

    @GetMapping("/usage")
    public ResponseEntity<StorageUsage> usage() {
        return ResponseEntity.ok(storageUsageService.usage(SecurityUtils.requireCurrentUserId()));
    }
}

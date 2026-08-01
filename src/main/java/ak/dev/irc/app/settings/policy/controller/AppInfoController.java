package ak.dev.irc.app.settings.policy.controller;

import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.settings.policy.dto.PolicyDtos.*;
import ak.dev.irc.app.settings.policy.service.PolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * About / app-config / policy endpoints (spec §19). The config + policy reads
 * are public so an out-of-date or logged-out client can still version-gate and
 * show policies; acceptance requires auth.
 */
@RestController
@RequestMapping("/api/v1/app")
@RequiredArgsConstructor
public class AppInfoController {

    private final PolicyService policyService;

    @GetMapping("/config")
    @PreAuthorize("permitAll()")
    public ResponseEntity<AppConfigResponse> config() {
        return ResponseEntity.ok(policyService.appConfig());
    }

    @GetMapping("/policies/{key}")
    @PreAuthorize("permitAll()")
    public ResponseEntity<PolicyDocResponse> policy(@PathVariable String key) {
        return ResponseEntity.ok(policyService.policyMeta(key));
    }

    @PostMapping("/policies/{key}/accept")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PolicyAcceptanceResponse> accept(@PathVariable String key) {
        return ResponseEntity.ok(policyService.accept(SecurityUtils.requireCurrentUserId(), key));
    }

    @GetMapping("/policies/me/accepted")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<PolicyAcceptanceResponse>> myAccepted() {
        return ResponseEntity.ok(policyService.myAcceptances(SecurityUtils.requireCurrentUserId()));
    }
}

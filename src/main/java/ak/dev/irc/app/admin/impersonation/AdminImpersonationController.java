package ak.dev.irc.app.admin.impersonation;

import ak.dev.irc.app.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code DELETE /api/v1/admin/impersonation} — end the caller's active act-as session. */
@RestController
@RequestMapping("/api/v1/admin/impersonation")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminImpersonationController {

    private final ImpersonationService impersonationService;

    @DeleteMapping
    public ResponseEntity<Void> end(@AuthenticationPrincipal User admin) {
        impersonationService.end(admin);
        return ResponseEntity.noContent().build();
    }
}

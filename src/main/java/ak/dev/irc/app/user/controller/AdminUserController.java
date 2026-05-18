package ak.dev.irc.app.user.controller;

import ak.dev.irc.app.user.dto.request.AdminChangeAccountTypeRequest;
import ak.dev.irc.app.user.dto.response.UserResponse;
import ak.dev.irc.app.user.service.AdminUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Admin-only mutations on user accounts. Routes are mounted under
 * {@code /api/v1/admin/users}.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    /**
     * Change a user's {@code accountType} (and optionally
     * {@code verificationTier} / {@code role}).
     *
     * <p>This is the ONLY entry point for changing an account's classification.
     * Users may not request changes themselves.</p>
     */
    @PatchMapping("/{userId}/account-type")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<UserResponse> changeAccountType(
            @PathVariable UUID userId,
            @Valid @RequestBody AdminChangeAccountTypeRequest request) {
        return ResponseEntity.ok(adminUserService.changeAccountType(userId, request));
    }
}

package ak.dev.irc.app.user.controller;

import ak.dev.irc.app.user.dto.request.AdminChangeRoleRequest;
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
 *
 * <p>The previous {@code /account-type} endpoint and its companion
 * {@code AdminChangeAccountTypeRequest} were retired with the broader
 * AccountType / VerificationTier cleanup. Role is now the only knob.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    /**
     * Promote / demote a user along the four-role ladder
     * ({@code USER} / {@code RESEARCHER} / {@code SCHOLAR} / {@code ADMIN}).
     * The auto-derived badge updates on the next read.
     *
     * <p>This is the ONLY entry point for changing a user's role — users may
     * not request promotions themselves.</p>
     */
    @PatchMapping("/{userId}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> changeRole(
            @PathVariable UUID userId,
            @Valid @RequestBody AdminChangeRoleRequest request) {
        return ResponseEntity.ok(adminUserService.changeRole(userId, request));
    }
}

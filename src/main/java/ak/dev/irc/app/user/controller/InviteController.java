package ak.dev.irc.app.user.controller;

import ak.dev.irc.app.user.dto.AdminUserDtos.AcceptInviteRequest;
import ak.dev.irc.app.user.dto.response.UserResponse;
import ak.dev.irc.app.user.service.AdminUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public consumption of admin-issued onboarding invites — the target of the
 * emailed set-password link. Creates the account (pre-verified, invited role)
 * or sets the password on the admin-precreated account, then the user logs
 * in normally.
 */
@RestController
@RequestMapping("/api/v1/auth/invites")
@RequiredArgsConstructor
public class InviteController {

    private final AdminUserService adminUserService;

    @PostMapping("/accept")
    public ResponseEntity<UserResponse> accept(@Valid @RequestBody AcceptInviteRequest request) {
        return ResponseEntity.ok(adminUserService.acceptInvite(request));
    }
}

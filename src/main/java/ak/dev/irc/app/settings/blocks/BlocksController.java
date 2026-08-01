package ak.dev.irc.app.settings.blocks;

import ak.dev.irc.app.user.dto.response.SocialActionResponse;
import ak.dev.irc.app.user.dto.response.UserResponse;
import ak.dev.irc.app.user.service.UserSocialService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Spec §13 blocked-users surface — a thin, spec-named alias over the existing
 * {@link UserSocialService}. Block semantics (bidirectional invisibility, follows
 * severed, fail-closed) live in that service and its repository-level filter; this
 * controller is only a convenience entry point at {@code /api/v1/blocks}.
 */
@RestController
@RequestMapping("/api/v1/blocks")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class BlocksController {

    private final UserSocialService userSocialService;

    @GetMapping
    public ResponseEntity<Page<UserResponse>> blocked(Pageable pageable) {
        return ResponseEntity.ok(userSocialService.getBlockedUsers(pageable));
    }

    @PostMapping("/{userId}")
    public ResponseEntity<SocialActionResponse> block(@PathVariable UUID userId) {
        return ResponseEntity.ok(userSocialService.block(userId));
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<SocialActionResponse> unblock(@PathVariable UUID userId) {
        return ResponseEntity.ok(userSocialService.unblock(userId));
    }
}

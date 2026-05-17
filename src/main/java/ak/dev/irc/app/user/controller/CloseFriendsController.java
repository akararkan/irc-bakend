package ak.dev.irc.app.user.controller;

import ak.dev.irc.app.user.dto.response.UserResponse;
import ak.dev.irc.app.user.service.CloseFriendsService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users/me/close-friends")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class CloseFriendsController {

    private final CloseFriendsService closeFriendsService;

    @GetMapping
    public ResponseEntity<Page<UserResponse>> list(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(closeFriendsService.getMyCloseFriends(pageable));
    }

    @PostMapping("/{userId}")
    public ResponseEntity<Void> add(@PathVariable UUID userId) {
        closeFriendsService.addCloseFriend(userId);
        return ResponseEntity.status(201).build();
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> remove(@PathVariable UUID userId) {
        closeFriendsService.removeCloseFriend(userId);
        return ResponseEntity.noContent().build();
    }
}

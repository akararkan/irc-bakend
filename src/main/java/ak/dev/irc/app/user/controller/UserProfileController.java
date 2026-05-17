package ak.dev.irc.app.user.controller;

import ak.dev.irc.app.user.dto.request.UpdateSpecializationsRequest;
import ak.dev.irc.app.user.dto.request.UpdateUserProfileRequest;
import ak.dev.irc.app.user.dto.response.UserResponse;
import ak.dev.irc.app.user.service.UserProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserProfileService profileService;

    // ── Public profile read ───────────────────────────────────────────────────

    @GetMapping("/{id}/profile")
    public ResponseEntity<UserResponse> getPublicProfile(@PathVariable UUID id) {
        return ResponseEntity.ok(profileService.getPublicProfile(id));
    }

    @GetMapping("/me/profile")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserResponse> getMyProfile() {
        return ResponseEntity.ok(profileService.getMyProfile());
    }

    // ── Profile field update ──────────────────────────────────────────────────

    @PatchMapping("/me/profile")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserResponse> updateMyProfile(
            @Valid @RequestBody UpdateUserProfileRequest request) {
        return ResponseEntity.ok(profileService.updateMyProfile(request));
    }

    // ── Avatar ────────────────────────────────────────────────────────────────

    @PostMapping(value = "/me/profile/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserResponse> uploadAvatar(
            @RequestPart("image") MultipartFile image) {
        return ResponseEntity.ok(profileService.uploadAvatar(image));
    }

    @DeleteMapping("/me/profile/avatar")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserResponse> removeAvatar() {
        return ResponseEntity.ok(profileService.removeAvatar());
    }

    // ── Cover image ───────────────────────────────────────────────────────────

    @PostMapping(value = "/me/profile/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserResponse> uploadCover(
            @RequestPart("image") MultipartFile image) {
        return ResponseEntity.ok(profileService.uploadCover(image));
    }

    @DeleteMapping("/me/profile/cover")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserResponse> removeCover() {
        return ResponseEntity.ok(profileService.removeCover());
    }

    // ── Specializations ───────────────────────────────────────────────────────

    @PatchMapping("/me/profile/specializations")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserResponse> updateSpecializations(
            @Valid @RequestBody UpdateSpecializationsRequest request) {
        return ResponseEntity.ok(profileService.updateSpecializations(request));
    }
}

package ak.dev.irc.app.user.controller;

import ak.dev.irc.app.user.dto.request.*;
import ak.dev.irc.app.user.dto.response.*;
import ak.dev.irc.app.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    // ── Profile ───────────────────────────────────────────────────────────────

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserResponse> getMyProfile() {
        return ResponseEntity.ok(userService.getMyProfile());
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getProfile(@PathVariable UUID id) {
        return ResponseEntity.ok(userService.getProfile(id));
    }

    @GetMapping("/username/{username}")
    public ResponseEntity<UserResponse> getProfileByUsername(@PathVariable String username) {
        return ResponseEntity.ok(userService.getProfileByUsername(username));
    }

    @GetMapping("/email/{email}")
    public ResponseEntity<UserResponse> getProfileByEmail(@PathVariable String email) {
        return ResponseEntity.ok(userService.getProfileByEmail(email));
    }

    @PatchMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserResponse> updateProfile(
            @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(userService.updateProfile(request));
    }

    // ── Profile Image (Multipart → Cloudflare R2) ─────────────────────────────

    @PostMapping(value = "/me/profile-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserResponse> uploadProfileImage(
            @RequestPart("image") MultipartFile image) {
        return ResponseEntity.ok(userService.uploadProfileImage(image));
    }

    @DeleteMapping("/me/profile-image")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserResponse> removeProfileImage() {
        return ResponseEntity.ok(userService.removeProfileImage());
    }

    // ── Search ────────────────────────────────────────────────────────────────

    @GetMapping("/search")
    public ResponseEntity<Page<UserResponse>> search(
            @RequestParam(defaultValue = "") String q,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(userService.searchUsers(q, pageable));
    }

    // ── Links ─────────────────────────────────────────────────────────────────

    @PostMapping("/me/links")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserLinkResponse> addLink(
            @Valid @RequestBody AddLinkRequest request) {
        return ResponseEntity.status(201).body(userService.addLink(request));
    }

    @PatchMapping("/me/links/{linkId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserLinkResponse> editLink(
            @PathVariable UUID linkId,
            @Valid @RequestBody EditLinkRequest request) {
        return ResponseEntity.ok(userService.editLink(linkId, request));
    }

    @DeleteMapping("/me/links/{linkId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> removeLink(@PathVariable UUID linkId) {
        userService.removeLink(linkId);
        return ResponseEntity.noContent().build();
    }

    // ── Contacts ──────────────────────────────────────────────────────────────

    @PostMapping("/me/contacts")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserContactResponse> addContact(
            @Valid @RequestBody AddContactRequest request) {
        return ResponseEntity.status(201).body(userService.addContact(request));
    }

    @PatchMapping("/me/contacts/{contactId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserContactResponse> editContact(
            @PathVariable UUID contactId,
            @Valid @RequestBody EditContactRequest request) {
        return ResponseEntity.ok(userService.editContact(contactId, request));
    }

    @DeleteMapping("/me/contacts/{contactId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> removeContact(@PathVariable UUID contactId) {
        userService.removeContact(contactId);
        return ResponseEntity.noContent().build();
    }

    // ── Account ───────────────────────────────────────────────────────────────

    @DeleteMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> deleteAccount() {
        userService.deleteMyAccount();
        return ResponseEntity.noContent().build();
    }
}
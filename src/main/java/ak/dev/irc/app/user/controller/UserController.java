package ak.dev.irc.app.user.controller;

import ak.dev.irc.app.user.dto.request.*;
import ak.dev.irc.app.user.dto.response.*;
import ak.dev.irc.app.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    // ── Identity read ─────────────────────────────────────────────────────────

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserResponse> getMe() {
        return ResponseEntity.ok(userService.getMyProfile());
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(userService.getProfile(id));
    }

    @GetMapping("/username/{username}")
    public ResponseEntity<UserResponse> getByUsername(@PathVariable String username) {
        return ResponseEntity.ok(userService.getProfileByUsername(username));
    }

    @GetMapping("/email/{email}")
    public ResponseEntity<UserResponse> getByEmail(@PathVariable String email) {
        return ResponseEntity.ok(userService.getProfileByEmail(email));
    }

    // ── Auth-layer identity update (fname, lname, username) ───────────────────

    @PatchMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserResponse> updateIdentity(
            @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(userService.updateProfile(request));
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

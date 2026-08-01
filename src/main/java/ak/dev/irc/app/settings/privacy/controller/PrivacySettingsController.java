package ak.dev.irc.app.settings.privacy.controller;

import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.settings.privacy.dto.PrivacyDtos.*;
import ak.dev.irc.app.settings.privacy.service.HiddenKeywordService;
import ak.dev.irc.app.settings.privacy.service.MuteService;
import ak.dev.irc.app.settings.privacy.service.PrivacyListService;
import ak.dev.irc.app.settings.privacy.service.PrivacyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Privacy settings surface (spec §5, §13): field visibility policy, custom
 * lists, muted users and hidden keywords.
 */
@RestController
@RequestMapping("/api/v1/settings/privacy")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class PrivacySettingsController {

    private final PrivacyService privacyService;
    private final PrivacyListService listService;
    private final MuteService muteService;
    private final HiddenKeywordService keywordService;

    // ── Field visibility ──────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<Map<String, String>> getPolicy() {
        return ResponseEntity.ok(
                privacyService.resolvedPolicyAsStrings(SecurityUtils.requireCurrentUserId()));
    }

    @PutMapping("/{field}")
    public ResponseEntity<Map<String, String>> setField(@PathVariable String field,
                                                        @Valid @RequestBody SetVisibilityRequest req) {
        return ResponseEntity.ok(privacyService.setFieldPolicy(
                SecurityUtils.requireCurrentUserId(), field, req.visibility()));
    }

    // ── Custom lists ──────────────────────────────────────────────────────────

    @GetMapping("/lists")
    public ResponseEntity<List<PrivacyListResponse>> lists() {
        return ResponseEntity.ok(listService.myLists(SecurityUtils.requireCurrentUserId())
                .stream().map(PrivacyListResponse::of).toList());
    }

    @PostMapping("/lists")
    public ResponseEntity<PrivacyListResponse> createList(@Valid @RequestBody CreateListRequest req) {
        return ResponseEntity.ok(PrivacyListResponse.of(
                listService.create(SecurityUtils.requireCurrentUserId(), req.name())));
    }

    @DeleteMapping("/lists/{id}")
    public ResponseEntity<Void> deleteList(@PathVariable UUID id) {
        listService.delete(SecurityUtils.requireCurrentUserId(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/lists/{id}/members")
    public ResponseEntity<List<UUID>> listMembers(@PathVariable UUID id) {
        return ResponseEntity.ok(listService.memberIds(SecurityUtils.requireCurrentUserId(), id));
    }

    @PostMapping("/lists/{id}/members")
    public ResponseEntity<Void> addMember(@PathVariable UUID id,
                                          @Valid @RequestBody AddMemberRequest req) {
        listService.addMember(SecurityUtils.requireCurrentUserId(), id, req.memberId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/lists/{id}/members/{memberId}")
    public ResponseEntity<Void> removeMember(@PathVariable UUID id, @PathVariable UUID memberId) {
        listService.removeMember(SecurityUtils.requireCurrentUserId(), id, memberId);
        return ResponseEntity.noContent().build();
    }

    // ── Muted users ─────────────────────────────────────────────────────────────

    @GetMapping("/muted")
    public ResponseEntity<List<UUID>> muted() {
        return ResponseEntity.ok(muteService.mutedList(SecurityUtils.requireCurrentUserId()));
    }

    @PostMapping("/muted/{userId}")
    public ResponseEntity<Void> mute(@PathVariable UUID userId) {
        muteService.mute(SecurityUtils.requireCurrentUserId(), userId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/muted/{userId}")
    public ResponseEntity<Void> unmute(@PathVariable UUID userId) {
        muteService.unmute(SecurityUtils.requireCurrentUserId(), userId);
        return ResponseEntity.noContent().build();
    }

    // ── Hidden keywords ─────────────────────────────────────────────────────────

    @GetMapping("/keywords")
    public ResponseEntity<List<HiddenKeywordResponse>> keywords() {
        return ResponseEntity.ok(keywordService.list(SecurityUtils.requireCurrentUserId())
                .stream().map(HiddenKeywordResponse::of).toList());
    }

    @PostMapping("/keywords")
    public ResponseEntity<HiddenKeywordResponse> addKeyword(@Valid @RequestBody AddKeywordRequest req) {
        return ResponseEntity.ok(HiddenKeywordResponse.of(
                keywordService.add(SecurityUtils.requireCurrentUserId(), req.keyword())));
    }

    @DeleteMapping("/keywords/{id}")
    public ResponseEntity<Void> removeKeyword(@PathVariable UUID id) {
        keywordService.remove(SecurityUtils.requireCurrentUserId(), id);
        return ResponseEntity.noContent().build();
    }
}

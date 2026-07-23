package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.dto.request.UpdateChatSettingsRequest;
import ak.dev.irc.app.chat.dto.response.ChatSettingsResponse;
import ak.dev.irc.app.chat.entity.ChatUserSettings;
import ak.dev.irc.app.chat.repository.ChatUserSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Per-user chat privacy settings + the symmetric gates the rest of the module
 * consults. Read receipts and last-seen are symmetric: turning yours off also
 * hides others' from you, so a user can never take a signal they refuse to give.
 * Rows are created lazily (all-on defaults) on first update.
 */
@Service
@RequiredArgsConstructor
public class ChatSettingsService {

    private final ChatUserSettingsRepository repo;

    @Transactional(readOnly = true)
    public ChatUserSettings settingsOf(UUID userId) {
        return repo.findById(userId).orElseGet(() -> ChatUserSettings.defaults(userId));
    }

    @Transactional(readOnly = true)
    public ChatSettingsResponse get(UUID userId) {
        ChatUserSettings s = settingsOf(userId);
        return new ChatSettingsResponse(s.isReadReceiptsEnabled(), s.isLastSeenVisible(),
                s.isTypingIndicatorsEnabled());
    }

    @Transactional
    public ChatSettingsResponse update(UUID userId, UpdateChatSettingsRequest req) {
        ChatUserSettings s = repo.findById(userId).orElseGet(() -> ChatUserSettings.defaults(userId));
        if (req.getReadReceiptsEnabled() != null) s.setReadReceiptsEnabled(req.getReadReceiptsEnabled());
        if (req.getLastSeenVisible() != null) s.setLastSeenVisible(req.getLastSeenVisible());
        if (req.getTypingIndicatorsEnabled() != null) s.setTypingIndicatorsEnabled(req.getTypingIndicatorsEnabled());
        repo.save(s);
        return new ChatSettingsResponse(s.isReadReceiptsEnabled(), s.isLastSeenVisible(),
                s.isTypingIndicatorsEnabled());
    }

    // ── Symmetric gates (used by the receipt / presence / typing paths) ──────────

    /** True only if BOTH users share read receipts (symmetric) — used before
     *  broadcasting {@code viewer}'s read/delivered receipt to {@code peer} (DMs). */
    @Transactional(readOnly = true)
    public boolean readReceiptsBetween(UUID viewer, UUID peer) {
        return settingsOf(viewer).isReadReceiptsEnabled() && settingsOf(peer).isReadReceiptsEnabled();
    }

    @Transactional(readOnly = true)
    public boolean readReceiptsEnabled(UUID userId) {
        return settingsOf(userId).isReadReceiptsEnabled();
    }

    @Transactional(readOnly = true)
    public boolean lastSeenVisible(UUID userId) {
        return settingsOf(userId).isLastSeenVisible();
    }

    @Transactional(readOnly = true)
    public boolean typingEnabled(UUID userId) {
        return settingsOf(userId).isTypingIndicatorsEnabled();
    }

    /** Of these users, the subset that share read receipts (missing row = default on).
     *  Used to filter a group "seen by" list. */
    @Transactional(readOnly = true)
    public java.util.Set<UUID> receiptSharersAmong(java.util.Collection<UUID> ids) {
        if (ids.isEmpty()) return java.util.Set.of();
        java.util.Set<UUID> out = new java.util.HashSet<>(ids);
        out.removeAll(repo.findReceiptDisabledAmong(ids));
        return out;
    }

    /** The symmetric recipient set for broadcasting the actor's read/delivered
     *  receipt: active members other than the actor who ALSO share read receipts.
     *  For a DM this is the peer iff both share; for a group, the opted-in subset —
     *  a member who turned receipts off never receives one (can't take what you
     *  won't give). The actor's own gate is checked by the caller. */
    @Transactional(readOnly = true)
    public java.util.List<UUID> receiptRecipients(java.util.Collection<UUID> activeMemberIds, UUID actorId) {
        if (activeMemberIds.isEmpty()) return java.util.List.of();
        java.util.Set<UUID> sharing = receiptSharersAmong(activeMemberIds);
        return activeMemberIds.stream()
                .filter(id -> !id.equals(actorId))
                .filter(sharing::contains)
                .toList();
    }

    /** Of these users, the subset whose last-seen is HIDDEN (for presence filtering). */
    @Transactional(readOnly = true)
    public java.util.Set<UUID> lastSeenHiddenAmong(java.util.Collection<UUID> ids) {
        if (ids.isEmpty()) return java.util.Set.of();
        return new java.util.HashSet<>(repo.findLastSeenHiddenAmong(ids));
    }
}

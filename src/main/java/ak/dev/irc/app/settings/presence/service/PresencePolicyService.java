package ak.dev.irc.app.settings.presence.service;

import ak.dev.irc.app.chat.entity.ChatUserSettings;
import ak.dev.irc.app.chat.repository.ChatUserSettingsRepository;
import ak.dev.irc.app.settings.audit.service.SettingsAuditService;
import ak.dev.irc.app.settings.presence.entity.UserPresencePolicy;
import ak.dev.irc.app.settings.presence.enums.PresencePolicy;
import ak.dev.irc.app.settings.presence.repository.UserPresencePolicyRepository;
import ak.dev.irc.app.user.repository.UserFollowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Three-way presence policy (spec §7) with the reciprocity rule. Enforcement of
 * the current last-seen boolean stays in {@code PresenceService}; this service
 * mirrors {@code NOBODY} onto that boolean so an existing hide takes effect on
 * the hot path, and exposes {@link #lastSeenVisibleTo} for the FRIENDS
 * refinement (the documented PresenceService seam).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PresencePolicyService {

    private final UserPresencePolicyRepository repo;
    private final ChatUserSettingsRepository chatSettingsRepo;
    private final UserFollowRepository followRepo;
    private final SettingsAuditService audit;

    @Transactional
    public UserPresencePolicy get(UUID userId) {
        return repo.findById(userId).orElseGet(() -> UserPresencePolicy.defaultsFor(userId));
    }

    @Transactional
    public UserPresencePolicy update(UUID userId, String onlineRaw, String lastSeenRaw) {
        UserPresencePolicy p = get(userId);
        if (onlineRaw != null) p.setOnlineStatusPolicy(PresencePolicy.parse(onlineRaw, p.getOnlineStatusPolicy()));
        if (lastSeenRaw != null) p.setLastSeenPolicy(PresencePolicy.parse(lastSeenRaw, p.getLastSeenPolicy()));
        repo.save(p);
        mirrorLastSeenBoolean(userId, p.getLastSeenPolicy());
        audit.record(userId, "presence.policy",
                null, "online=" + p.getOnlineStatusPolicy() + ",lastSeen=" + p.getLastSeenPolicy());
        return p;
    }

    /**
     * Whether {@code viewer} may see {@code owner}'s last seen, applying the
     * owner's policy and the reciprocity rule (a viewer who hides their own last
     * seen cannot see anyone else's). Blocks are handled upstream in
     * {@code PresenceService}.
     */
    @Transactional(readOnly = true)
    public boolean lastSeenVisibleTo(UUID viewer, UUID owner) {
        if (owner == null) return false;
        if (owner.equals(viewer)) return true;
        PresencePolicy ownerPolicy = get(owner).getLastSeenPolicy();
        if (ownerPolicy == PresencePolicy.NOBODY) return false;
        if (ownerPolicy == PresencePolicy.FRIENDS && !mutualFollow(viewer, owner)) return false;
        // Reciprocity: a viewer who hides their own last seen may not see others'.
        if (viewer != null && get(viewer).getLastSeenPolicy() == PresencePolicy.NOBODY) return false;
        return true;
    }

    private boolean mutualFollow(UUID a, UUID b) {
        return a != null && b != null
                && followRepo.isFollowing(a, b) && followRepo.isFollowing(b, a);
    }

    /** Keep the legacy boolean consistent so existing enforcement honours a full hide. */
    private void mirrorLastSeenBoolean(UUID userId, PresencePolicy policy) {
        try {
            ChatUserSettings s = chatSettingsRepo.findById(userId)
                    .orElseGet(() -> ChatUserSettings.builder().userId(userId).build());
            s.setLastSeenVisible(policy != PresencePolicy.NOBODY);
            chatSettingsRepo.save(s);
        } catch (Exception ex) {
            log.debug("[PRESENCE-POLICY] boolean mirror failed for {}: {}", userId, ex.getMessage());
        }
    }
}

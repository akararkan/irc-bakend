package ak.dev.irc.app.settings.privacy.service;

import ak.dev.irc.app.common.service.SocialGuard;
import ak.dev.irc.app.settings.privacy.entity.UserPrivacy;
import ak.dev.irc.app.settings.privacy.enums.FieldKey;
import ak.dev.irc.app.settings.privacy.enums.VisibilityDecision;
import ak.dev.irc.app.settings.privacy.enums.VisibilityLevel;
import ak.dev.irc.app.settings.privacy.repository.PrivacyListMemberRepository;
import ak.dev.irc.app.settings.privacy.repository.UserPrivacyRepository;
import ak.dev.irc.app.user.repository.CloseFriendsRepository;
import ak.dev.irc.app.user.repository.UserFollowRepository;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The single enforcement funnel for field-level visibility (spec §5) — the most
 * important component in the settings module. A pure function over
 * {@code (relationship, policy)} that fails closed and checks blocks first, so
 * it is covered by one truth-table unit test (the same technique as the chat
 * permission engine).
 *
 * <p>Evaluation order (fail closed):</p>
 * <pre>
 * 1. viewer == owner                    → ALLOW  (owners always see their own)
 * 2. block exists in EITHER direction   → DENY   (hard stop, before anything else)
 * 3. owner account suspended/deleted    → DENY
 * 4. policy = ONLY_ME                   → DENY
 * 5. policy = EVERYONE                  → ALLOW
 * 6. policy = FRIENDS                   → ALLOW if mutual follow
 * 7. policy = FOLLOWERS                 → ALLOW if viewer follows owner
 * 8. policy = CLOSE_FRIENDS / CUSTOM    → ALLOW if viewer ∈ list
 * 9. default                            → DENY
 * </pre>
 *
 * <p>This resolver is called thousands of times per feed render, so callers
 * should resolve once per {@code (viewer, owner)} pair and reuse. Follow/block
 * edges are already cached by {@link SocialGuard} / Spring cache.</p>
 */
@Service
@RequiredArgsConstructor
public class VisibilityResolver {

    private final SocialGuard socialGuard;
    private final UserFollowRepository followRepo;
    private final CloseFriendsRepository closeFriendsRepo;
    private final PrivacyListMemberRepository listMemberRepo;
    private final UserPrivacyRepository privacyRepo;
    private final UserRepository userRepo;

    @Transactional(readOnly = true)
    public VisibilityDecision resolve(UUID viewerId, UUID ownerId, FieldKey field) {
        if (ownerId == null || field == null) return VisibilityDecision.DENY;

        // 1. Owners always see their own.
        if (ownerId.equals(viewerId)) return VisibilityDecision.ALLOW;

        // 2. Block in either direction — hard stop before anything else.
        if (viewerId != null && socialGuard.isBlockedBetween(viewerId, ownerId)) {
            return VisibilityDecision.DENY;
        }

        // 3. Owner not a live account.
        if (!userRepo.existsByIdAndDeletedAtIsNull(ownerId)) {
            return VisibilityDecision.DENY;
        }

        VisibilityLevel policy = policyFor(ownerId, field);

        // 4–9. Policy resolution.
        return switch (policy) {
            case ONLY_ME  -> VisibilityDecision.DENY;
            case EVERYONE -> VisibilityDecision.ALLOW;
            case FRIENDS  -> mutualFollow(viewerId, ownerId)
                    ? VisibilityDecision.ALLOW : VisibilityDecision.DENY;
            case FOLLOWERS -> (viewerId != null && followRepo.isFollowing(viewerId, ownerId))
                    ? VisibilityDecision.ALLOW : VisibilityDecision.DENY;
            case CLOSE_FRIENDS -> (viewerId != null
                    && closeFriendsRepo.existsByIdOwnerIdAndIdFriendId(ownerId, viewerId))
                    ? VisibilityDecision.ALLOW : VisibilityDecision.DENY;
            case CUSTOM -> (viewerId != null
                    && listMemberRepo.isInAnyCustomList(ownerId, viewerId))
                    ? VisibilityDecision.ALLOW : VisibilityDecision.DENY;
        };
    }

    /** Boolean convenience over {@link #resolve}. */
    @Transactional(readOnly = true)
    public boolean isVisible(UUID viewerId, UUID ownerId, FieldKey field) {
        return resolve(viewerId, ownerId, field).allowed();
    }

    /** The effective policy for a field — stored value or the code default. */
    @Transactional(readOnly = true)
    public VisibilityLevel policyFor(UUID ownerId, FieldKey field) {
        VisibilityLevel def = PrivacyDefaults.forField(field);
        UserPrivacy up = privacyRepo.findById(ownerId).orElse(null);
        if (up == null || up.getPolicy() == null) return def;
        return VisibilityLevel.parse(up.getPolicy().get(field.name()), def);
    }

    private boolean mutualFollow(UUID viewerId, UUID ownerId) {
        return viewerId != null
                && followRepo.isFollowing(viewerId, ownerId)
                && followRepo.isFollowing(ownerId, viewerId);
    }
}

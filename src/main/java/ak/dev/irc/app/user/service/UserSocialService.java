package ak.dev.irc.app.user.service;

import ak.dev.irc.app.user.dto.response.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface UserSocialService {

    // ── Follow ────────────────────────────────────────────────────────────────
    SocialActionResponse follow(UUID targetId);
    SocialActionResponse unfollow(UUID targetId);

    // ── Block ─────────────────────────────────────────────────────────────────
    SocialActionResponse block(UUID targetId);
    SocialActionResponse unblock(UUID targetId);

    // ── Restrict ──────────────────────────────────────────────────────────────
    SocialActionResponse restrict(UUID targetId);
    SocialActionResponse unrestrict(UUID targetId);

    // ── Queries ───────────────────────────────────────────────────────────────
    Page<UserResponse>   getFollowers(UUID userId, Pageable pageable);
    Page<UserResponse>   getFollowing(UUID userId, Pageable pageable);
    Page<UserResponse>   getBlockedUsers(Pageable pageable);
    Page<UserResponse>   getRestrictedUsers(Pageable pageable);
    SocialStatusResponse getSocialStatus(UUID targetId);
}

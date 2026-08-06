package ak.dev.irc.app.user.service;

import ak.dev.irc.app.common.enums.AuditAction;
import ak.dev.irc.app.common.exception.DuplicateResourceException;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.entity.UserProfile;
import ak.dev.irc.app.user.enums.Role;
import ak.dev.irc.app.user.repository.UserProfileRepository;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * The single account-provisioning path, extracted from
 * {@code AuthServiceImpl.register} so self-signup and admin-create produce
 * structurally identical accounts (dup guards → user row → profile row →
 * search index). Admin-created accounts differ only in the knobs the command
 * exposes: explicit role, optional pre-verified email, optional null password
 * (invite flows set it later).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserProvisioningService {

    private final UserRepository        userRepository;
    private final UserProfileRepository profileRepository;
    private final PasswordEncoder       passwordEncoder;
    private final ak.dev.irc.app.user.search.service.UserSearchService userSearch;
    private final ak.dev.irc.app.admin.analytics.FunnelTracker funnelTracker;

    /**
     * @param rawPassword       plaintext password to encode, or {@code null}
     *                          for invite-style accounts that set it later
     * @param markEmailVerified stamps {@code email_verified_at} — the only
     *                          intended writer of that column (the self-serve
     *                          verify flow is dead scaffolding)
     */
    public record ProvisionCommand(String fname,
                                   String lname,
                                   String username,
                                   String email,
                                   String rawPassword,
                                   Role role,
                                   boolean enabled,
                                   boolean markEmailVerified,
                                   String auditNote) {
    }

    @Transactional
    public User provision(ProvisionCommand cmd) {
        if (userRepository.existsByEmail(cmd.email())) {
            throw new DuplicateResourceException("User", "email", cmd.email());
        }
        if (userRepository.existsByUsername(cmd.username())) {
            throw new DuplicateResourceException("User", "username", cmd.username());
        }

        User user = User.builder()
                .fname(cmd.fname())
                .lname(cmd.lname())
                .username(cmd.username())
                .email(cmd.email())
                .password(cmd.rawPassword() == null ? null : passwordEncoder.encode(cmd.rawPassword()))
                .role(cmd.role())
                .isEnabled(cmd.enabled())
                .build();
        if (cmd.markEmailVerified()) {
            user.setEmailVerifiedAt(LocalDateTime.now());
        }
        user.audit(AuditAction.CREATE, cmd.auditNote());
        user = userRepository.save(user);

        UserProfile profile = UserProfile.builder()
                .user(user)
                .displayName(user.getFname() + " " + user.getLname())
                .build();
        profile.audit(AuditAction.CREATE, "Profile created on provisioning");
        profileRepository.save(profile);

        userSearch.indexAsync(user.getId());
        funnelTracker.markFirstSeen(user.getId());

        log.info("User provisioned — id={}, email='{}', role={}, emailVerified={}",
                user.getId(), user.getEmail(), cmd.role(), cmd.markEmailVerified());
        return user;
    }
}

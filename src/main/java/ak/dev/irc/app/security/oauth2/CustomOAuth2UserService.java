package ak.dev.irc.app.security.oauth2;

import ak.dev.irc.app.common.enums.AuditAction;
import ak.dev.irc.app.common.exception.AppException;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.enums.Role;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Custom OAuth2 user service that:
 * <ol>
 *   <li>Loads user info from the OAuth2 provider (Google)</li>
 *   <li>Checks if the user already exists in our database by email</li>
 *   <li>If exists → updates profile image and last login</li>
 *   <li>If new → creates a new User record (auto-enabled, no password)</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);

        try {
            return processOAuth2User(userRequest, oAuth2User);
        } catch (AuthenticationException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("OAuth2 user processing failed — {}: {}", ex.getClass().getSimpleName(), ex.getMessage(), ex);
            throw new InternalAuthenticationServiceException(ex.getMessage(), ex.getCause());
        }
    }

    private OAuth2User processOAuth2User(OAuth2UserRequest userRequest, OAuth2User oAuth2User) {
        String registrationId = userRequest.getClientRegistration().getRegistrationId();
        OAuth2UserInfo userInfo = OAuth2UserInfo.of(registrationId, oAuth2User.getAttributes());

        if (userInfo.getEmail() == null || userInfo.getEmail().isBlank()) {
            log.warn("OAuth2 login failed — email not available from provider '{}'", registrationId);
            throw new AppException(
                    "Email not available from OAuth2 provider. Please use an account with a visible email.",
                    HttpStatus.BAD_REQUEST, "OAUTH2_EMAIL_MISSING");
        }

        log.info("OAuth2 login — provider='{}', email='{}', name='{}'",
                registrationId, userInfo.getEmail(), userInfo.getName());

        Optional<User> existingUser = userRepository.findByEmail(userInfo.getEmail());

        User user;
        if (existingUser.isPresent()) {
            user = updateExistingUser(existingUser.get(), userInfo, registrationId);
        } else {
            user = registerNewOAuth2User(userInfo, registrationId);
        }

        // Return a principal that Spring Security can work with
        return oAuth2User;
    }

    /**
     * Updates an existing user with fresh OAuth2 profile data.
     */
    private User updateExistingUser(User user, OAuth2UserInfo userInfo, String provider) {
        log.info("OAuth2 — existing user found [{}] ({}), updating from provider '{}'",
                user.getId(), user.getEmail(), provider);

        // Update profile image if provided and not already set
        if (userInfo.getImageUrl() != null
                && (user.getProfileImage() == null || user.getProfileImage().isBlank())) {
            user.setProfileImage(userInfo.getImageUrl());
            log.debug("Updated profile image for user [{}] from OAuth2", user.getId());
        }

        user.setLastLoginAt(LocalDateTime.now());
        user.audit(AuditAction.LOGIN, "OAuth2 login via " + provider);
        return userRepository.save(user);
    }

    /**
     * Creates a brand-new user from OAuth2 provider data.
     */
    private User registerNewOAuth2User(OAuth2UserInfo userInfo, String provider) {
        log.info("OAuth2 — registering new user from provider '{}', email='{}'",
                provider, userInfo.getEmail());

        // Generate a unique username from email prefix + random suffix
        String baseUsername = userInfo.getEmail().split("@")[0]
                .replaceAll("[^a-zA-Z0-9_]", "");
        String username = generateUniqueUsername(baseUsername);

        User user = User.builder()
                .fname(userInfo.getFirstName())
                .lname(userInfo.getLastName())
                .username(username)
                .email(userInfo.getEmail())
                .profileImage(userInfo.getImageUrl())
                .role(Role.USER)
                .isEnabled(true)               // OAuth2 users are auto-verified
                .emailVerifiedAt(LocalDateTime.now())
                .lastLoginAt(LocalDateTime.now())
                .build();
        user.audit(AuditAction.CREATE, "Registered via OAuth2: " + provider);

        user = userRepository.save(user);

        log.info("OAuth2 — new user registered: id={}, email='{}', username='{}'",
                user.getId(), user.getEmail(), user.getUsername());

        return user;
    }

    /**
     * Ensures the generated username doesn't collide with existing ones.
     */
    private String generateUniqueUsername(String base) {
        if (base.length() < 3) base = base + "user";
        if (base.length() > 40) base = base.substring(0, 40);

        if (!userRepository.existsByUsername(base)) {
            return base;
        }

        // Append random suffix until unique
        for (int i = 0; i < 10; i++) {
            String candidate = base + "_" + UUID.randomUUID().toString().substring(0, 6);
            if (candidate.length() > 50) candidate = candidate.substring(0, 50);
            if (!userRepository.existsByUsername(candidate)) {
                return candidate;
            }
        }

        // Fallback — extremely unlikely
        return base + "_" + System.currentTimeMillis();
    }
}

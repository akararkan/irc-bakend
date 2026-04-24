package ak.dev.irc.app.security.service;

import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads the {@link User} entity by email OR username.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>If the identifier contains '@' → treat as email</li>
 *   <li>Otherwise → treat as username</li>
 * </ol>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String identifier) throws UsernameNotFoundException {
        log.debug("Loading user by identifier '{}'", identifier);

        boolean isEmail = identifier != null && identifier.contains("@");

        User user = isEmail
                ? userRepository.findByEmailAndDeletedAtIsNull(identifier).orElse(null)
                : userRepository.findByUsernameAndDeletedAtIsNull(identifier).orElse(null);

        if (user == null) {
            log.warn("Authentication attempt with unknown {} '{}'",
                    isEmail ? "email" : "username", identifier);
            throw new UsernameNotFoundException(
                    "No user found with " + (isEmail ? "email" : "username") + ": " + identifier);
        }

        return user;
    }
}
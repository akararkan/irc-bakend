package ak.dev.irc.app.email;

import ak.dev.irc.app.common.exception.UnauthorizedException;
import ak.dev.irc.app.common.messages.EmailMessages;
import ak.dev.irc.app.security.SecurityUtils;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Read / update the authenticated user's email-notification preferences.
 *
 * <ul>
 *   <li>{@code GET /api/v1/users/me/email-preferences} — current flags.</li>
 *   <li>{@code PATCH /api/v1/users/me/email-preferences} — partial update;
 *       any field omitted keeps its existing value.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/users/me/email-preferences")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class EmailPreferencesController {

    private final UserRepository userRepository;
    private final EmailContextProvider emailContextProvider;
    private final EmailService emailService;

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<EmailPreferences> get() {
        User user = currentUser();
        return ResponseEntity.ok(EmailPreferences.from(user));
    }

    @PatchMapping
    @Transactional
    public ResponseEntity<EmailPreferences> update(@RequestBody EmailPreferences req) {
        User user = currentUser();
        if (req == null) return ResponseEntity.ok(EmailPreferences.from(user));

        if (req.master() != null)   user.setEmailNotificationsEnabled(req.master());
        if (req.social() != null)   user.setEmailSocialEnabled(req.social());
        if (req.mentions() != null) user.setEmailMentionsEnabled(req.mentions());
        if (req.system() != null)   user.setEmailSystemEnabled(req.system());
        if (req.trending() != null) user.setEmailTrendingEnabled(req.trending());

        userRepository.save(user);
        // The 60-second email-context cache must be invalidated so the new
        // toggles take effect immediately on the next outbound notification.
        emailContextProvider.evict(user.getId());
        return ResponseEntity.ok(EmailPreferences.from(user));
    }

    /**
     * Convenience one-click unsubscribe — turns the master toggle off.
     * Designed to be linked from the email footer in a future iteration
     * (signed-token URL → this endpoint).
     */
    /**
     * Send a self-test email to the caller's own address. Bypasses the
     * notification pipeline so users can verify SMTP without triggering
     * unrelated app activity. Useful when emails appear "sent" in logs but
     * never arrive — running this surfaces SMTP-level failures and helps
     * confirm the account isn't being silently spam-foldered.
     */
    @PostMapping("/test")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> sendTestEmail() {
        User user = currentUser();
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            return ResponseEntity.ok(Map.of(
                    "queued", false,
                    "reason", EmailMessages.NOTE_NO_EMAIL_ON_ACCOUNT));
        }
        String subject = EmailMessages.NOTIF_TEST_EMAIL_SUBJECT;
        String plain = EmailMessages.NOTIF_TEST_EMAIL_PLAIN.formatted(user.getFullName());
        String html = EmailMessages.NOTIF_TEST_EMAIL_HTML.formatted(user.getFullName());
        emailService.sendAsync(user.getEmail(), subject, plain, html);
        return ResponseEntity.ok(Map.of(
                "queued", true,
                "to", user.getEmail()));
    }

    @PostMapping("/unsubscribe-all")
    @Transactional
    public ResponseEntity<Map<String, Boolean>> unsubscribeAll() {
        User user = currentUser();
        user.setEmailNotificationsEnabled(false);
        userRepository.save(user);
        emailContextProvider.evict(user.getId());
        return ResponseEntity.ok(Map.of("emailNotificationsEnabled", false));
    }

    private User currentUser() {
        UUID id = SecurityUtils.getCurrentUserId()
                .orElseThrow(() -> new UnauthorizedException(EmailMessages.AUTH_UNAUTHORIZED_MSG));
        return userRepository.findActiveById(id)
                .orElseThrow(() -> new UnauthorizedException(EmailMessages.AUTH_UNAUTHORIZED_MSG));
    }

    /**
     * Boxed {@link Boolean} fields so a PATCH can update one flag without
     * forcing the client to repeat the others.
     */
    public record EmailPreferences(
            Boolean master,
            Boolean social,
            Boolean mentions,
            Boolean system,
            /** Daily "trending in scholarship" digest (X-style). Default: on. */
            Boolean trending
    ) {
        static EmailPreferences from(User u) {
            return new EmailPreferences(
                    u.isEmailNotificationsEnabled(),
                    u.isEmailSocialEnabled(),
                    u.isEmailMentionsEnabled(),
                    u.isEmailSystemEnabled(),
                    u.isEmailTrendingEnabled()
            );
        }
    }
}

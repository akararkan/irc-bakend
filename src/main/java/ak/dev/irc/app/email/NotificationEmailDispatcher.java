package ak.dev.irc.app.email;

import ak.dev.irc.app.user.dto.response.NotificationResponse;
import ak.dev.irc.app.user.enums.NotificationCategory;
import ak.dev.irc.app.user.realtime.NotificationPushedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Bridges the in-app notification pipeline to outbound email.
 *
 * <p>Listens for {@link NotificationPushedEvent} — the same event the SSE
 * push pipeline consumes. Every email here corresponds to a successfully
 * persisted notification, so phantom mail for rolled-back transactions is
 * impossible.</p>
 *
 * <p>Hot-path optimisation: instead of reloading the full {@code User}
 * entity, this dispatcher queries a slim {@link UserEmailContext} projection
 * via {@link EmailContextProvider}, which caches the result for 60 seconds
 * in Redis. A viral burst of N notifications now costs one DB read per
 * unique recipient, not N.</p>
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Throttle by {@code (recipient, groupKey)} — short-circuits before any DB hit.</li>
 *   <li>Resolve cached email context (1 round-trip on cache miss, 0 on hit).</li>
 *   <li>Skip silently when the user has muted the relevant category, has
 *       no usable email, or is soft-deleted.</li>
 *   <li>Hand off to {@link EmailService#sendAsync}.</li>
 * </ol>
 *
 * <p>The email is always sent to {@code recipient.email} — the notification
 * target, never the actor that triggered the event.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEmailDispatcher {

    private final EmailContextProvider contextProvider;
    private final EmailService         emailService;
    private final EmailTemplate        template;
    private final EmailThrottle        throttle;

    @EventListener
    public void onNotificationPushed(NotificationPushedEvent event) {
        if (event == null) return;

        NotificationResponse n = event.payload();
        UUID recipientId = event.recipientId();
        if (n == null || recipientId == null) {
            log.debug("[EMAIL-DISPATCH] skipped — empty payload or recipient");
            return;
        }

        if (!emailService.isEnabled()) {
            log.debug("[EMAIL-DISPATCH] skipped — email service disabled (notif type={} recipient={})",
                    n.type(), recipientId);
            return;
        }

        // Pre-throttle on the cheap — short-circuit before touching the DB
        // or the cache. Same `(recipient, groupKey)` won't be emailed twice
        // inside the throttle window.
        String dedupeKey = n.resourceId() != null && n.type() != null
                ? n.type() + ":" + n.resourceId()
                : (n.id() != null ? n.id().toString() : null);
        if (dedupeKey != null && !throttle.shouldSend(recipientId, dedupeKey)) {
            log.debug("[EMAIL-DISPATCH] throttled — userId={} key={}", recipientId, dedupeKey);
            return;
        }

        // Cached projection — 1 row, 6 columns, served from Redis on repeat hits.
        UserEmailContext ctx = contextProvider.get(recipientId);
        if (ctx == null) {
            log.warn("[EMAIL-DISPATCH] recipient {} not found / inactive — skip", recipientId);
            return;
        }

        if (!isEnabledForUser(ctx, n.category())) {
            log.debug("[EMAIL-DISPATCH] muted by user prefs — userId={} category={}",
                    recipientId, n.category());
            return;
        }

        String to = ctx.email();
        if (to == null || to.isBlank()) {
            log.warn("[EMAIL-DISPATCH] recipient {} has no email address — skip", recipientId);
            return;
        }

        EmailTemplate.Rendered rendered = template.render(n, ctx.fullName());

        log.info("[EMAIL-DISPATCH] queueing '{}' → {} (type={} category={})",
                rendered.subject(), to, n.type(), n.category());
        emailService.sendAsync(to, rendered.subject(), rendered.plainBody(), rendered.htmlBody());
    }

    /**
     * Master toggle plus the three coarse buckets. POST / QNA / RESEARCH
     * activity rides the {@code social} flag; mentions and system messages
     * have their own toggles so users can keep security mail on while
     * muting interaction noise.
     */
    private boolean isEnabledForUser(UserEmailContext ctx, NotificationCategory category) {
        if (!ctx.emailNotificationsEnabled()) return false;
        if (category == null) return true;
        return switch (category) {
            case SOCIAL              -> ctx.emailSocialEnabled();
            case MENTIONS            -> ctx.emailMentionsEnabled();
            case SYSTEM              -> ctx.emailSystemEnabled();
            case POSTS, QNA, RESEARCH -> ctx.emailSocialEnabled();
        };
    }
}

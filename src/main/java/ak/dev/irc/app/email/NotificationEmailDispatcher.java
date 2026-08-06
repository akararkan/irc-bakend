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
    private final EmailSendLogRepository sendLogRepository;

    @EventListener
    public void onNotificationPushed(NotificationPushedEvent event) {
        if (event == null) return;

        NotificationResponse n = event.payload();
        UUID recipientId = event.recipientId();
        if (n == null || recipientId == null) {
            log.debug("[EMAIL-DISPATCH] skipped — empty payload or recipient");
            return;
        }
        String kind = n.type() != null ? n.type().name() : null;
        String dedupeKey = n.resourceId() != null && n.type() != null
                ? n.type() + ":" + n.resourceId()
                : (n.id() != null ? n.id().toString() : null);

        if (!emailService.isEnabled()) {
            log.debug("[EMAIL-DISPATCH] skipped — email service disabled (notif type={} recipient={})",
                    n.type(), recipientId);
            ledger(recipientId, kind, dedupeKey, EmailSendLog.Outcome.DISABLED, "email service off");
            return;
        }

        // Pre-throttle on the cheap — short-circuit before touching the DB
        // or the cache. Same `(recipient, groupKey)` won't be emailed twice
        // inside the throttle window.
        if (dedupeKey != null && !throttle.shouldSend(recipientId, dedupeKey)) {
            log.debug("[EMAIL-DISPATCH] throttled — userId={} key={}", recipientId, dedupeKey);
            ledger(recipientId, kind, dedupeKey, EmailSendLog.Outcome.THROTTLED, null);
            return;
        }

        // Cached projection — 1 row, 6 columns, served from Redis on repeat hits.
        UserEmailContext ctx = contextProvider.get(recipientId);
        if (ctx == null) {
            log.warn("[EMAIL-DISPATCH] recipient {} not found / inactive — skip", recipientId);
            ledger(recipientId, kind, dedupeKey, EmailSendLog.Outcome.SKIPPED, "recipient inactive");
            return;
        }

        if (!isEnabledForUser(ctx, n.type(), n.category())) {
            log.debug("[EMAIL-DISPATCH] muted by user prefs — userId={} category={} type={}",
                    recipientId, n.category(), n.type());
            ledger(recipientId, kind, dedupeKey, EmailSendLog.Outcome.MUTED, null);
            return;
        }

        String to = ctx.email();
        if (to == null || to.isBlank()) {
            log.warn("[EMAIL-DISPATCH] recipient {} has no email address — skip", recipientId);
            ledger(recipientId, kind, dedupeKey, EmailSendLog.Outcome.NO_ADDRESS, null);
            return;
        }

        EmailTemplate.Rendered rendered = template.render(n, ctx.fullName());

        log.info("[EMAIL-DISPATCH] queueing '{}' → {} (type={} category={})",
                rendered.subject(), to, n.type(), n.category());
        emailService.sendAsync(to, rendered.subject(), rendered.plainBody(), rendered.htmlBody());
        ledger(recipientId, kind, dedupeKey, EmailSendLog.Outcome.QUEUED, null);
    }

    /** Append-only send-ledger write — best-effort, never blocks dispatch. */
    private void ledger(UUID recipientId, String kind, String groupKey,
                        EmailSendLog.Outcome outcome, String error) {
        try {
            sendLogRepository.save(EmailSendLog.builder()
                    .recipientId(recipientId)
                    .kind(kind)
                    .groupKey(groupKey != null && groupKey.length() > 160
                            ? groupKey.substring(0, 160) : groupKey)
                    .outcome(outcome)
                    .error(error)
                    .build());
        } catch (Exception e) {
            log.debug("[EMAIL-DISPATCH] ledger write failed: {}", e.getMessage());
        }
    }

    /**
     * Master toggle plus the four coarse buckets. POST / QNA / RESEARCH
     * activity rides the {@code social} flag; mentions and system messages
     * have their own toggles so users can keep security mail on while
     * muting interaction noise.
     *
     * <p>{@code TRENDING_DIGEST} is the one type that lives in the
     * {@code SYSTEM} inbox category but is gated by its own
     * {@code emailTrendingEnabled} toggle — so a user can mute the daily
     * digest without losing account warnings or system messages.</p>
     */
    private boolean isEnabledForUser(UserEmailContext ctx,
                                     ak.dev.irc.app.user.enums.NotificationType type,
                                     NotificationCategory category) {
        if (!ctx.emailNotificationsEnabled()) return false;
        if (type == ak.dev.irc.app.user.enums.NotificationType.TRENDING_DIGEST) {
            return ctx.emailTrendingEnabled();
        }
        if (category == null) return true;
        return switch (category) {
            case SOCIAL              -> ctx.emailSocialEnabled();
            case MENTIONS            -> ctx.emailMentionsEnabled();
            case SYSTEM              -> ctx.emailSystemEnabled();
            case POSTS, QNA, RESEARCH -> ctx.emailSocialEnabled();
            // Chat is in-app only (kinds are emailEligible=false) — gate under social if ever reached.
            case CHAT                -> ctx.emailSocialEnabled();
        };
    }
}

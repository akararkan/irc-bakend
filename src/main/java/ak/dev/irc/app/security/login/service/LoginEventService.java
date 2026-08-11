package ak.dev.irc.app.security.login.service;

import ak.dev.irc.app.common.messages.SecurityMessages;
import ak.dev.irc.app.security.login.entity.LoginEvent;
import ak.dev.irc.app.security.login.repository.LoginEventRepository;
import ak.dev.irc.app.user.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Records login history and raises new-device alerts (spec §12). The alert path
 * uses {@link NotificationService#sendSystemNotification} — a security alert must
 * always reach the user (it deliberately bypasses DND and channel preferences).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginEventService {

    private final LoginEventRepository repo;
    private final NotificationService notificationService;
    private final ak.dev.irc.app.admin.analytics.MetricDailyService metricDaily;
    private final ak.dev.irc.app.admin.analytics.FunnelTracker funnelTracker;

    @Transactional
    public LoginEvent record(UUID userId, String ip, String userAgent, String method, String outcome) {
        // Telemetry tee: login outcomes are the cheapest correct DAU source.
        if ("SUCCESS".equalsIgnoreCase(outcome)) {
            metricDaily.bump("login.success");
            metricDaily.markActive(userId);
            funnelTracker.markFirstSeen(userId);
        } else if (outcome != null) {
            metricDaily.bump("login." + outcome.toLowerCase());
        }
        return repo.save(LoginEvent.builder()
                .userId(userId).ip(ip).userAgent(userAgent)
                .method(method).outcome(outcome).build());
    }

    /**
     * Record an outcome in its <b>own</b> transaction.
     *
     * <p>Every unsuccessful-login path records the attempt and then throws. With
     * the default {@code REQUIRED} propagation that row joins the caller's
     * transaction and is rolled back by the very exception it was meant to
     * document — so the FAILED and MFA_REQUIRED rows, precisely the ones a
     * brute-force investigation reads, silently never landed. {@code
     * REQUIRES_NEW} commits the audit row independently of the request's
     * outcome.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LoginEvent recordIndependent(UUID userId, String ip, String userAgent,
                                        String method, String outcome) {
        return record(userId, ip, userAgent, method, outcome);
    }

    /**
     * Record a successful login and, if it comes from an IP never seen before
     * for this user, emit a security alert. The "new device" heuristic is
     * intentionally IP-based-plus-first-seen; a real impl would fold in the
     * device fingerprint from the session row.
     */
    @Transactional
    public void recordSuccessAndAlertIfNew(UUID userId, String ip, String userAgent, String method) {
        List<String> known = repo.distinctSuccessfulIps(userId);
        boolean isNew = ip != null && !known.contains(ip) && !known.isEmpty();
        record(userId, ip, userAgent, method, "SUCCESS");
        if (isNew) {
            try {
                notificationService.sendSystemNotification(userId,
                        SecurityMessages.NOTIF_NEW_DEVICE_TITLE,
                        "A new device signed in" + (ip != null ? " from " + ip : "")
                                + ". If this wasn't you, secure your account now.");
            } catch (Exception ex) {
                log.warn("[LOGIN-ALERT] failed to send new-device alert to {}: {}", userId, ex.getMessage());
            }
        }
    }

    @Transactional(readOnly = true)
    public Page<LoginEvent> history(UUID userId, Pageable pageable) {
        return repo.findByUserIdOrderByTsDesc(userId, pageable);
    }
}

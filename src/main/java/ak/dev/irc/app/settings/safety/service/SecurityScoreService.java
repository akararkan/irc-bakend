package ak.dev.irc.app.settings.safety.service;

import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.settings.safety.dto.SafetyDtos.ScoreItem;
import ak.dev.irc.app.settings.safety.dto.SafetyDtos.SecurityScoreResponse;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Derived security score + privacy recommendations (spec §18). Never stored: a
 * small rules engine reads the user's actual configuration and returns a scored
 * checklist, so it is always current and needs no migration when a rule is added.
 */
@Service
@RequiredArgsConstructor
public class SecurityScoreService {

    private final UserRepository userRepo;

    @Transactional(readOnly = true)
    public SecurityScoreResponse computeScore(UUID userId) {
        User u = userRepo.findActiveById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        List<Check> checks = List.of(
                new Check("two_factor", "Two-factor authentication enabled", 40,
                        u.isTwoFactorEnabled(),
                        "Enable two-factor authentication to protect your account from stolen passwords."),
                new Check("recovery", "Account recovery configured", 30,
                        u.isEmailVerified(),
                        "Verify a recovery channel so you can regain access if you're locked out."),
                new Check("email_verified", "Email address verified", 20,
                        u.isEmailVerified(),
                        "Verify your email address to receive security alerts."),
                new Check("recent_review", "Account activity reviewed recently", 10,
                        u.getLastLoginAt() != null
                                && u.getLastLoginAt().isAfter(LocalDateTime.now().minusDays(90)),
                        "Review your active sessions and recent login history.")
        );

        int totalWeight = checks.stream().mapToInt(Check::weight).sum();
        int earned = checks.stream().filter(Check::passed).mapToInt(Check::weight).sum();
        int score = totalWeight == 0 ? 0 : Math.round(100f * earned / totalWeight);

        List<ScoreItem> items = new ArrayList<>();
        for (Check c : checks) {
            items.add(new ScoreItem(c.key(), c.label(), c.passed(),
                    c.passed() ? null : c.recommendation()));
        }
        return new SecurityScoreResponse(score, level(score), items);
    }

    private static String level(int score) {
        if (score < 40) return "LOW";
        if (score < 70) return "MEDIUM";
        if (score < 90) return "HIGH";
        return "EXCELLENT";
    }

    private record Check(String key, String label, int weight, boolean passed, String recommendation) {}
}

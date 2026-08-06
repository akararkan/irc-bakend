package ak.dev.irc.app.admin.analytics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Incremental writer for {@code user_first_events} — each milestone is set
 * once and never overwritten; every write is fail-open (funnel telemetry must
 * never fail a user action). Hooked from: provisioning + login success
 * (first-seen), profile save with bio/avatar (profile-completed), the follow
 * consumer (first-follow), and the activity sink's creation types
 * (first-content).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FunnelTracker {

    private final UserFirstEventsRepository repository;

    public void markFirstSeen(UUID userId) {
        mark(userId, (row, now) -> {
            if (row.getFirstSeen() == null) row.setFirstSeen(now);
        });
    }

    public void markProfileCompleted(UUID userId) {
        mark(userId, (row, now) -> {
            if (row.getProfileCompletedAt() == null) row.setProfileCompletedAt(now);
        });
    }

    public void markFirstFollow(UUID userId) {
        mark(userId, (row, now) -> {
            if (row.getFirstFollowAt() == null) row.setFirstFollowAt(now);
        });
    }

    public void markFirstContent(UUID userId) {
        mark(userId, (row, now) -> {
            if (row.getFirstContentAt() == null) row.setFirstContentAt(now);
        });
    }

    private void mark(UUID userId, BiConsumer<UserFirstEvents, LocalDateTime> setter) {
        if (userId == null) return;
        try {
            UserFirstEvents row = repository.findById(userId)
                    .orElseGet(() -> UserFirstEvents.builder().userId(userId).build());
            setter.accept(row, LocalDateTime.now());
            repository.save(row);
        } catch (Exception e) {
            log.debug("[FUNNEL] milestone write failed for {}: {}", userId, e.getMessage());
        }
    }
}

package ak.dev.irc.app.settings.consent.service;

import ak.dev.irc.app.settings.consent.entity.ConsentEvent;
import ak.dev.irc.app.settings.consent.repository.ConsentEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Records and reads consent evidence (spec §14). Append-only: a change in
 * consent state for a scope is always a new {@link ConsentEvent}, never an
 * update, so the full history (and the exact withdrawal time) is preserved.
 */
@Service
@RequiredArgsConstructor
public class ConsentService {

    private final ConsentEventRepository repo;

    /** Append a grant ({@code granted=true}) or revocation ({@code granted=false}). */
    @Transactional
    public ConsentEvent record(UUID userId, String scope, boolean granted, String appVersion) {
        return repo.save(ConsentEvent.builder()
                .userId(userId)
                .scope(scope == null ? "" : scope.trim().toUpperCase())
                .granted(granted)
                .appVersion(appVersion)
                .build());
    }

    @Transactional(readOnly = true)
    public Page<ConsentEvent> history(UUID userId, Pageable pageable) {
        return repo.findByUserIdOrderByOccurredAtDesc(userId, pageable);
    }

    /** Current consent for a scope — the latest event's value, or {@code false} if none. */
    @Transactional(readOnly = true)
    public boolean currentState(UUID userId, String scope) {
        return repo.findFirstByUserIdAndScopeOrderByOccurredAtDesc(
                        userId, scope == null ? "" : scope.trim().toUpperCase())
                .map(ConsentEvent::isGranted)
                .orElse(false);
    }
}

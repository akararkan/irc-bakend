package ak.dev.irc.app.settings.discovery.service;

import ak.dev.irc.app.settings.audit.service.SettingsAuditService;
import ak.dev.irc.app.settings.discovery.entity.UserDiscoverability;
import ak.dev.irc.app.settings.discovery.repository.UserDiscoverabilityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Search & discovery flags (spec §6). Stores the per-user discoverability policy
 * and exposes {@link #isDiscoverableBy} so the search / QR / directory paths can
 * gate results. The values should also be denormalised into the Elasticsearch
 * document ({@code discover.*}, {@code indexable}) — that is the documented seam
 * ({@code UserSearchService.buildDoc} + a {@code profile.updated} reindex).
 */
@Service
@RequiredArgsConstructor
public class DiscoverabilityService {

    public enum Method { USERNAME, PHONE, EMAIL, QR }

    private final UserDiscoverabilityRepository repo;
    private final SettingsAuditService audit;

    @Transactional
    public UserDiscoverability get(UUID userId) {
        return repo.findById(userId).orElseGet(() -> UserDiscoverability.defaultsFor(userId));
    }

    @Transactional
    public UserDiscoverability update(UUID userId, Boolean byUsername, Boolean byPhone,
                                      Boolean byEmail, Boolean byQr, Boolean indexable) {
        UserDiscoverability d = get(userId);
        if (byUsername != null) d.setByUsername(byUsername);
        if (byPhone != null)    d.setByPhone(byPhone);
        if (byEmail != null)    d.setByEmail(byEmail);
        if (byQr != null)       d.setByQr(byQr);
        if (indexable != null)  d.setIndexable(indexable);
        UserDiscoverability saved = repo.save(d);
        audit.record(userId, "discovery.flags", null,
                "byUsername=" + saved.isByUsername() + ",byPhone=" + saved.isByPhone()
                        + ",byEmail=" + saved.isByEmail() + ",byQr=" + saved.isByQr()
                        + ",indexable=" + saved.isIndexable());
        // SEAM: publish profile.updated → indexer patches discover.* on the ES doc.
        return saved;
    }

    /** Whether {@code userId} may be found via a given method (defaults applied). */
    @Transactional(readOnly = true)
    public boolean isDiscoverableBy(UUID userId, Method method) {
        UserDiscoverability d = get(userId);
        return switch (method) {
            case USERNAME -> d.isByUsername();
            case PHONE    -> d.isByPhone();
            case EMAIL    -> d.isByEmail();
            case QR       -> d.isByQr();
        };
    }

    @Transactional(readOnly = true)
    public boolean isIndexable(UUID userId) {
        return get(userId).isIndexable();
    }
}

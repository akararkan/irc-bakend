package ak.dev.irc.app.settings.privacy.service;

import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.settings.audit.service.SettingsAuditService;
import ak.dev.irc.app.settings.privacy.entity.UserPrivacy;
import ak.dev.irc.app.settings.privacy.enums.FieldKey;
import ak.dev.irc.app.settings.privacy.enums.VisibilityLevel;
import ak.dev.irc.app.settings.privacy.repository.UserPrivacyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages a user's field visibility policy (spec §5). Reads merge stored values
 * over {@link PrivacyDefaults}; writes are audited.
 */
@Service
@RequiredArgsConstructor
public class PrivacyService {

    private final UserPrivacyRepository repo;
    private final SettingsAuditService audit;

    /** Full resolved policy map (defaults merged) as {@code FieldKey → level}. */
    @Transactional(readOnly = true)
    public Map<FieldKey, VisibilityLevel> resolvedPolicy(UUID userId) {
        Map<FieldKey, VisibilityLevel> out = new EnumMap<>(FieldKey.class);
        Map<String, String> stored = repo.findById(userId)
                .map(UserPrivacy::getPolicy).orElse(Map.of());
        for (FieldKey f : FieldKey.values()) {
            out.put(f, VisibilityLevel.parse(
                    stored == null ? null : stored.get(f.name()),
                    PrivacyDefaults.forField(f)));
        }
        return out;
    }

    /** Same content as {@link #resolvedPolicy} but string-keyed for JSON responses. */
    @Transactional(readOnly = true)
    public Map<String, String> resolvedPolicyAsStrings(UUID userId) {
        Map<String, String> out = new HashMap<>();
        resolvedPolicy(userId).forEach((k, v) -> out.put(k.name(), v.name()));
        return out;
    }

    @Transactional
    public Map<String, String> setFieldPolicy(UUID userId, String fieldRaw, String levelRaw) {
        FieldKey field = FieldKey.parse(fieldRaw);
        if (field == null) throw new BadRequestException("Unknown privacy field: " + fieldRaw);
        VisibilityLevel level = VisibilityLevel.parse(levelRaw, null);
        if (level == null) throw new BadRequestException("Unknown visibility level: " + levelRaw);

        UserPrivacy up = repo.findById(userId).orElseGet(() -> UserPrivacy.emptyFor(userId));
        if (up.getPolicy() == null) up.setPolicy(new HashMap<>());
        String old = up.getPolicy().get(field.name());
        up.getPolicy().put(field.name(), level.name());
        repo.save(up);
        audit.record(userId, "privacy." + field.name(),
                old, level.name());
        return resolvedPolicyAsStrings(userId);
    }
}

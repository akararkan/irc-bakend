package ak.dev.irc.app.settings.core.service;

import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.settings.audit.service.SettingsAuditService;
import ak.dev.irc.app.settings.core.dto.SettingsResponse;
import ak.dev.irc.app.settings.core.entity.*;
import ak.dev.irc.app.settings.core.repository.UserSettingsRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Aggregates the cosmetic/structured settings blocks (spec §10, §11, §9, §20.3)
 * with Redis caching (§22.2) and audit (§22.3).
 *
 * <p>Two update modes per block, matching the API:</p>
 * <ul>
 *   <li><b>PATCH</b> — JSON Merge Patch: only the supplied fields change.</li>
 *   <li><b>PUT</b>  — full replace of the block with a validated bean.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsService {

    public static final String APPEARANCE    = "appearance";
    public static final String ACCESSIBILITY = "accessibility";
    public static final String MESSAGES      = "messages";
    public static final String MEDIA         = "media";

    private final UserSettingsRepository repo;
    private final SettingsCache cache;
    private final SettingsAuditService audit;
    private final ObjectMapper objectMapper;

    // ── Read ──────────────────────────────────────────────────────────────────

    @Transactional
    public UserSettings getOrCreate(UUID userId) {
        return repo.findById(userId)
                .map(UserSettings::withDefaults)
                .orElseGet(() -> repo.save(UserSettings.defaultsFor(userId)));
    }

    @Transactional
    public SettingsResponse resolve(UUID userId) {
        return cache.get(userId).orElseGet(() -> {
            SettingsResponse resp = toResponse(getOrCreate(userId));
            cache.put(userId, resp);
            return resp;
        });
    }

    @Transactional
    public Object getSection(UUID userId, String section) {
        UserSettings s = getOrCreate(userId);
        return switch (normalize(section)) {
            case APPEARANCE    -> s.getAppearance();
            case ACCESSIBILITY -> s.getAccessibility();
            case MESSAGES      -> s.getMessages();
            case MEDIA         -> s.getMedia();
            default -> throw unknownSection(section);
        };
    }

    // ── PATCH (JSON Merge Patch) ────────────────────────────────────────────────

    @Transactional
    public Object patchSection(UUID userId, String section, Map<String, Object> patch) {
        UserSettings s = getOrCreate(userId);
        String key = normalize(section);
        Object oldValue;
        Object newValue;
        switch (key) {
            case APPEARANCE -> {
                oldValue = s.getAppearance();
                s.setAppearance(merge(s.getAppearance(), patch, AppearanceSettings.class));
                newValue = s.getAppearance();
            }
            case ACCESSIBILITY -> {
                oldValue = s.getAccessibility();
                s.setAccessibility(merge(s.getAccessibility(), patch, AccessibilitySettings.class));
                newValue = s.getAccessibility();
            }
            case MESSAGES -> {
                oldValue = s.getMessages();
                s.setMessages(merge(s.getMessages(), patch, MessageSettings.class));
                newValue = s.getMessages();
            }
            case MEDIA -> {
                oldValue = s.getMedia();
                s.setMedia(merge(s.getMedia(), patch, MediaSettings.class));
                newValue = s.getMedia();
            }
            default -> throw unknownSection(section);
        }
        return persist(s, userId, "settings." + key, oldValue, newValue);
    }

    // ── PUT (full replace) ──────────────────────────────────────────────────────

    @Transactional
    public Object replaceSection(UUID userId, String section, Map<String, Object> body) {
        UserSettings s = getOrCreate(userId);
        String key = normalize(section);
        Object oldValue;
        Object newValue;
        switch (key) {
            case APPEARANCE -> {
                oldValue = s.getAppearance();
                s.setAppearance(convert(body, AppearanceSettings.class));
                newValue = s.getAppearance();
            }
            case ACCESSIBILITY -> {
                oldValue = s.getAccessibility();
                s.setAccessibility(convert(body, AccessibilitySettings.class));
                newValue = s.getAccessibility();
            }
            case MESSAGES -> {
                oldValue = s.getMessages();
                s.setMessages(convert(body, MessageSettings.class));
                newValue = s.getMessages();
            }
            case MEDIA -> {
                oldValue = s.getMedia();
                s.setMedia(convert(body, MediaSettings.class));
                newValue = s.getMedia();
            }
            default -> throw unknownSection(section);
        }
        return persist(s, userId, "settings." + key, oldValue, newValue);
    }

    // ── internals ───────────────────────────────────────────────────────────────

    private Object persist(UserSettings s, UUID userId, String key, Object oldV, Object newV) {
        s.setSettingsVersion(s.getSettingsVersion() + 1);
        repo.save(s);
        cache.invalidate(userId);
        audit.record(userId, key, writeJson(oldV), writeJson(newV));
        return newV;
    }

    private <T> T merge(T current, Map<String, Object> patch, Class<T> type) {
        try {
            ObjectReader updater = objectMapper.readerForUpdating(current);
            return updater.readValue(objectMapper.writeValueAsString(patch));
        } catch (Exception ex) {
            throw new BadRequestException(
                    "Invalid settings patch for " + type.getSimpleName() + ": " + ex.getMessage());
        }
    }

    private <T> T convert(Map<String, Object> body, Class<T> type) {
        try {
            return objectMapper.convertValue(body, type);
        } catch (Exception ex) {
            throw new BadRequestException(
                    "Invalid settings body for " + type.getSimpleName() + ": " + ex.getMessage());
        }
    }

    private String writeJson(Object v) {
        try {
            return v == null ? null : objectMapper.writeValueAsString(v);
        } catch (Exception ex) {
            return String.valueOf(v);
        }
    }

    private SettingsResponse toResponse(UserSettings s) {
        return SettingsResponse.builder()
                .appearance(s.getAppearance())
                .accessibility(s.getAccessibility())
                .messages(s.getMessages())
                .media(s.getMedia())
                .settingsVersion(s.getSettingsVersion())
                .build();
    }

    private String normalize(String section) {
        return section == null ? "" : section.trim().toLowerCase();
    }

    private BadRequestException unknownSection(String section) {
        return new BadRequestException("Unknown or non-cosmetic settings section: " + section);
    }
}

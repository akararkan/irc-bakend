package ak.dev.irc.app.admin.trending;

import ak.dev.irc.app.admin.support.AdminAuditor;
import ak.dev.irc.app.admin.support.RequiresStepUp;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.messages.AdminContentMessages;
import ak.dev.irc.app.common.tag.entity.TrendingTagOverride;
import ak.dev.irc.app.common.tag.job.TrendingTagJob;
import ak.dev.irc.app.common.tag.repository.TrendingTagOverrideRepository;
import ak.dev.irc.app.security.SecurityUtils;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Editorial control over the trending-tags surface
 * (search-feed-trending.md §4/§6.2): PIN/BAN overrides consulted by the
 * rebuild job, plus a manual rebuild trigger so an override doesn't have to
 * wait out the refresh interval.
 */
@RestController
@RequestMapping("/api/v1/admin/trending")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminTrendingController {

    private static final Set<String> SCOPES = Set.of("ALL", "QUESTION", "RESEARCH", "POST", "REEL");

    private final TrendingTagOverrideRepository overrideRepository;
    private final TrendingTagJob trendingTagJob;
    private final AdminAuditor adminAuditor;

    public record OverrideRequest(@NotBlank String tag,
                                  String scope,
                                  @NotBlank String type,
                                  Integer rank,
                                  LocalDateTime expiresAt,
                                  @Size(max = 500) String reason) {
    }

    @GetMapping("/overrides")
    public ResponseEntity<List<TrendingTagOverride>> overrides(
            @RequestParam(required = false, defaultValue = "true") boolean active) {
        List<TrendingTagOverride> all = active
                ? overrideRepository.findByRevokedAtIsNull()
                : overrideRepository.findAll();
        return ResponseEntity.ok(all);
    }

    @PostMapping("/overrides")
    @RequiresStepUp   // editorializes a public surface
    public ResponseEntity<TrendingTagOverride> create(@RequestBody OverrideRequest body) {
        String scope = normalizeScope(body.scope());
        TrendingTagOverride.OverrideType type = parseType(body.type());
        String tag = normalizeTag(body.tag());
        TrendingTagOverride saved = overrideRepository.save(TrendingTagOverride.builder()
                .tagNormalized(tag)
                .scope(scope)
                .type(type)
                .pinnedRank(body.rank())
                .reason(body.reason())
                .expiresAt(body.expiresAt())
                .createdBy(SecurityUtils.getCurrentUserId().orElse(null))
                .build());
        adminAuditor.record(AuditOperation.CREATE, "TrendingTag", saved.getId(),
                "ADMIN_TRENDING_OVERRIDE_SET", type + " '" + tag + "' scope=" + scope);
        return ResponseEntity.status(201).body(saved);
    }

    @DeleteMapping("/overrides/{id}")
    public ResponseEntity<Void> revoke(@PathVariable UUID id) {
        TrendingTagOverride o = overrideRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("TrendingTagOverride", "id", id));
        o.setRevokedAt(LocalDateTime.now());
        overrideRepository.save(o);
        adminAuditor.record(AuditOperation.DELETE, "TrendingTag", id,
                "ADMIN_TRENDING_OVERRIDE_REMOVED", o.getType() + " '" + o.getTagNormalized() + "'");
        return ResponseEntity.noContent().build();
    }

    /** Force an immediate snapshot rebuild (overrides apply right away). */
    @PostMapping("/rebuild")
    public ResponseEntity<Void> rebuild() {
        trendingTagJob.rebuildTrending();
        adminAuditor.record(AuditOperation.UPDATE, "TrendingTag", null, "ADMIN_TRENDING_REBUILD");
        return ResponseEntity.accepted().build();
    }

    public static String normalizeScope(String raw) {
        String scope = raw == null || raw.isBlank() ? "ALL" : raw.trim().toUpperCase(Locale.ROOT);
        if (!SCOPES.contains(scope)) {
            throw new BadRequestException(
                    AdminContentMessages.INVALID_SCOPE_MSG.formatted(SCOPES),
                    AdminContentMessages.INVALID_SCOPE);
        }
        return scope;
    }

    public static TrendingTagOverride.OverrideType parseType(String raw) {
        try {
            return TrendingTagOverride.OverrideType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new BadRequestException(AdminContentMessages.INVALID_TYPE_PIN_BAN_MSG,
                    AdminContentMessages.INVALID_TYPE);
        }
    }

    public static String normalizeTag(String raw) {
        String tag = raw.trim().toLowerCase(Locale.ROOT);
        if (tag.startsWith("#")) tag = tag.substring(1);
        if (tag.isBlank() || tag.length() > 100) {
            throw new BadRequestException(AdminContentMessages.INVALID_TAG_MSG,
                    AdminContentMessages.INVALID_TAG);
        }
        return tag;
    }
}

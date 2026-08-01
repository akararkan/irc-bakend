package ak.dev.irc.app.settings.policy.dto;

import java.time.LocalDateTime;

/** DTOs for the About / app-config / policy surface (spec §19). */
public final class PolicyDtos {

    private PolicyDtos() {}

    public record AppConfigResponse(String minSupportedVersion,
                                    boolean forceUpdate,
                                    String latestVersion) {}

    public record PolicyDocResponse(String key,
                                    String version,
                                    String effectiveDate,
                                    String title,
                                    String body) {}

    public record PolicyAcceptanceResponse(String policyKey,
                                           String version,
                                           LocalDateTime acceptedAt) {}
}

package ak.dev.irc.app.settings.consent.dto;

import ak.dev.irc.app.settings.consent.entity.ConsentEvent;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;
import java.util.UUID;

/** Request/response DTOs for the consent surface (spec §14). */
public final class ConsentDtos {

    private ConsentDtos() {}

    public record RecordConsentRequest(@NotBlank String scope, boolean granted, String appVersion) {}

    public record ConsentEventResponse(UUID id, String scope, boolean granted,
                                       String appVersion, LocalDateTime occurredAt) {
        public static ConsentEventResponse of(ConsentEvent e) {
            return new ConsentEventResponse(e.getId(), e.getScope(), e.isGranted(),
                    e.getAppVersion(), e.getOccurredAt());
        }
    }

    public record ConsentStateResponse(String scope, boolean granted) {}
}

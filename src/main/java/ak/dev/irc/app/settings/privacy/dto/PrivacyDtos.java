package ak.dev.irc.app.settings.privacy.dto;

import ak.dev.irc.app.settings.privacy.entity.HiddenKeyword;
import ak.dev.irc.app.settings.privacy.entity.PrivacyList;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.UUID;

/** Request/response DTOs for the privacy settings surface (spec §5). */
public final class PrivacyDtos {

    private PrivacyDtos() {}

    public record SetVisibilityRequest(@NotBlank String visibility) {}

    public record CreateListRequest(@NotBlank String name) {}

    public record AddMemberRequest(@NotNull UUID memberId) {}

    public record AddKeywordRequest(@NotBlank String keyword) {}

    public record MuteRequest(@NotNull UUID userId) {}

    public record PrivacyListResponse(UUID id, String name, String type, LocalDateTime createdAt) {
        public static PrivacyListResponse of(PrivacyList l) {
            return new PrivacyListResponse(l.getId(), l.getName(),
                    l.getType().name(), l.getCreatedAt());
        }
    }

    public record HiddenKeywordResponse(UUID id, String keyword, LocalDateTime createdAt) {
        public static HiddenKeywordResponse of(HiddenKeyword k) {
            return new HiddenKeywordResponse(k.getId(), k.getKeywordDisplay(), k.getCreatedAt());
        }
    }
}

package ak.dev.irc.app.user.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record UpdateSpecializationsRequest(
    @NotNull List<SpecializationItem> specializations
) {
    public record SpecializationItem(
        @NotNull Integer topicId,
        int displayOrder
    ) {}
}

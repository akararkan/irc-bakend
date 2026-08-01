package ak.dev.irc.app.settings.data.dto;

import ak.dev.irc.app.settings.data.entity.AccountDeletionRequest;
import ak.dev.irc.app.settings.data.entity.ExportJob;

import java.time.LocalDateTime;
import java.util.UUID;

/** DTOs for the data & privacy surface (spec §16). */
public final class DataDtos {

    private DataDtos() {}

    public record ExportJobResponse(UUID jobId, String status, LocalDateTime createdAt,
                                    LocalDateTime expiresAt, Long sizeBytes) {
        public static ExportJobResponse of(ExportJob j) {
            return new ExportJobResponse(j.getId(), j.getStatus().name(),
                    j.getCreatedAt(), j.getExpiresAt(), j.getSizeBytes());
        }
    }

    public record DeletionStatusResponse(String status, LocalDateTime requestedAt,
                                         LocalDateTime purgeAfter) {
        public static DeletionStatusResponse of(AccountDeletionRequest r) {
            return new DeletionStatusResponse(r.getStatus().name(),
                    r.getRequestedAt(), r.getPurgeAfter());
        }
    }
}

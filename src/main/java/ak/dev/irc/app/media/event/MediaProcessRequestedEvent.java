package ak.dev.irc.app.media.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * "An asset needs its renditions produced" — consumed by {@code
 * MediaProcessWorker}. Carries only the asset id: the bytes stay in object
 * storage and every other fact lives on the {@code media_assets} row.
 *
 * <p>Published after commit (see {@link MediaEventPublisher}) so a message is
 * never emitted for an asset row whose transaction rolled back.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaProcessRequestedEvent implements Serializable {

    private UUID assetId;

    /** Why: {@code complete} | {@code ingest} | {@code admin-reprocess} | {@code sweeper-retry}. */
    private String reason;

    @Builder.Default
    private LocalDateTime occurredAt = LocalDateTime.now();
}

package ak.dev.irc.app.media.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * "Delete an asset's objects and rows" (or one legacy pre-pipeline key) —
 * consumed by {@code MediaDeleteWorker}. Exactly one of {@code assetId} /
 * {@code legacyKey} is set.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaDeleteRequestedEvent implements Serializable {

    /** Pipeline asset to delete (renditions + raw + rows). */
    private UUID assetId;

    /** Pre-pipeline object key to delete verbatim (no rows exist for it). */
    private String legacyKey;

    @Builder.Default
    private LocalDateTime occurredAt = LocalDateTime.now();
}

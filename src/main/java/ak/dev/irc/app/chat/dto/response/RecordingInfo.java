package ak.dev.irc.app.chat.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The owner-facing recording manifest for a stream: overall status plus the
 * individual on-disk parts (MediaMTX rolls a new fMP4 file per segment window).
 * Built by scanning the stream's recordings directory — O(parts).
 */
public record RecordingInfo(
        UUID streamId,
        String status,            // RecordingStatus name
        boolean available,        // status == AVAILABLE (at least one downloadable part)
        int partCount,
        long totalBytes,
        /** The part the whole-recording download (no {@code ?part=}) returns — the
         *  largest part, which is the one carrying video when a broadcast's audio
         *  prelude split into its own tiny part. Null when there are no parts. */
        String primaryFile,
        List<RecordingPart> parts
) {
    /** One recorded file. {@code downloadUrl} streams THIS part; when there is a
     *  single part it is also the whole-recording download. */
    public record RecordingPart(
            String file,          // bare filename (no path) — pass back as ?part=
            long sizeBytes,
            Instant modifiedAt,
            String downloadUrl
    ) {}
}

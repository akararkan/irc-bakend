package ak.dev.irc.app.chat.dto.response;

/** Outbound attachment reference. {@code url}/{@code thumbnailUrl} resolve through
 *  the media proxy so the client renders without extra round-trips. */
public record MediaRefResponse(
        String kind,
        String storageKey,
        String url,
        String thumbnailKey,
        String thumbnailUrl,
        String mime,
        Long bytes,
        Integer width,
        Integer height,
        Integer durationMs,
        String waveform,
        String fileName,
        String altText
) {}

package ak.dev.irc.app.post.sound.dto;

import ak.dev.irc.app.post.enums.SoundCategory;
import ak.dev.irc.app.post.enums.SoundStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record SoundResponse(
    UUID          id,
    String        title,
    String        artistName,
    String        description,
    SoundCategory category,
    SoundStatus   status,
    String        audioUrl,
    String        coverArtUrl,
    int           durationSeconds,
    int           defaultClipStart,
    String        mimeType,
    Long          fileSizeBytes,
    String        waveformData,
    String        sourceUrl,
    String        license,
    UUID          uploaderId,
    String        uploaderUsername,
    long          useCount,
    LocalDateTime createdAt
) {}

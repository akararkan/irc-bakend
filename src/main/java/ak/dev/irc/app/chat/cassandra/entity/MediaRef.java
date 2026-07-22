package ak.dev.irc.app.chat.cassandra.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.UserDefinedType;

/**
 * Cassandra UDT for a single chat attachment — image, video, voice note or file.
 *
 * <p>This is the first {@code @UserDefinedType} in the project. A message stores
 * a {@code list<frozen<media_ref>>} so a single bubble can carry an album. Media
 * bytes themselves live in R2/S3 and are uploaded through the <b>existing</b>
 * media pipeline; chat only references the object by {@link #storageKey}
 * (served via {@code GET /api/v1/media/{storageKey}} with Range support for
 * audio/video seeking) and keeps {@link #url} as a rendering convenience.</p>
 *
 * <p>The UDT is created before its owning tables by
 * {@code ChatCassandraSchemaInitializer} so the freeze ordering is guaranteed
 * regardless of Spring Data's schema-action.</p>
 */
@UserDefinedType("media_ref")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MediaRef {

    /** IMAGE | VIDEO | VOICE | FILE (stored as text, matching the flat-string
     *  media convention used elsewhere in the codebase). */
    @Column("kind")
    private String kind;

    /** R2/S3 object key — the source of truth. Served via the media proxy. */
    @Column("storage_key")
    private String storageKey;

    /** Proxy URL convenience ({@code /api/v1/media/{storageKey}}); derivable from
     *  the key but denormalised so the client renders without a round-trip. */
    @Column("url")
    private String url;

    /** Optional poster/thumbnail object key (video/image). */
    @Column("thumbnail_key")
    private String thumbnailKey;

    @Column("thumbnail_url")
    private String thumbnailUrl;

    @Column("mime")
    private String mime;

    /** Size in bytes. */
    @Column("bytes")
    private Long bytes;

    @Column("width")
    private Integer width;

    @Column("height")
    private Integer height;

    /** Duration in <b>milliseconds</b> (voice notes / video). Chat standardises on
     *  ms for sub-second waveform alignment, a deliberate departure from the
     *  seconds used elsewhere. */
    @Column("duration_ms")
    private Integer durationMs;

    /** Base64 peak array for rendering a voice-note waveform. */
    @Column("waveform")
    private String waveform;

    /** Original file name (files) and alt text (images) for accessibility. */
    @Column("file_name")
    private String fileName;

    @Column("alt_text")
    private String altText;
}

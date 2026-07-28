package ak.dev.irc.app.chat.enums;

/**
 * Lifecycle of a live stream's on-disk recording (MediaMTX writes fMP4 parts to
 * the bind-mounted recordings dir; the app serves them back to the owner).
 *
 * <ul>
 *   <li>{@code DISABLED}  — the host opted out; no recording is kept.</li>
 *   <li>{@code RECORDING} — the stream is LIVE and being written to disk.</li>
 *   <li>{@code PAUSED}    — the host stopped recording mid-broadcast. The stream is
 *       still LIVE; the parts written so far are kept and recording can be
 *       resumed, which opens a new part. Everything is joined at end.</li>
 *   <li>{@code PROCESSING} — the stream ended and its takes are being joined into
 *       one file. The individual parts stay downloadable while this runs.</li>
 *   <li>{@code AVAILABLE} — the stream ended and at least one recording part exists.</li>
 *   <li>{@code EMPTY}     — recording was on, but nothing was written (nobody published).</li>
 *   <li>{@code DELETED}   — the recording existed and was removed by the owner.</li>
 * </ul>
 */
public enum RecordingStatus { DISABLED, RECORDING, PAUSED, PROCESSING, AVAILABLE, EMPTY, DELETED }

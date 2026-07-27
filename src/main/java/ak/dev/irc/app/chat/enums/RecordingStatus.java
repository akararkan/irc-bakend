package ak.dev.irc.app.chat.enums;

/**
 * Lifecycle of a live stream's on-disk recording (MediaMTX writes fMP4 parts to
 * the bind-mounted recordings dir; the app serves them back to the owner).
 *
 * <ul>
 *   <li>{@code DISABLED}  — the host opted out; no recording is kept.</li>
 *   <li>{@code RECORDING} — the stream is LIVE and being written to disk.</li>
 *   <li>{@code AVAILABLE} — the stream ended and at least one recording part exists.</li>
 *   <li>{@code EMPTY}     — recording was on, but nothing was written (nobody published).</li>
 *   <li>{@code DELETED}   — the recording existed and was removed by the owner.</li>
 * </ul>
 */
public enum RecordingStatus { DISABLED, RECORDING, AVAILABLE, EMPTY, DELETED }

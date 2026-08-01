package ak.dev.irc.app.media.enums;

/**
 * Media processing lifecycle (spec §20.7):
 * <pre>PENDING → UPLOADING → PROCESSING → READY
 *                              ├→ FAILED_VALIDATION  (bad codec, corrupt, oversize)
 *                              ├→ FAILED_MODERATION  (scan rejected)
 *                              └→ FAILED_PROCESSING  (retryable)</pre>
 */
public enum MediaStatus {
    PENDING,
    UPLOADING,
    PROCESSING,
    READY,
    FAILED_VALIDATION,
    FAILED_MODERATION,
    FAILED_PROCESSING;

    public boolean isTerminalFailure() {
        return this == FAILED_VALIDATION || this == FAILED_MODERATION || this == FAILED_PROCESSING;
    }
}

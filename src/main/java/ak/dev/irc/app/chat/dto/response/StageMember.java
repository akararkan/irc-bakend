package ak.dev.irc.app.chat.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * One participant on a live stream's <b>stage</b> — the host or a guest who is up
 * and talking. Carries the identity a tile renders from (so no extra per-user
 * fetch) plus how to watch them and whether they are muted.
 *
 * <p><b>Credential visibility.</b> {@code whepUrl} is the public subscribe URL —
 * present for everyone so every client can pull this participant's video.
 * {@code whipUrl} and {@code publishKey} are the secret <b>publish</b> credential;
 * they are populated <b>only</b> in the private frame delivered to that participant
 * themselves ({@code stream.stage.grant} / the accept response) and are always null
 * in the roster broadcast everyone else sees.</p>
 */
public record StageMember(
        UUID streamId,
        UUID userId,
        String username,
        String displayName,
        String avatarUrl,
        /** {@code HOST} or {@code GUEST}. */
        String role,
        /** Guest lifecycle name ({@code StreamGuestStatus}); {@code ACTIVE} for the host. */
        String status,
        boolean muted,
        /** Public WebRTC/WHEP subscribe URL for this participant's camera. */
        String whepUrl,
        /** Secret WebRTC/WHIP publish URL — this participant only; null for others. */
        String whipUrl,
        /** Secret publish key — this participant only; null for others. */
        String publishKey,
        Instant joinedAt
) {}

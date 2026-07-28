package ak.dev.irc.app.chat.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * A gift landing on a live stream, broadcast to everyone watching so they can play
 * the animation and update the "top supporters" tally. Ephemeral on the wire — the
 * running total is what persists (see {@code StreamGiftTally}).
 */
public record StreamGiftEvent(
        UUID streamId,
        UUID senderId,
        String senderUsername,
        String senderAvatarUrl,
        /** {@code StreamGift} name, e.g. {@code ROSE}. */
        String giftId,
        String giftName,
        /** Stable icon name the client maps to an emoji / sprite / Lottie. */
        String iconKey,
        /** Symbolic weight of this single gift (a score, not money). */
        int coins,
        /** This sender's running coin total in THIS stream after the gift. */
        long senderTotalCoins,
        Instant sentAt
) {}

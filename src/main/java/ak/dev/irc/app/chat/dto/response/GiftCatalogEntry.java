package ak.dev.irc.app.chat.dto.response;

/**
 * One entry in the live-stream gift catalogue — what the client renders in the
 * gift picker. Comes straight off {@link ak.dev.irc.app.chat.enums.StreamGift}.
 */
public record GiftCatalogEntry(
        /** Enum name — pass this back as {@code giftId} when sending. */
        String id,
        String name,
        String iconKey,
        int coins
) {}

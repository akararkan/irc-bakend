package ak.dev.irc.app.chat.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Send a symbolic gift into a live stream. {@code giftId} is a {@code StreamGift}
 *  enum name (from {@code GET /streams/gifts/catalog}). */
@Data
public class SendGiftRequest {
    @NotNull
    private String giftId;
}

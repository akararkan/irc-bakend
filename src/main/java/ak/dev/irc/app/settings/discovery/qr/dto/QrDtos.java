package ak.dev.irc.app.settings.discovery.qr.dto;

import ak.dev.irc.app.settings.discovery.qr.entity.QrToken;

import java.time.LocalDateTime;
import java.util.UUID;

/** DTOs for QR discovery (spec §6). */
public final class QrDtos {

    private QrDtos() {}

    public record QrTokenResponse(String opaqueToken, String uri, LocalDateTime rotatedAt) {
        public static QrTokenResponse of(QrToken t) {
            return new QrTokenResponse(t.getOpaqueToken(),
                    "irc://u/" + t.getOpaqueToken(), t.getRotatedAt());
        }
    }

    public record QrResolveResponse(UUID userId, String username, String fullName, String avatarUrl) {}
}

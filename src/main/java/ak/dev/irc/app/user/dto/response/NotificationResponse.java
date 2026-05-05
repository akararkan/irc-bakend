package ak.dev.irc.app.user.dto.response;


import ak.dev.irc.app.user.enums.NotificationCategory;
import ak.dev.irc.app.user.enums.NotificationType;

import java.time.LocalDateTime;
import java.util.UUID;

public record NotificationResponse(
        UUID                 id,
        NotificationType     type,
        /** Coarse inbox tab — derived from {@link #type} for client-side grouping. */
        NotificationCategory category,
        String               title,
        String               body,

        // ── Primary actor (most recent for aggregated rows) ───────────
        UUID                 actorId,
        String               actorUsername,
        String               actorFullName,
        String               actorProfileImage,

        // ── Aggregation ───────────────────────────────────────────────
        /** Number of underlying events this row represents — 1 for non-aggregated. */
        long                 aggregateCount,
        /** Last actor in the aggregated stream (may equal {@link #actorId}). */
        UUID                 lastActorId,
        String               lastActorUsername,

        // ── Resource pointers + deep link ────────────────────────────
        UUID                 resourceId,
        String               resourceType,
        /** Pre-built client URL (e.g. {@code /posts/abc}). Null for opaque resources. */
        String               deepLink,

        // ── State ─────────────────────────────────────────────────────
        boolean              isRead,
        LocalDateTime        readAt,
        LocalDateTime        createdAt
) {}

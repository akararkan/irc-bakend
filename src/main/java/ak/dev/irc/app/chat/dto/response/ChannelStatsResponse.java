package ak.dev.irc.app.chat.dto.response;

import java.util.List;
import java.util.Map;

/** Owner/admin analytics for a channel. */
public record ChannelStatsResponse(
        long subscriberCount,
        /** Subscribers currently online (presence-derived). */
        long onlineSubscribers,
        long joinedLast7Days,
        long joinedLast30Days,
        long leftLast30Days,
        /** Subscribers currently muting the channel. */
        long mutedCount,
        /** Active subscribers with notifications on (subscribers − muted). */
        long notificationsEnabledCount,
        long postCount,
        /** Live post count per message type (TEXT/IMAGE/VIDEO/…), best-effort. */
        Map<String, Long> postsByType,
        long totalViews,
        long totalForwards,
        /** ISO date → number of joins, last 30 days. */
        Map<String, Long> joinsByDay,
        /** Join source (OWNER/DISCOVERY/INVITE_LINK/JOIN_REQUEST/ADDED_BY_ADMIN/
         *  COMMENT/UNKNOWN) → active subscribers who arrived that way. */
        Map<String, Long> joinsBySource,
        /** The channel's most-viewed posts, most viewed first. */
        List<MessageResponse> topPosts
) {}

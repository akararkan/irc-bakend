package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.dto.response.ChannelStatsResponse;
import ak.dev.irc.app.chat.dto.response.MessageResponse;
import ak.dev.irc.app.chat.entity.Conversation;
import ak.dev.irc.app.chat.entity.ConversationMember;
import ak.dev.irc.app.chat.repository.ConversationMemberRepository;
import ak.dev.irc.app.chat.repository.ConversationRepository;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.messages.ChannelStreamMessages;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owner/admin channel analytics. Durable inputs (member rows, the post counter,
 * per-post view counters) are combined with the best-effort Redis aggregates
 * (per-type post counts, running view/forward totals, most-viewed ZSET)
 * maintained by {@link ChannelPostMetricsService}.
 */
@Service
@RequiredArgsConstructor
public class ChannelStatsService {

    private static final int TOP_POSTS = 10;

    private final ConversationRepository conversationRepo;
    private final ConversationMemberRepository memberRepo;
    private final ChannelPostMetricsService metrics;
    private final MessageQueryService messageQueryService;
    private final PresenceService presence;

    @Transactional(readOnly = true)
    public ChannelStatsResponse stats(UUID channelId, UUID actorId) {
        Conversation c = conversationRepo.findById(channelId)
                .filter(x -> x.getDeletedAt() == null && x.isChannel())
                .orElseThrow(() -> new ResourceNotFoundException("Channel", "id", channelId));
        ConversationMember me = memberRepo.findMember(channelId, actorId)
                .filter(m -> m.isActive() && m.isAdminOrOwner())
                .orElseThrow(() -> new ForbiddenException(
                        ChannelStreamMessages.CHANNEL_STATS_ADMINS_ONLY_MSG, ChannelStreamMessages.ADMINS_ONLY));

        LocalDateTime now = LocalDateTime.now();
        long joined7 = memberRepo.countJoinedSince(channelId, now.minusDays(7));
        long joined30 = memberRepo.countJoinedSince(channelId, now.minusDays(30));
        long left30 = memberRepo.countLeftSince(channelId, now.minusDays(30));
        long muted = memberRepo.countMuted(channelId);

        Map<String, Long> joinsByDay = new LinkedHashMap<>();
        for (Object[] row : memberRepo.joinsByDay(channelId, now.minusDays(30))) {
            joinsByDay.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        Map<String, Long> joinsBySource = new LinkedHashMap<>();
        for (Object[] row : memberRepo.joinsBySource(channelId)) {
            joinsBySource.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        long online = presence.onlineAmong(memberRepo.findActiveMemberIds(channelId)).size();

        Map<String, Long> totals = metrics.totals(channelId);
        List<Long> topIds = metrics.topPostIds(channelId, TOP_POSTS);
        List<MessageResponse> topPosts = topIds.isEmpty() ? List.of()
                : messageQueryService.messagesByIds(topIds, me.getId().getUserId());

        return new ChannelStatsResponse(
                c.getMemberCount(),
                online,
                joined7,
                joined30,
                left30,
                muted,
                Math.max(0, c.getMemberCount() - muted),
                c.getPostCount(),
                metrics.postsByType(channelId),
                totals.getOrDefault("views", 0L),
                totals.getOrDefault("forwards", 0L),
                joinsByDay,
                joinsBySource,
                topPosts);
    }

    /**
     * Platform-admin override (chat-channels-live.md §6): same aggregates
     * without the channel-member gate — a non-member platform ADMIN got a 403
     * from {@link #stats}. Two deliberate differences: taken-down (soft-
     * deleted) channels remain inspectable, and {@code topPosts} stays empty —
     * post CONTENT is not projected into the admin view, only counts.
     */
    @Transactional(readOnly = true)
    public ChannelStatsResponse statsAsAdmin(UUID channelId) {
        Conversation c = conversationRepo.findById(channelId)
                .filter(Conversation::isChannel)
                .orElseThrow(() -> new ResourceNotFoundException("Channel", "id", channelId));

        LocalDateTime now = LocalDateTime.now();
        long joined7 = memberRepo.countJoinedSince(channelId, now.minusDays(7));
        long joined30 = memberRepo.countJoinedSince(channelId, now.minusDays(30));
        long left30 = memberRepo.countLeftSince(channelId, now.minusDays(30));
        long muted = memberRepo.countMuted(channelId);

        Map<String, Long> joinsByDay = new LinkedHashMap<>();
        for (Object[] row : memberRepo.joinsByDay(channelId, now.minusDays(30))) {
            joinsByDay.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        Map<String, Long> joinsBySource = new LinkedHashMap<>();
        for (Object[] row : memberRepo.joinsBySource(channelId)) {
            joinsBySource.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        long online = presence.onlineAmong(memberRepo.findActiveMemberIds(channelId)).size();
        Map<String, Long> totals = metrics.totals(channelId);

        return new ChannelStatsResponse(
                c.getMemberCount(),
                online,
                joined7,
                joined30,
                left30,
                muted,
                Math.max(0, c.getMemberCount() - muted),
                c.getPostCount(),
                metrics.postsByType(channelId),
                totals.getOrDefault("views", 0L),
                totals.getOrDefault("forwards", 0L),
                joinsByDay,
                joinsBySource,
                List.of());
    }
}

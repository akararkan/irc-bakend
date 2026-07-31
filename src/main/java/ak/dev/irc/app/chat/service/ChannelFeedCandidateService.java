package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.cassandra.entity.MessageByConversationEntity;
import ak.dev.irc.app.chat.cassandra.repository.MessageByConversationRepository;
import ak.dev.irc.app.chat.entity.Conversation;
import ak.dev.irc.app.chat.repository.ConversationMemberRepository;
import ak.dev.irc.app.chat.util.ChatBuckets;
import ak.dev.irc.app.chat.util.SnowflakeIdGenerator;
import ak.dev.irc.app.post.dto.ChannelSummary;
import ak.dev.irc.app.research.service.S3StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Channel candidates for the ranked home feed — the "posts from channels
 * you subscribe to" digest, Telegram-content-in-an-Instagram-feed style.
 *
 * <p>Read-time candidate generation (no fanout writes): the home feed's
 * first page asks for the freshest posts across the viewer's most recently
 * active subscribed channels. Bounded cost per request:</p>
 * <ol>
 *   <li>one Postgres query for subscribed channels (already indexed),
 *       pruned to those with a message inside the lookback window and
 *       capped at {@link #MAX_CHANNELS} by last-activity;</li>
 *   <li>one single-partition Cassandra slice per surviving channel
 *       (parallel on the bounded {@code taskExecutor}), stepping back at
 *       most one extra {@link ChatBuckets} bucket when the window spans a
 *       bucket boundary;</li>
 *   <li>one bulk {@code message_counters} read for all candidate posts.</li>
 * </ol>
 *
 * <p>Poll/system/deleted rows are skipped. The ranker downstream scores
 * candidates with the same engagement + freshness math as posts, using
 * views/forwards/comments from {@code ChannelPostMetricsService}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelFeedCandidateService {

    private static final int  MAX_CHANNELS      = 15;
    private static final int  PER_CHANNEL_POSTS = 5;
    private static final long LOOKBACK_HOURS    = 48;
    private static final int  PREVIEW_MAX       = 280;

    private final ConversationMemberRepository    memberRepo;
    private final MessageByConversationRepository messageRepo;
    private final ChannelPostMetricsService       metricsService;
    private final S3StorageService                storageService;
    private final ThreadPoolTaskExecutor          taskExecutor;

    /** One channel post, pre-joined with its channel identity + counters. */
    public record ChannelPostCandidate(
            ChannelSummary channel,
            long    messageId,
            String  preview,
            String  mediaUrl,
            Instant createdAt,
            long    views,
            long    forwards,
            long    comments
    ) {}

    /**
     * Fresh posts (last {@value #LOOKBACK_HOURS}h) from the viewer's
     * subscribed channels. Fail-open: any per-channel error just drops that
     * channel from the digest.
     */
    @Transactional(readOnly = true)
    public List<ChannelPostCandidate> recentChannelPosts(UUID viewerId) {
        if (viewerId == null) return List.of();

        Instant cutoff = Instant.now().minus(LOOKBACK_HOURS, ChronoUnit.HOURS);
        List<Conversation> channels;
        try {
            channels = memberRepo.findMySubscribedChannels(viewerId);
        } catch (Exception e) {
            log.debug("[CH-FEED] subscribed-channel lookup failed for {}: {}", viewerId, e.getMessage());
            return List.of();
        }
        if (channels.isEmpty()) return List.of();

        // Prune to channels that actually posted inside the window, most
        // recently active first — dead channels cost zero Cassandra reads.
        List<Conversation> active = channels.stream()
                .filter(c -> c.getLastMessageAt() != null
                        && c.getLastMessageAt().toInstant(ZoneOffset.UTC).isAfter(cutoff))
                .sorted(Comparator.comparing(Conversation::getLastMessageAt).reversed())
                .limit(MAX_CHANNELS)
                .toList();
        if (active.isEmpty()) return List.of();

        List<CompletableFuture<List<MessageByConversationEntity>>> futures = active.stream()
                .map(c -> CompletableFuture.supplyAsync(
                        () -> fetchRecentPosts(c.getId(), cutoff), taskExecutor))
                .toList();

        Map<UUID, List<MessageByConversationEntity>> postsByChannel = new HashMap<>();
        List<Long> allMessageIds = new ArrayList<>();
        for (int i = 0; i < active.size(); i++) {
            List<MessageByConversationEntity> msgs;
            try {
                msgs = futures.get(i).join();
            } catch (Exception e) {
                log.debug("[CH-FEED] channel fan-in failed: {}", e.getMessage());
                continue;
            }
            if (msgs.isEmpty()) continue;
            postsByChannel.put(active.get(i).getId(), msgs);
            msgs.forEach(m -> allMessageIds.add(m.getMessageId()));
        }
        if (allMessageIds.isEmpty()) return List.of();

        Map<Long, ChannelPostMetricsService.PostMetrics> metrics;
        try {
            metrics = metricsService.metricsFor(allMessageIds);
        } catch (Exception e) {
            log.debug("[CH-FEED] metrics bulk-load failed: {}", e.getMessage());
            metrics = Map.of();
        }

        List<ChannelPostCandidate> out = new ArrayList<>(allMessageIds.size());
        for (Conversation channel : active) {
            List<MessageByConversationEntity> msgs = postsByChannel.get(channel.getId());
            if (msgs == null) continue;
            ChannelSummary summary = toSummary(channel);
            for (MessageByConversationEntity m : msgs) {
                ChannelPostMetricsService.PostMetrics pm = metrics.get(m.getMessageId());
                out.add(new ChannelPostCandidate(
                        summary,
                        m.getMessageId(),
                        preview(m.getBody()),
                        coverMedia(m),
                        createdAtOf(m),
                        pm == null ? 0L : pm.views(),
                        pm == null ? 0L : pm.forwards(),
                        pm == null ? 0L : pm.comments()));
            }
        }
        return out;
    }

    /**
     * Newest posts of one channel within the window. The current bucket is
     * read first; if it yields fewer rows than the per-channel cap AND the
     * window's floor lives in the previous bucket, that one is read too —
     * a 48h window can span at most one 10-day bucket boundary.
     */
    private List<MessageByConversationEntity> fetchRecentPosts(UUID channelId, Instant cutoff) {
        List<MessageByConversationEntity> out = new ArrayList<>(PER_CHANNEL_POSTS);
        int currentBucket = ChatBuckets.currentBucket();
        int floorBucket   = ChatBuckets.bucketForTimestamp(cutoff.toEpochMilli());
        try {
            collectEligible(messageRepo.firstPage(channelId, currentBucket, PER_CHANNEL_POSTS * 2),
                            cutoff, out);
            if (out.size() < PER_CHANNEL_POSTS && floorBucket < currentBucket) {
                collectEligible(messageRepo.firstPage(channelId, currentBucket - 1, PER_CHANNEL_POSTS * 2),
                                cutoff, out);
            }
        } catch (Exception e) {
            log.debug("[CH-FEED] message slice failed for channel {}: {}", channelId, e.getMessage());
        }
        return out.size() > PER_CHANNEL_POSTS ? out.subList(0, PER_CHANNEL_POSTS) : out;
    }

    private static void collectEligible(List<MessageByConversationEntity> rows,
                                        Instant cutoff,
                                        List<MessageByConversationEntity> out) {
        for (MessageByConversationEntity m : rows) {
            if (out.size() >= PER_CHANNEL_POSTS) return;
            if (Boolean.TRUE.equals(m.getDeleted())) continue;
            if (m.getSystemEvent() != null) continue;
            Instant created = createdAtOf(m);
            if (created == null || created.isBefore(cutoff)) continue;
            out.add(m);
        }
    }

    private ChannelSummary toSummary(Conversation c) {
        return new ChannelSummary(
                c.getId(),
                c.getHandle(),
                c.getTitle(),
                publicUrl(c.getAvatarKey()),
                c.isVerified(),
                c.getMemberCount());
    }

    private String publicUrl(String key) {
        if (key == null || key.isBlank()) return null;
        try { return storageService.getPublicUrl(key); }
        catch (Exception e) { return null; }
    }

    private static String preview(String body) {
        if (body == null) return null;
        return body.length() > PREVIEW_MAX ? body.substring(0, PREVIEW_MAX) : body;
    }

    /** First renderable media URL — thumbnail preferred for the feed card. */
    private static String coverMedia(MessageByConversationEntity m) {
        if (m.getMedia() == null || m.getMedia().isEmpty()) return null;
        var first = m.getMedia().get(0);
        if (first.getThumbnailUrl() != null) return first.getThumbnailUrl();
        return first.getUrl();
    }

    private static Instant createdAtOf(MessageByConversationEntity m) {
        if (m.getCreatedAt() != null) return m.getCreatedAt();
        if (m.getMessageId() == null) return null;
        return Instant.ofEpochMilli(SnowflakeIdGenerator.timestampOf(m.getMessageId()));
    }
}

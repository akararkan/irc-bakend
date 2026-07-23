package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.cassandra.entity.MessageByIdEntity;
import ak.dev.irc.app.chat.cassandra.entity.PollVoteByMessageEntity;
import ak.dev.irc.app.chat.cassandra.repository.MessageByConversationRepository;
import ak.dev.irc.app.chat.cassandra.repository.MessageByIdRepository;
import ak.dev.irc.app.chat.cassandra.repository.PollVoteByMessageRepository;
import ak.dev.irc.app.chat.dto.AdminRights;
import ak.dev.irc.app.chat.dto.request.PollCreateDto;
import ak.dev.irc.app.chat.dto.request.PollVoteRequest;
import ak.dev.irc.app.chat.dto.response.PollResponse;
import ak.dev.irc.app.chat.entity.Conversation;
import ak.dev.irc.app.chat.entity.ConversationMember;
import ak.dev.irc.app.chat.permission.ChannelRights;
import ak.dev.irc.app.chat.realtime.ChatRealtimeBroadcaster;
import ak.dev.irc.app.chat.realtime.ChatRealtimeEvent;
import ak.dev.irc.app.chat.realtime.ChatRealtimeEventType;
import ak.dev.irc.app.chat.repository.ConversationMemberRepository;
import ak.dev.irc.app.chat.repository.ConversationRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;

import java.time.Instant;
import java.util.*;

/**
 * Telegram-style polls/quizzes on messages. The immutable poll definition lives
 * as a JSON blob on the message row; one Cassandra row per (message, voter) is
 * the vote source of truth; a delta-maintained Redis hash
 * {@code chat:poll:{messageId}} (option index → count, {@code ~voters} → total)
 * is the hot read, lazily rebuilt from Cassandra when cold — the exact pattern
 * of {@link ReactionService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PollService {

    private static final String HASH_PREFIX = "chat:poll:";
    private static final String VOTERS_FIELD = "~voters";
    private static final String EMPTY_SENTINEL = "~";
    private static final java.time.Duration EMPTY_TTL = java.time.Duration.ofHours(6);

    private final PollVoteByMessageRepository voteRepo;
    private final MessageByIdRepository messageByIdRepo;
    private final MessageByConversationRepository messageRepo;
    private final ConversationRepository conversationRepo;
    private final ConversationMemberRepository memberRepo;
    private final ChatRealtimeBroadcaster broadcaster;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    // ── Payload ──────────────────────────────────────────────────────────────────

    /** The JSON blob stored in the message's {@code poll} column. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PollPayload(
            String question,
            List<String> options,
            boolean allowsMultipleAnswers,
            boolean anonymous,
            boolean quiz,
            Integer correctOptionIndex,
            String explanation,
            boolean closed,
            Instant closedAt) {}

    /** Validate a poll definition at send time and serialize it for storage. */
    public String validateAndSerialize(PollCreateDto dto) {
        if (dto.isQuiz()) {
            if (dto.isAllowsMultipleAnswers()) {
                throw new BadRequestException("A quiz cannot allow multiple answers.");
            }
            if (dto.getCorrectOptionIndex() == null
                    || dto.getCorrectOptionIndex() < 0
                    || dto.getCorrectOptionIndex() >= dto.getOptions().size()) {
                throw new BadRequestException("A quiz requires a valid correctOptionIndex.");
            }
        } else if (dto.getCorrectOptionIndex() != null) {
            throw new BadRequestException("correctOptionIndex applies only to quizzes.");
        }
        PollPayload payload = new PollPayload(
                dto.getQuestion().trim(), List.copyOf(dto.getOptions()),
                dto.isAllowsMultipleAnswers(), dto.isAnonymous(), dto.isQuiz(),
                dto.getCorrectOptionIndex(), dto.getExplanation(), false, null);
        return serialize(payload);
    }

    // ── Vote / retract / close ───────────────────────────────────────────────────

    public PollResponse vote(long messageId, UUID userId, PollVoteRequest req) {
        MessageByIdEntity m = requirePollMessage(messageId);
        requireActiveMember(m.getConversationId(), userId);
        PollPayload p = parse(m.getPoll());
        if (p.closed()) throw new BadRequestException("This poll is closed.");

        Set<Integer> picks = new LinkedHashSet<>(req.getOptionIndexes());
        for (Integer i : picks) {
            if (i == null || i < 0 || i >= p.options().size()) {
                throw new BadRequestException("Option index out of range.");
            }
        }
        if (picks.size() > 1 && !p.allowsMultipleAnswers()) {
            throw new BadRequestException("This poll allows only one answer.");
        }

        PollVoteByMessageEntity prior = voteRepo.findOne(messageId, userId);
        if (prior != null && p.quiz()) {
            throw new BadRequestException("Quiz answers are final.");
        }
        if (prior != null && picks.equals(prior.getOptions())) {
            return renderFor(m, userId); // idempotent
        }
        voteRepo.save(PollVoteByMessageEntity.builder()
                .messageId(messageId).userId(userId)
                .options(picks).createdAt(Instant.now())
                .build());
        if (prior != null) {
            adjust(messageId, prior.getOptions(), -1, false);
            adjust(messageId, picks, +1, false);
        } else {
            adjust(messageId, picks, +1, true);
        }
        broadcastPoll(m);
        return renderFor(m, userId);
    }

    public PollResponse retract(long messageId, UUID userId) {
        MessageByIdEntity m = requirePollMessage(messageId);
        requireActiveMember(m.getConversationId(), userId);
        PollPayload p = parse(m.getPoll());
        if (p.closed()) throw new BadRequestException("This poll is closed.");
        if (p.quiz()) throw new BadRequestException("Quiz answers are final.");
        PollVoteByMessageEntity prior = voteRepo.findOne(messageId, userId);
        if (prior != null) {
            voteRepo.deleteOne(messageId, userId);
            adjust(messageId, prior.getOptions(), -1, false);
            decVoters(messageId);
            broadcastPoll(m);
        }
        return renderFor(m, userId);
    }

    /** Close voting — the poll author, or a channel admin who can edit posts. */
    public PollResponse close(long messageId, UUID userId) {
        MessageByIdEntity m = requirePollMessage(messageId);
        ConversationMember me = requireActiveMember(m.getConversationId(), userId);
        boolean own = userId.equals(m.getSenderId());
        if (!own) {
            Conversation convo = conversationRepo.findById(m.getConversationId()).orElse(null);
            boolean adminClose = convo != null && convo.isChannel()
                    && ChannelRights.can(me, AdminRights::isCanEditMessages);
            if (!adminClose) {
                throw new ForbiddenException("You cannot close this poll.", "ACCESS_FORBIDDEN");
            }
        }
        PollPayload p = parse(m.getPoll());
        if (!p.closed()) {
            PollPayload closed = new PollPayload(p.question(), p.options(),
                    p.allowsMultipleAnswers(), p.anonymous(), p.quiz(),
                    p.correctOptionIndex(), p.explanation(), true, Instant.now());
            String json = serialize(closed);
            messageByIdRepo.updatePoll(messageId, json);
            messageRepo.updatePoll(m.getConversationId(), m.getBucket(), messageId, json);
            m.setPoll(json);
            broadcastPoll(m);
        }
        return renderFor(m, userId);
    }

    /** Drop a deleted poll message's votes + count hash. */
    public void clear(long messageId) {
        try {
            voteRepo.deleteAllForMessage(messageId);
            redis.delete(HASH_PREFIX + messageId);
        } catch (Exception e) {
            log.debug("[POLL] clear failed for {}: {}", messageId, e.getMessage());
        }
    }

    // ── Rendering / hydration ────────────────────────────────────────────────────

    /** Render one poll for a viewer (their own votes resolved exactly). */
    public PollResponse renderFor(MessageByIdEntity m, UUID viewerId) {
        if (!StringUtils.hasText(m.getPoll())) return null;
        PollPayload p = parse(m.getPoll());
        Map<Integer, Long> counts = countsOf(m.getMessageId());
        PollVoteByMessageEntity mine = viewerId == null ? null : voteRepo.findOne(m.getMessageId(), viewerId);
        List<Integer> myVotes = mine == null || mine.getOptions() == null
                ? List.of() : mine.getOptions().stream().sorted().toList();
        return render(p, counts, myVotes);
    }

    /**
     * Bulk hydration for a page: {@code pollJsonById} maps message id → stored
     * JSON. One Redis pipeline for counts + one Cassandra IN read for the
     * viewer's own votes.
     */
    public Map<Long, PollResponse> pollsFor(Map<Long, String> pollJsonById, UUID viewerId) {
        Map<Long, PollResponse> out = new HashMap<>();
        if (pollJsonById == null || pollJsonById.isEmpty()) return out;
        Map<Long, List<Integer>> mine = new HashMap<>();
        if (viewerId != null) {
            try {
                for (PollVoteByMessageEntity v : voteRepo.findByMessageIdInAndUserId(pollJsonById.keySet(), viewerId)) {
                    mine.put(v.getMessageId(), v.getOptions() == null
                            ? List.of() : v.getOptions().stream().sorted().toList());
                }
            } catch (Exception e) {
                log.debug("[POLL] viewer vote load failed: {}", e.getMessage());
            }
        }
        pollJsonById.forEach((id, json) -> {
            if (!StringUtils.hasText(json)) return;
            try {
                out.put(id, render(parse(json), countsOf(id), mine.getOrDefault(id, List.of())));
            } catch (Exception e) {
                log.debug("[POLL] render failed for {}: {}", id, e.getMessage());
            }
        });
        return out;
    }

    // ── internals ────────────────────────────────────────────────────────────────

    private PollResponse render(PollPayload p, Map<Integer, Long> counts, List<Integer> myVotes) {
        boolean voted = !myVotes.isEmpty();
        // Quiz answer + explanation are revealed only after voting / close.
        boolean reveal = !p.quiz() || voted || p.closed();
        List<PollResponse.PollOption> options = new ArrayList<>(p.options().size());
        for (int i = 0; i < p.options().size(); i++) {
            options.add(new PollResponse.PollOption(i, p.options().get(i), counts.getOrDefault(i, 0L)));
        }
        long voters = counts.getOrDefault(-1, 0L); // -1 carries the voters total
        return new PollResponse(p.question(), options, p.allowsMultipleAnswers(), p.anonymous(),
                p.quiz(), reveal ? p.correctOptionIndex() : null, reveal ? p.explanation() : null,
                p.closed(), voters, myVotes);
    }

    /** Option counts (key −1 = total voters), from Redis or rebuilt from Cassandra. */
    private Map<Integer, Long> countsOf(long messageId) {
        try {
            Map<Object, Object> hash = redis.opsForHash().entries(HASH_PREFIX + messageId);
            if (!hash.isEmpty()) return summarize(hash);
        } catch (Exception ignored) { /* fall through to rebuild */ }
        return rebuild(messageId);
    }

    private Map<Integer, Long> summarize(Map<Object, Object> hash) {
        Map<Integer, Long> out = new HashMap<>();
        hash.forEach((k, v) -> {
            String key = String.valueOf(k);
            if (EMPTY_SENTINEL.equals(key)) return;
            long count = parseLong(v);
            if (VOTERS_FIELD.equals(key)) out.put(-1, Math.max(0, count));
            else {
                try {
                    int idx = Integer.parseInt(key);
                    if (count > 0) out.put(idx, count);
                } catch (NumberFormatException ignored) { /* unknown field */ }
            }
        });
        return out;
    }

    private Map<Integer, Long> rebuild(long messageId) {
        List<PollVoteByMessageEntity> rows;
        try {
            rows = voteRepo.findByMessage(messageId);
        } catch (Exception e) {
            return Map.of();
        }
        Map<Integer, Long> counts = new HashMap<>();
        for (PollVoteByMessageEntity r : rows) {
            if (r.getOptions() == null) continue;
            for (Integer i : r.getOptions()) counts.merge(i, 1L, Long::sum);
        }
        String key = HASH_PREFIX + messageId;
        try {
            if (rows.isEmpty()) {
                redis.opsForHash().put(key, EMPTY_SENTINEL, "0");
                redis.expire(key, EMPTY_TTL);
            } else {
                Map<String, String> m = new HashMap<>();
                counts.forEach((i, c) -> m.put(Integer.toString(i), Long.toString(c)));
                m.put(VOTERS_FIELD, Long.toString(rows.size()));
                redis.opsForHash().putAll(key, m);
                redis.persist(key);
            }
        } catch (Exception ignored) { /* best-effort cache refill */ }
        if (!rows.isEmpty()) counts.put(-1, (long) rows.size());
        return counts;
    }

    private void adjust(long messageId, Set<Integer> options, long delta, boolean newVoter) {
        if (options == null) return;
        try {
            String key = HASH_PREFIX + messageId;
            for (Integer i : options) {
                redis.opsForHash().increment(key, Integer.toString(i), delta);
            }
            if (newVoter) redis.opsForHash().increment(key, VOTERS_FIELD, 1);
            if (delta > 0) redis.persist(key);
        } catch (Exception e) {
            log.debug("[POLL] redis adjust failed: {}", e.getMessage());
        }
    }

    private void decVoters(long messageId) {
        try {
            redis.opsForHash().increment(HASH_PREFIX + messageId, VOTERS_FIELD, -1);
        } catch (Exception ignored) { /* best-effort */ }
    }

    /** Everyone in the conversation sees fresh aggregate results (viewer-neutral). */
    private void broadcastPoll(MessageByIdEntity m) {
        try {
            PollPayload p = parse(m.getPoll());
            PollResponse aggregate = render(p, countsOf(m.getMessageId()), List.of());
            broadcaster.broadcast(memberRepo.findReadableMemberIds(m.getConversationId()),
                    ChatRealtimeEvent.builder()
                            .eventType(ChatRealtimeEventType.POLL_UPDATED)
                            .conversationId(m.getConversationId())
                            .messageId(m.getMessageId())
                            .poll(aggregate)
                            .build());
        } catch (Exception e) {
            log.debug("[POLL] broadcast failed for {}: {}", m.getMessageId(), e.getMessage());
        }
    }

    private MessageByIdEntity requirePollMessage(long messageId) {
        MessageByIdEntity m = messageByIdRepo.findById(messageId)
                .filter(x -> !Boolean.TRUE.equals(x.getDeleted()))
                .orElseThrow(() -> new ResourceNotFoundException("Message", "id", messageId));
        if (!StringUtils.hasText(m.getPoll())) {
            throw new BadRequestException("This message is not a poll.");
        }
        return m;
    }

    private ConversationMember requireActiveMember(UUID conversationId, UUID userId) {
        return memberRepo.findMember(conversationId, userId)
                .filter(ConversationMember::isActive)
                .orElseThrow(() -> new ForbiddenException(
                        "You are not an active member of this conversation.", "NOT_A_MEMBER"));
    }

    private String serialize(PollPayload p) {
        try {
            return objectMapper.writeValueAsString(p);
        } catch (Exception e) {
            throw new BadRequestException("Could not store the poll payload.");
        }
    }

    public PollPayload parse(String json) {
        try {
            return objectMapper.readValue(json, PollPayload.class);
        } catch (Exception e) {
            throw new BadRequestException("Corrupt poll payload.");
        }
    }

    private static long parseLong(Object v) {
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return 0L; }
    }
}

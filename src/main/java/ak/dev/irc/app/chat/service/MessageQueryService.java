package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.cassandra.entity.MessageByConversationEntity;
import ak.dev.irc.app.chat.cassandra.entity.MessageByIdEntity;
import ak.dev.irc.app.chat.cassandra.repository.MessageByConversationRepository;
import ak.dev.irc.app.chat.cassandra.repository.MessageByIdRepository;
import ak.dev.irc.app.chat.dto.response.MessagePage;
import ak.dev.irc.app.chat.dto.response.MessageResponse;
import ak.dev.irc.app.chat.dto.response.ReactionSummary;
import ak.dev.irc.app.chat.dto.response.ReplyPreview;
import ak.dev.irc.app.chat.entity.Conversation;
import ak.dev.irc.app.chat.entity.ConversationMember;
import ak.dev.irc.app.chat.enums.MessageType;
import ak.dev.irc.app.chat.mapper.ChatMapper;
import ak.dev.irc.app.chat.entity.ConversationPin;
import ak.dev.irc.app.chat.repository.ConversationMemberRepository;
import ak.dev.irc.app.chat.repository.ConversationPinRepository;
import ak.dev.irc.app.chat.repository.ConversationRepository;
import ak.dev.irc.app.chat.search.service.ChatSearchService;
import ak.dev.irc.app.chat.util.ChatBuckets;
import ak.dev.irc.app.chat.util.SnowflakeIdGenerator;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The message read path. Every page is a walk over one-partition-per-query
 * Cassandra slices, bounded by the conversation's known bucket range so it never
 * scans blindly. Hydration (senders, reply previews, reaction counts) is bulked.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageQueryService {

    /** How many buckets in-conversation search scans back before giving up. */
    private static final int SEARCH_MAX_BUCKETS = 24;   // ~240 days at BUCKET_DAYS=10
    private static final int SEARCH_MAX_SCANNED = 3000;

    private final MessageByConversationRepository messageRepo;
    private final MessageByIdRepository messageByIdRepo;
    private final ConversationRepository conversationRepo;
    private final ConversationMemberRepository memberRepo;
    private final ConversationPinRepository pinRepo;
    private final ReactionService reactionService;
    private final ChatSearchService chatSearch;
    private final ChatMapper mapper;
    private final UserRepository userRepository;

    // ── Page (newest → older, bucket walk) ─────────────────────────────────────

    @Transactional(readOnly = true)
    public MessagePage<MessageResponse> loadPage(UUID conversationId, UUID userId, Long cursor, int limit) {
        Conversation convo = requireConversation(conversationId);
        ConversationMember me = requireReadableMember(conversationId, userId);

        long minBucketTsFloor = historyFloorMillis(convo, me);
        int minBucket = ChatBuckets.bucketForTimestamp(minBucketTsFloor);
        Long floorId = floorMessageId(convo, me);

        int startBucket = (cursor != null)
                ? ChatBuckets.bucketOf(cursor)
                : (convo.getLastMessageId() != null
                    ? ChatBuckets.bucketOf(convo.getLastMessageId())
                    : ChatBuckets.currentBucket());

        List<MessageByConversationEntity> out = new ArrayList<>(limit);
        int bucket = startBucket;
        Long cur = cursor;
        while (out.size() < limit && bucket >= minBucket) {
            int need = limit - out.size();
            List<MessageByConversationEntity> rows = (cur != null)
                    ? messageRepo.pageBefore(conversationId, bucket, cur, need)
                    : messageRepo.firstPage(conversationId, bucket, need);
            for (MessageByConversationEntity r : rows) {
                if (floorId != null && r.getMessageId() < floorId) continue; // hidden pre-join history
                out.add(r);
            }
            bucket--;
            cur = null; // subsequent buckets take the whole window
        }

        boolean hasMore = out.size() >= limit;
        Long nextCursor = out.isEmpty() ? null : out.get(out.size() - 1).getMessageId();
        return new MessagePage<>(hydrate(out, userId), nextCursor, hasMore);
    }

    // ── Gap sync (everything strictly newer than a high-water id) ────────────────

    @Transactional(readOnly = true)
    public List<MessageResponse> sync(UUID conversationId, UUID userId, long afterId, int limit) {
        requireConversation(conversationId);
        requireReadableMember(conversationId, userId);

        int fromBucket = ChatBuckets.bucketOf(afterId);
        int toBucket = ChatBuckets.currentBucket();
        List<MessageByConversationEntity> out = new ArrayList<>();
        for (int bucket = fromBucket; bucket <= toBucket && out.size() < limit; bucket++) {
            out.addAll(messageRepo.pageAfter(conversationId, bucket, afterId, limit - out.size()));
        }
        // pageAfter returns DESC within a bucket; present ascending for append.
        out.sort(Comparator.comparingLong(MessageByConversationEntity::getMessageId));
        return hydrate(out, userId);
    }

    // ── Single message ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public MessageResponse getOne(long messageId, UUID userId) {
        MessageByIdEntity m = messageByIdRepo.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message", "id", messageId));
        requireReadableMember(m.getConversationId(), userId);
        Map<UUID, User> users = loadUsers(Set.of(m.getSenderId()));
        ReplyPreview reply = m.getReplyToId() == null ? null
                : mapper.toReplyPreview(messageByIdRepo.findById(m.getReplyToId()).orElse(null));
        return mapper.toMessage(m, users, reactionService.detailFor(messageId, userId), reply);
    }

    @Transactional(readOnly = true)
    public List<ReactionSummary> reactions(long messageId, UUID userId) {
        MessageByIdEntity m = messageByIdRepo.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message", "id", messageId));
        requireReadableMember(m.getConversationId(), userId);
        return reactionService.detailFor(messageId, userId);
    }

    // ── In-conversation search (bounded recent scan) ───────────────────────────────

    /**
     * In-conversation search. Uses the Elasticsearch index (BM25 + fuzzy) when it
     * has results; falls back to a bounded single-partition Cassandra scan when ES
     * is cold/unavailable so search still works before the index is warm.
     */
    @Transactional(readOnly = true)
    public List<MessageResponse> search(UUID conversationId, UUID userId, String q, int limit) {
        Conversation convo = requireConversation(conversationId);
        ConversationMember me = requireReadableMember(conversationId, userId);
        if (!StringUtils.hasText(q)) return List.of();

        try {
            List<Long> ids = chatSearch.searchMessageIds(List.of(conversationId), q, limit);
            if (!ids.isEmpty()) {
                Long floorId = floorMessageId(convo, me);
                return hydrateByIds(ids, userId, floorId);
            }
        } catch (Exception e) {
            log.debug("[CHAT-SEARCH] ES unavailable, falling back to scan: {}", e.getMessage());
        }
        return scanSearch(convo, me, q, limit);
    }

    /**
     * Cross-conversation search over every conversation the caller can read. The
     * membership scope is enforced inside the ES query. ES-only (no all-conversation
     * scan fallback — that would be unbounded); returns empty if ES is unavailable.
     */
    @Transactional(readOnly = true)
    public List<MessageResponse> searchAll(UUID userId, String q, int limit) {
        if (!StringUtils.hasText(q)) return List.of();
        List<UUID> myConversations = memberRepo.findMyConversationIds(userId);
        if (myConversations.isEmpty()) return List.of();

        List<Long> ids;
        try {
            ids = chatSearch.searchMessageIds(myConversations, q, limit);
        } catch (Exception e) {
            log.debug("[CHAT-SEARCH] cross-conversation ES unavailable: {}", e.getMessage());
            return List.of();
        }
        if (ids.isEmpty()) return List.of();

        // Resolve per-conversation visibility: exclude soft-deleted conversations
        // and, for hidden-history groups, any message sent before I joined. Each
        // conversation has its OWN join floor, so a single scalar floor is wrong here.
        Map<UUID, Conversation> live = conversationRepo.findAllById(myConversations).stream()
                .filter(c -> c.getDeletedAt() == null)
                .collect(Collectors.toMap(Conversation::getId, c -> c));
        if (live.isEmpty()) return List.of();
        Map<UUID, Long> floorByConv = new java.util.HashMap<>();
        for (ConversationMember m : memberRepo.findMyMembershipsIn(userId, live.keySet())) {
            UUID cid = m.getId().getConversationId();
            Long fl = floorMessageId(live.get(cid), m);
            if (fl != null) floorByConv.put(cid, fl);
        }

        return hydrateByIds(ids, userId, msg -> {
            Conversation c = live.get(msg.getConversationId());
            if (c == null) return false;                       // soft-deleted / not a live membership
            Long fl = floorByConv.get(msg.getConversationId());
            return fl == null || msg.getMessageId() >= fl;     // honour this conversation's join floor
        });
    }

    /** Pinned messages of a conversation, newest pin first. */
    @Transactional(readOnly = true)
    public List<MessageResponse> pinnedMessages(UUID conversationId, UUID userId) {
        requireConversation(conversationId);
        requireReadableMember(conversationId, userId);
        List<Long> ids = pinRepo.findByConversationIdOrderByPinnedAtDesc(conversationId).stream()
                .map(ConversationPin::getMessageId).toList();
        return hydrateByIds(ids, userId, (Long) null);
    }

    private List<MessageResponse> scanSearch(Conversation convo, ConversationMember me, String q, int limit) {
        String needle = q.toLowerCase(Locale.ROOT);

        int minBucket = ChatBuckets.bucketForTimestamp(historyFloorMillis(convo, me));
        Long floorId = floorMessageId(convo, me);
        int startBucket = convo.getLastMessageId() != null
                ? ChatBuckets.bucketOf(convo.getLastMessageId()) : ChatBuckets.currentBucket();

        List<MessageByConversationEntity> matches = new ArrayList<>();
        int scanned = 0, bucketsWalked = 0;
        for (int bucket = startBucket; bucket >= minBucket
                && bucketsWalked < SEARCH_MAX_BUCKETS
                && scanned < SEARCH_MAX_SCANNED
                && matches.size() < limit; bucket--, bucketsWalked++) {
            List<MessageByConversationEntity> rows = messageRepo.firstPage(convo.getId(), bucket, 500);
            for (MessageByConversationEntity r : rows) {
                scanned++;
                if (floorId != null && r.getMessageId() < floorId) continue;
                if (Boolean.TRUE.equals(r.getDeleted())) continue;
                if (MessageType.SYSTEM.name().equals(r.getType())) continue;
                if (r.getBody() != null && r.getBody().toLowerCase(Locale.ROOT).contains(needle)) {
                    matches.add(r);
                    if (matches.size() >= limit) break;
                }
            }
        }
        return hydrate(matches, me.getId().getUserId());
    }

    /** Single-conversation hydration with one history floor (search / pinned). */
    private List<MessageResponse> hydrateByIds(List<Long> ids, UUID viewerId, Long floorId) {
        return hydrateByIds(ids, viewerId, m -> floorId == null || m.getMessageId() >= floorId);
    }

    /**
     * Hydrate a ranked list of message ids, preserving order and dropping deleted,
     * system, and any message the {@code visible} predicate rejects (used to
     * enforce per-conversation history floors + deleted-conversation exclusion in
     * cross-conversation search).
     */
    private List<MessageResponse> hydrateByIds(List<Long> ids, UUID viewerId,
                                               java.util.function.Predicate<MessageByIdEntity> visible) {
        if (ids == null || ids.isEmpty()) return List.of();
        Map<Long, MessageByIdEntity> byId = messageByIdRepo.findAllByMessageIdIn(ids).stream()
                .collect(Collectors.toMap(MessageByIdEntity::getMessageId, e -> e, (a, b) -> a));

        Set<UUID> senderIds = byId.values().stream().map(MessageByIdEntity::getSenderId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, User> users = loadUsers(senderIds);
        Map<Long, List<ReactionSummary>> reactions = reactionService.countsFor(ids);

        Set<Long> replyIds = byId.values().stream().map(MessageByIdEntity::getReplyToId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, MessageByIdEntity> replies = replyIds.isEmpty() ? Map.of()
                : messageByIdRepo.findAllByMessageIdIn(replyIds).stream()
                    .collect(Collectors.toMap(MessageByIdEntity::getMessageId, e -> e, (a, b) -> a));

        List<MessageResponse> out = new ArrayList<>(ids.size());
        for (Long id : ids) {                       // preserve rank / pin order
            MessageByIdEntity m = byId.get(id);
            if (m == null) continue;
            if (!visible.test(m)) continue;
            if (Boolean.TRUE.equals(m.getDeleted())) continue;
            if (MessageType.SYSTEM.name().equals(m.getType())) continue;
            ReplyPreview reply = m.getReplyToId() == null ? null
                    : mapper.toReplyPreview(replies.get(m.getReplyToId()));
            out.add(mapper.toMessage(m, users, reactions.getOrDefault(id, List.of()), reply));
        }
        return out;
    }

    // ── hydration ──────────────────────────────────────────────────────────────────

    private List<MessageResponse> hydrate(List<MessageByConversationEntity> rows, UUID viewerId) {
        if (rows.isEmpty()) return List.of();

        Set<UUID> senderIds = rows.stream().map(MessageByConversationEntity::getSenderId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, User> users = loadUsers(senderIds);

        List<Long> ids = rows.stream().map(MessageByConversationEntity::getMessageId).toList();
        Map<Long, List<ReactionSummary>> reactions = reactionService.countsFor(ids);

        Set<Long> replyIds = rows.stream().map(MessageByConversationEntity::getReplyToId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, MessageByIdEntity> replies = replyIds.isEmpty() ? Map.of()
                : messageByIdRepo.findAllByMessageIdIn(replyIds).stream()
                    .collect(Collectors.toMap(MessageByIdEntity::getMessageId, e -> e));

        List<MessageResponse> out = new ArrayList<>(rows.size());
        for (MessageByConversationEntity r : rows) {
            ReplyPreview reply = r.getReplyToId() == null ? null
                    : mapper.toReplyPreview(replies.get(r.getReplyToId()));
            out.add(mapper.toMessage(r, users, reactions.getOrDefault(r.getMessageId(), List.of()), reply));
        }
        return out;
    }

    private Map<UUID, User> loadUsers(Set<UUID> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        return userRepository.findActiveByIdIn(ids).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
    }

    private Conversation requireConversation(UUID conversationId) {
        return conversationRepo.findById(conversationId)
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", "id", conversationId));
    }

    private ConversationMember requireReadableMember(UUID conversationId, UUID userId) {
        return memberRepo.findMember(conversationId, userId)
                .filter(ConversationMember::canRead)
                .orElseThrow(() -> new ForbiddenException(
                        "You are not a member of this conversation.", "NOT_A_MEMBER"));
    }

    /** Epoch-ms floor of the scan range — never before the conversation existed,
     *  and (for hidden-history groups) never before the member joined. */
    private long historyFloorMillis(Conversation convo, ConversationMember me) {
        long convoFloor = convo.getCreatedAt() != null
                ? convo.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli()
                : SnowflakeIdGenerator.CUSTOM_EPOCH;
        if (hidesHistory(convo) && me.getJoinedAt() != null) {
            return Math.max(convoFloor, me.getJoinedAt().toInstant(ZoneOffset.UTC).toEpochMilli());
        }
        return convoFloor;
    }

    /** The lowest message id this member may see (hidden pre-join history), or null. */
    private Long floorMessageId(Conversation convo, ConversationMember me) {
        if (!hidesHistory(convo) || me.getJoinedAt() == null) return null;
        long joinMs = me.getJoinedAt().toInstant(ZoneOffset.UTC).toEpochMilli();
        return (joinMs - SnowflakeIdGenerator.CUSTOM_EPOCH) << 22;
    }

    private boolean hidesHistory(Conversation convo) {
        return convo.isGroup() && convo.getGroupSettings() != null
                && !convo.getGroupSettings().isHistoryVisibleToNewMembers();
    }
}

package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.cassandra.entity.MediaRef;
import ak.dev.irc.app.chat.cassandra.entity.MessageByConversationEntity;
import ak.dev.irc.app.chat.cassandra.entity.MessageByIdEntity;
import ak.dev.irc.app.chat.cassandra.repository.ChatCommentByPostRepository;
import ak.dev.irc.app.chat.cassandra.repository.MediaByConversationRepository;
import ak.dev.irc.app.chat.cassandra.repository.MessageByConversationRepository;
import ak.dev.irc.app.chat.cassandra.repository.MessageByIdRepository;
import ak.dev.irc.app.chat.entity.Conversation;
import ak.dev.irc.app.chat.enums.MessageType;
import ak.dev.irc.app.chat.repository.CallParticipantRepository;
import ak.dev.irc.app.chat.repository.CallSessionRepository;
import ak.dev.irc.app.chat.repository.ConversationDraftRepository;
import ak.dev.irc.app.chat.repository.ConversationInviteRepository;
import ak.dev.irc.app.chat.repository.ConversationJoinRequestRepository;
import ak.dev.irc.app.chat.repository.ConversationMemberRepository;
import ak.dev.irc.app.chat.repository.ConversationPinRepository;
import ak.dev.irc.app.chat.repository.ConversationRepository;
import ak.dev.irc.app.chat.repository.HiddenMessageRepository;
import ak.dev.irc.app.chat.repository.MessageRequestRepository;
import ak.dev.irc.app.chat.repository.MessageStarRepository;
import ak.dev.irc.app.chat.repository.ScheduledMessageRepository;
import ak.dev.irc.app.chat.search.service.ChannelSearchService;
import ak.dev.irc.app.chat.search.service.ChatSearchService;
import ak.dev.irc.app.chat.util.ChatBuckets;
import ak.dev.irc.app.media.service.MediaIngestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Whole-conversation hard delete — the storage cascade behind
 * {@link ConversationPurgeJob}. A conversation reaches this service only after
 * it has been SOFT-deleted ({@code Conversation.deletedAt} set) and its grace
 * window has elapsed: a DM retired because every member deleted-for-me, or a
 * group/channel its owner deleted. From that moment nothing can read or write
 * it (every read/send path filters {@code deletedAt}), so the sweep here is
 * invisible — it reclaims storage, it never changes what anyone sees.
 *
 * <p>Split into two halves on purpose:</p>
 * <ul>
 *   <li>{@link #sweepStorage} — Cassandra/Redis/ES/R2, NO transaction (none of
 *       those stores join one). Every step is idempotent, so a partial failure
 *       leaves the conversation retired and the next nightly run re-sweeps what
 *       remains — the row is only ever finalized after a FULLY clean sweep.
 *       This is the account-purge lesson applied: never mark done what only
 *       half happened.</li>
 *   <li>{@link #finalizePurge} — one small Postgres transaction that takes a
 *       pessimistic lock on the conversation row, re-checks it is still
 *       retired, and deletes the children + the row. The lock serializes
 *       against {@code createDirect}'s revive of a retired DM: the revive
 *       waits, sees the row gone, updates 0 rows, and falls through to a fresh
 *       create on the freed {@code direct_key}.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationPurgeService {

    /** Page size for the bucket walk — bounds memory, batches the ES deletes. */
    private static final int PAGE = 500;

    /** Every gallery partition kind (mirrors the validator set in
     *  {@code MessageQueryService.gallery}). */
    private static final Set<String> GALLERY_KINDS = Set.of(
            "IMAGE", "VIDEO", "VOICE", "AUDIO", "GIF", "STICKER", "VIDEO_NOTE", "FILE", "LINK");

    private final ConversationRepository conversationRepo;
    private final ConversationMemberRepository memberRepo;
    private final MessageByConversationRepository messageRepo;
    private final MessageByIdRepository messageByIdRepo;
    private final MediaByConversationRepository mediaGalleryRepo;
    private final ChatCommentByPostRepository commentRepo;
    private final ConversationPinRepository pinRepo;
    private final MessageStarRepository starRepo;
    private final HiddenMessageRepository hiddenRepo;
    private final ConversationDraftRepository draftRepo;
    private final ScheduledMessageRepository scheduledRepo;
    private final MessageRequestRepository requestRepo;
    private final ConversationInviteRepository inviteRepo;
    private final ConversationJoinRequestRepository joinRequestRepo;
    private final CallSessionRepository callRepo;
    private final CallParticipantRepository callParticipantRepo;
    private final ReactionService reactionService;
    private final PollService pollService;
    private final ChatSearchService chatSearch;
    private final ChannelSearchService channelSearch;
    private final ChannelPostMetricsService channelMetrics;
    private final MediaIngestService mediaIngest;
    private final ak.dev.irc.app.admin.chat.LegalHoldRepository legalHoldRepo;

    // ── Phase 1: retire fully-deleted DMs ───────────────────────────────────────

    /**
     * Stamp {@code deletedAt} on a DIRECT conversation every member has
     * deleted-for-me. The eligibility is re-checked HERE, under a row lock,
     * because the candidate list was computed in an earlier transaction — a
     * message that landed in between resurrects the thread for its members and
     * must veto the retire (the lock makes a concurrent send's
     * {@code advanceLastMessage} wait, so the count sees it). Two more vetoes:
     * a PENDING send-later (retiring would strand it as an invisible FAILURE),
     * and a Cassandra log head above the lowest member floor — {@code persist}
     * writes Cassandra before the transactional Postgres pointer advance, so a
     * rolled-back send can leave READABLE rows the pointer never learned about,
     * and eligibility must consult the log itself, not just the mirror.
     * Returns whether the row was retired.
     */
    @Transactional
    public boolean retireDirect(UUID conversationId) {
        Conversation c = conversationRepo.lockById(conversationId).orElse(null);
        if (c == null || c.getDeletedAt() != null) return false;
        if (memberRepo.countMembersWhoCanSee(conversationId) > 0) return false;
        if (scheduledRepo.existsByConversationIdAndStatus(
                conversationId, ak.dev.irc.app.chat.enums.ScheduledMessageStatus.PENDING)) {
            return false;
        }
        if (cassandraHeadAbove(c, memberRepo.minClearedFloor(conversationId))) return false;
        return conversationRepo.retire(conversationId, LocalDateTime.now()) == 1;
    }

    /** True when the newest row in the Cassandra log has an id above
     *  {@code floor} — i.e. some member could still read something the
     *  Postgres {@code lastMessageId} mirror does not reflect. Walks buckets
     *  newest-first and stops at the first non-empty one (its head is the
     *  global newest, ids and buckets being monotonic together). */
    private boolean cassandraHeadAbove(Conversation c, long floor) {
        long createdMs = c.getCreatedAt() == null
                ? System.currentTimeMillis()
                : c.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli();
        int stop = ChatBuckets.bucketForTimestamp(createdMs) - 1;
        for (int bucket = ChatBuckets.currentBucket(); bucket >= stop; bucket--) {
            List<MessageByConversationEntity> head = messageRepo.firstPage(c.getId(), bucket, 1);
            if (!head.isEmpty()) return head.get(0).getMessageId() > floor;
        }
        return false;
    }

    /**
     * Nightly revive pass over retired DMs (graced or not): one that turned out
     * to be visible to someone — the retire race losing in the narrowest way —
     * steps back into the inbox instead of waiting out the grace invisibly.
     * The lock serializes against {@link #beginPurge}; a row whose
     * {@code directKey} is already nulled belongs to a purge in progress and is
     * left alone. Returns whether a revive happened.
     */
    @Transactional
    public boolean reviveIfSeeable(Conversation c) {
        if (!c.isDirect()) return false;
        Conversation row = conversationRepo.lockById(c.getId()).orElse(null);
        if (row == null || row.getDeletedAt() == null) return false;   // gone, or already live
        if (row.getDirectKey() == null) return false;                  // purge has committed to it
        if (memberRepo.countMembersWhoCanSee(row.getId()) == 0) return false;
        row.setDeletedAt(null);
        conversationRepo.save(row);
        return true;
    }

    // ── Phase 2 gate: commit to the purge, or step away ─────────────────────────

    /** {@link #beginPurge}'s answer — every SKIP leaves the row exactly as found
     *  (except SKIP_REVIVED, which un-retires it). */
    public enum PurgeGate { PROCEED, SKIP_REVIVED, SKIP_HELD, SKIP_GONE }

    /**
     * The point of no return, taken under the row lock immediately before the
     * irreversible storage sweep:
     * <ul>
     *   <li>row gone or no longer retired (a {@code createDirect} revive won
     *       the race) → skip untouched;</li>
     *   <li>an OPEN/APPROVED legal hold → skip: holds read message content
     *       LIVE from the log during the grace window, and destroying their
     *       evidence would be unrecoverable (EXECUTED/REJECTED holds have had
     *       their moment);</li>
     *   <li>a retired DM that became visible again → revive and skip;</li>
     *   <li>otherwise, for a DM, null {@code directKey} — the purge FENCE:
     *       {@code reviveRetiredDirect} refuses a keyless row, so from this
     *       commit on no revive can interleave with the sweep, and a reopening
     *       pair falls through to a fresh conversation on the freed key.</li>
     * </ul>
     */
    @Transactional
    public PurgeGate beginPurge(UUID conversationId) {
        Conversation c = conversationRepo.lockById(conversationId).orElse(null);
        if (c == null) return PurgeGate.SKIP_GONE;
        if (c.getDeletedAt() == null) return PurgeGate.SKIP_REVIVED;
        if (legalHoldRepo.existsByConversationIdAndStatusIn(conversationId,
                List.of(ak.dev.irc.app.admin.chat.LegalHold.Status.OPEN,
                        ak.dev.irc.app.admin.chat.LegalHold.Status.APPROVED))) {
            return PurgeGate.SKIP_HELD;
        }
        if (c.isDirect()) {
            if (memberRepo.countMembersWhoCanSee(conversationId) > 0) {
                c.setDeletedAt(null);
                conversationRepo.save(c);
                return PurgeGate.SKIP_REVIVED;
            }
            c.setDirectKey(null);
            conversationRepo.save(c);
        }
        return PurgeGate.PROCEED;
    }

    // ── Phase 2a: the storage sweep (no transaction — see class doc) ────────────

    /**
     * Reclaim everything outside Postgres: the Cassandra message log (both
     * tables, bucket by bucket), reactions, poll votes, the ES message docs,
     * the media gallery partitions, comment-index rows, channel metrics, and
     * the stored media objects. Returns {@code true} only when every step
     * succeeded — the caller must not finalize otherwise. Idempotent
     * throughout: a re-run over half-purged storage deletes what remains and
     * no-ops the rest.
     */
    public boolean sweepStorage(Conversation c) {
        UUID cid = c.getId();
        boolean clean = true;
        // A GROUP that serves as a channel's discussion group: its messages are
        // comment members of the CHANNEL's comment-index partitions, which stay
        // behind unless removed here (mirrors MessageService.dropCommentLinks).
        Conversation linkingChannel = c.isGroup()
                ? conversationRepo.findByLinkedGroupId(cid).orElse(null)
                : null;

        long createdMs = c.getCreatedAt() == null
                ? System.currentTimeMillis()
                : c.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli();
        // One bucket of margin below the creation bucket: created_at is stamped
        // by the Postgres app node and message buckets by the Snowflake clock —
        // a skew across a 10-day boundary would silently strand the first
        // partition, and an extra empty-bucket delete costs one tombstone.
        int first = ChatBuckets.bucketForTimestamp(createdMs) - 1;
        int last = ChatBuckets.currentBucket();

        // Kinds actually seen on refs during the walk — clients could historically
        // write free-form kinds, and a partition under a kind outside the fixed
        // set would otherwise survive the purge.
        Set<String> observedKinds = new java.util.HashSet<>(GALLERY_KINDS);

        for (int bucket = first; bucket <= last; bucket++) {
            try {
                List<MessageByConversationEntity> page = messageRepo.firstPage(cid, bucket, PAGE);
                while (!page.isEmpty()) {
                    List<Long> ids = new ArrayList<>(page.size());
                    for (MessageByConversationEntity m : page) {
                        ids.add(m.getMessageId());
                        if (m.getMedia() != null) {
                            for (MediaRef ref : m.getMedia()) {
                                if (StringUtils.hasText(ref.getKind())) observedKinds.add(ref.getKind());
                            }
                        }
                        purgeMessageSideData(c, linkingChannel, m);
                    }
                    /* ES BEFORE the log rows go: this synchronous call throws on
                       failure, deferring the bucket while the ids — the only
                       handle to the docs — still exist for the re-sweep. A
                       fire-and-forget here once meant a broken ES cluster left
                       message bodies indexed forever. */
                    chatSearch.deleteBatch(ids);
                    messageByIdRepo.deleteAllById(ids);
                    page = page.size() < PAGE
                            ? List.of()
                            : messageRepo.pageBefore(cid, bucket, page.get(page.size() - 1).getMessageId(), PAGE);
                }
                messageRepo.deleteBucket(cid, bucket);
            } catch (Exception e) {
                clean = false;
                log.warn("[CHAT-PURGE] {} bucket {} sweep failed: {}", cid, bucket, e.getMessage());
            }
        }

        clean &= step(cid, "gallery partitions", () -> {
            for (String kind : observedKinds) mediaGalleryRepo.deleteAllForConversationKind(cid, kind);
        });
        if (c.isChannel()) {
            clean &= step(cid, "channel metric keys", () -> channelMetrics.purgeChannel(cid));
            // deleteChannel already de-indexed at soft-delete time; defensive re-run.
            clean &= step(cid, "channel search doc", () -> channelSearch.deleteAsync(cid));
        }
        return clean;
    }

    /** Everything one message row references outside the two log tables. All
     *  strict variants: a swallowed failure here would report a clean sweep
     *  over rows whose only handle (the message id) is about to be destroyed. */
    private void purgeMessageSideData(Conversation c, Conversation linkingChannel,
                                      MessageByConversationEntity m) {
        long mid = m.getMessageId();
        boolean system = MessageType.SYSTEM.name().equals(m.getType());
        reactionService.clearStrict(mid);
        if (StringUtils.hasText(m.getPoll())) pollService.clearStrict(mid);
        /* CHANNEL media is deliberately NOT deleted: channel posts are the
           most-forwarded content in the system, forwards share the source's
           MediaRefs verbatim, and no reverse index exists to know whether a
           surviving forward still needs the object — deleting would kill the
           media under every forward platform-wide. The orphaned objects are the
           future reference-count/reconcile work's to reclaim. DM/group media IS
           deleted (both/all parties chose deletion, forwards out are rare, and
           this matches the per-message delete's existing semantics). */
        if (!c.isChannel()) dropStoredMedia(m);
        if (c.isChannel()) {
            if (!system) {
                commentRepo.deleteAllForPost(mid);           // the post's whole comment index
                channelMetrics.clearStrict(c.getId(), mid);  // counter row + viewer HLL + top entry
            }
        } else if (linkingChannel != null && m.getReplyToId() != null) {
            // A discussion-group comment: retire its row in the CHANNEL's index
            // and give the post's counter back its −1 — the channel outlives
            // this group, so the index must not dangle. Point-read guarded like
            // dropCommentLinks: replyTo may be an ordinary in-group reply. The
            // second guard (the index row still exists) keeps a RE-SWEEP after a
            // partial failure from decrementing the surviving channel's counter
            // twice — the delete is idempotent, the delta is not.
            MessageByIdEntity post = messageByIdRepo.findById(m.getReplyToId()).orElse(null);
            if (post != null && linkingChannel.getId().equals(post.getConversationId())
                    && commentRepo.findOne(m.getReplyToId(), mid).isPresent()) {
                commentRepo.deleteOne(m.getReplyToId(), mid);
                channelMetrics.onCommentDelta(m.getReplyToId(), -1);
            }
        }
    }

    /**
     * Mirror of {@code MessageService.dropStoredMedia}: pipeline assets by id,
     * legacy uploads by key, legacy client thumbnails as their own object —
     * and forwarded messages keep their objects, because the refs are SHARED
     * with the source message. Failures PROPAGATE (unlike the per-message
     * path): a lost Rabbit publish would otherwise orphan the R2 object with
     * its storage key destroyed a moment later — the bucket catch defers the
     * whole bucket instead, and the delete queue is idempotent on re-sweep.
     */
    private void dropStoredMedia(MessageByConversationEntity m) {
        if (m.getForwardedFrom() != null) return;
        if (m.getMedia() == null || m.getMedia().isEmpty()) return;
        for (MediaRef ref : m.getMedia()) {
            mediaIngest.deleteByStorageKey(ref.getStorageKey());
            if (ref.getThumbnailKey() != null
                    && MediaIngestService.assetIdFromKey(ref.getThumbnailKey()) == null) {
                mediaIngest.deleteByStorageKey(ref.getThumbnailKey());
            }
        }
    }

    private boolean step(UUID cid, String what, Runnable action) {
        try {
            action.run();
            return true;
        } catch (Exception e) {
            log.warn("[CHAT-PURGE] {} {} failed: {}", cid, what, e.getMessage());
            return false;
        }
    }

    // ── Phase 2b: the final Postgres transaction ────────────────────────────────

    /**
     * Delete the conversation's Postgres footprint — children first, then the
     * row. Runs ONLY after a clean {@link #sweepStorage}. The pessimistic lock
     * plus the re-check make a concurrent {@code createDirect} revive either
     * win outright (we abort, storage was invisible to both members anyway) or
     * wait and observe the deletion. Returns whether the row was purged.
     */
    @Transactional
    public boolean finalizePurge(UUID conversationId) {
        Conversation c = conversationRepo.lockById(conversationId).orElse(null);
        if (c == null) return false;                     // already gone
        if (c.getDeletedAt() == null) {
            log.info("[CHAT-PURGE] {} was revived mid-purge — leaving the row alone", conversationId);
            return false;
        }
        List<UUID> callIds = callRepo.findIdsByConversationId(conversationId);
        if (!callIds.isEmpty()) callParticipantRepo.deleteAllForCalls(callIds);
        callRepo.deleteAllForConversation(conversationId);
        starRepo.deleteAllForConversation(conversationId);
        hiddenRepo.deleteAllForConversation(conversationId);
        pinRepo.deleteAllForConversation(conversationId);
        draftRepo.deleteAllForConversation(conversationId);
        scheduledRepo.deleteAllForConversation(conversationId);
        requestRepo.deleteAllForConversation(conversationId);
        inviteRepo.deleteAllForConversation(conversationId);
        joinRequestRepo.deleteAllForConversation(conversationId);
        memberRepo.deleteAllForConversation(conversationId);
        // A purged discussion GROUP must not leave its channel advertising
        // comments against a dangling id — mirror ChannelDiscussionService's
        // unlink so the channel degrades to "comments disabled".
        if (c.isGroup()) {
            conversationRepo.findByLinkedGroupId(conversationId).ifPresent(channel -> {
                channel.setLinkedGroupId(null);
                conversationRepo.save(channel);
            });
        }
        conversationRepo.delete(c);
        return true;
    }
}

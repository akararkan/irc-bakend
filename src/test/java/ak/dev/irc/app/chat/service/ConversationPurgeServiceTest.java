package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.admin.chat.LegalHold;
import ak.dev.irc.app.admin.chat.LegalHoldRepository;
import ak.dev.irc.app.chat.cassandra.entity.ChatCommentByPostEntity;
import ak.dev.irc.app.chat.cassandra.entity.MediaRef;
import ak.dev.irc.app.chat.cassandra.entity.MessageByConversationEntity;
import ak.dev.irc.app.chat.cassandra.entity.MessageByIdEntity;
import ak.dev.irc.app.chat.cassandra.repository.ChatCommentByPostRepository;
import ak.dev.irc.app.chat.cassandra.repository.MediaByConversationRepository;
import ak.dev.irc.app.chat.cassandra.repository.MessageByConversationRepository;
import ak.dev.irc.app.chat.cassandra.repository.MessageByIdRepository;
import ak.dev.irc.app.chat.entity.Conversation;
import ak.dev.irc.app.chat.enums.ConversationType;
import ak.dev.irc.app.chat.enums.ScheduledMessageStatus;
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
import ak.dev.irc.app.media.service.MediaIngestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The purge's DECISION logic — the retire vetoes, the revive escape hatches,
 * the {@code beginPurge} gate (including the directKey fence and the
 * legal-hold guard), the finalize guards, and the per-message storage fan-out
 * (most importantly: forwarded copies and channel media keep their objects).
 */
@ExtendWith(MockitoExtension.class)
class ConversationPurgeServiceTest {

    @Mock private ConversationRepository conversationRepo;
    @Mock private ConversationMemberRepository memberRepo;
    @Mock private MessageByConversationRepository messageRepo;
    @Mock private MessageByIdRepository messageByIdRepo;
    @Mock private MediaByConversationRepository mediaGalleryRepo;
    @Mock private ChatCommentByPostRepository commentRepo;
    @Mock private ConversationPinRepository pinRepo;
    @Mock private MessageStarRepository starRepo;
    @Mock private HiddenMessageRepository hiddenRepo;
    @Mock private ConversationDraftRepository draftRepo;
    @Mock private ScheduledMessageRepository scheduledRepo;
    @Mock private MessageRequestRepository requestRepo;
    @Mock private ConversationInviteRepository inviteRepo;
    @Mock private ConversationJoinRequestRepository joinRequestRepo;
    @Mock private CallSessionRepository callRepo;
    @Mock private CallParticipantRepository callParticipantRepo;
    @Mock private ReactionService reactionService;
    @Mock private PollService pollService;
    @Mock private ChatSearchService chatSearch;
    @Mock private ChannelSearchService channelSearch;
    @Mock private ChannelPostMetricsService channelMetrics;
    @Mock private MediaIngestService mediaIngest;
    @Mock private LegalHoldRepository legalHoldRepo;

    @InjectMocks private ConversationPurgeService service;

    private final UUID convId = UUID.randomUUID();

    private Conversation convo(ConversationType type) {
        Conversation c = Conversation.builder().id(convId).type(type).build();
        if (type == ConversationType.DIRECT) c.setDirectKey("a:b");
        // A recent createdAt keeps the bucket walk to the margin bucket + one.
        c.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return c;
    }

    private Conversation retired(ConversationType type) {
        Conversation c = convo(type);
        c.setDeletedAt(LocalDateTime.now());
        return c;
    }

    private MessageByConversationEntity row(long id) {
        return MessageByConversationEntity.builder()
                .conversationId(convId).bucket(1).messageId(id).type("TEXT").build();
    }

    // ── retireDirect ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("retireDirect: a member who can still see the thread vetoes the retire")
    void retireDirect_vetoedWhileVisible() {
        when(conversationRepo.lockById(convId)).thenReturn(Optional.of(convo(ConversationType.DIRECT)));
        when(memberRepo.countMembersWhoCanSee(convId)).thenReturn(1L);
        assertThat(service.retireDirect(convId)).isFalse();
        verify(conversationRepo, never()).retire(eq(convId), any());
    }

    @Test
    @DisplayName("retireDirect: a PENDING send-later vetoes the retire")
    void retireDirect_vetoedByScheduledSend() {
        when(conversationRepo.lockById(convId)).thenReturn(Optional.of(convo(ConversationType.DIRECT)));
        when(memberRepo.countMembersWhoCanSee(convId)).thenReturn(0L);
        when(scheduledRepo.existsByConversationIdAndStatus(convId, ScheduledMessageStatus.PENDING))
                .thenReturn(true);
        assertThat(service.retireDirect(convId)).isFalse();
        verify(conversationRepo, never()).retire(eq(convId), any());
    }

    @Test
    @DisplayName("retireDirect: a Cassandra log head above the lowest floor vetoes — the Postgres mirror is not trusted")
    void retireDirect_vetoedByOrphanCassandraRow() {
        when(conversationRepo.lockById(convId)).thenReturn(Optional.of(convo(ConversationType.DIRECT)));
        when(memberRepo.countMembersWhoCanSee(convId)).thenReturn(0L);
        when(scheduledRepo.existsByConversationIdAndStatus(convId, ScheduledMessageStatus.PENDING))
                .thenReturn(false);
        when(memberRepo.minClearedFloor(convId)).thenReturn(100L);
        // A rolled-back send left a readable row the pointer never learned about.
        when(messageRepo.firstPage(eq(convId), anyInt(), eq(1))).thenReturn(List.of(row(101L)));
        assertThat(service.retireDirect(convId)).isFalse();
        verify(conversationRepo, never()).retire(eq(convId), any());
    }

    @Test
    @DisplayName("retireDirect: stamps deletedAt once nobody can see anything")
    void retireDirect_stampsWhenInvisible() {
        when(conversationRepo.lockById(convId)).thenReturn(Optional.of(convo(ConversationType.DIRECT)));
        when(memberRepo.countMembersWhoCanSee(convId)).thenReturn(0L);
        when(scheduledRepo.existsByConversationIdAndStatus(convId, ScheduledMessageStatus.PENDING))
                .thenReturn(false);
        when(memberRepo.minClearedFloor(convId)).thenReturn(100L);
        when(messageRepo.firstPage(eq(convId), anyInt(), eq(1))).thenReturn(List.of(row(100L)));
        when(conversationRepo.retire(eq(convId), any())).thenReturn(1);
        assertThat(service.retireDirect(convId)).isTrue();
    }

    // ── reviveIfSeeable ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("reviveIfSeeable: owner-deleted rooms are never revived")
    void revive_neverForRooms() {
        assertThat(service.reviveIfSeeable(retired(ConversationType.GROUP))).isFalse();
        verify(conversationRepo, never()).lockById(any());
    }

    @Test
    @DisplayName("reviveIfSeeable: a resurrect-eligible retired DM steps back from the purge")
    void revive_directWithVisibleMember() {
        Conversation row = retired(ConversationType.DIRECT);
        when(conversationRepo.lockById(convId)).thenReturn(Optional.of(row));
        when(memberRepo.countMembersWhoCanSee(convId)).thenReturn(1L);
        assertThat(service.reviveIfSeeable(row)).isTrue();
        assertThat(row.getDeletedAt()).isNull();
        verify(conversationRepo).save(row);
    }

    @Test
    @DisplayName("reviveIfSeeable: a keyless row belongs to a purge in progress — left alone")
    void revive_refusesFencedRow() {
        Conversation row = retired(ConversationType.DIRECT);
        row.setDirectKey(null);                       // beginPurge committed to it
        when(conversationRepo.lockById(convId)).thenReturn(Optional.of(row));
        assertThat(service.reviveIfSeeable(row)).isFalse();
        verify(conversationRepo, never()).save(any(Conversation.class));
    }

    // ── beginPurge ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("beginPurge: a row createDirect already revived is SKIPPED — never swept")
    void begin_skipsAlreadyRevivedRow() {
        when(conversationRepo.lockById(convId)).thenReturn(Optional.of(convo(ConversationType.DIRECT)));
        assertThat(service.beginPurge(convId)).isEqualTo(ConversationPurgeService.PurgeGate.SKIP_REVIVED);
    }

    @Test
    @DisplayName("beginPurge: an OPEN/APPROVED legal hold retains the conversation")
    void begin_skipsHeldConversation() {
        when(conversationRepo.lockById(convId)).thenReturn(Optional.of(retired(ConversationType.CHANNEL)));
        when(legalHoldRepo.existsByConversationIdAndStatusIn(eq(convId), anyCollection())).thenReturn(true);
        assertThat(service.beginPurge(convId)).isEqualTo(ConversationPurgeService.PurgeGate.SKIP_HELD);
    }

    @Test
    @DisplayName("beginPurge: a seeable retired DM is revived, not purged")
    void begin_revivesSeeableDirect() {
        Conversation row = retired(ConversationType.DIRECT);
        when(conversationRepo.lockById(convId)).thenReturn(Optional.of(row));
        when(legalHoldRepo.existsByConversationIdAndStatusIn(eq(convId), anyCollection())).thenReturn(false);
        when(memberRepo.countMembersWhoCanSee(convId)).thenReturn(1L);
        assertThat(service.beginPurge(convId)).isEqualTo(ConversationPurgeService.PurgeGate.SKIP_REVIVED);
        assertThat(row.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("beginPurge: committing to a DM nulls its directKey — the revive fence")
    void begin_fencesDirectKey() {
        Conversation row = retired(ConversationType.DIRECT);
        when(conversationRepo.lockById(convId)).thenReturn(Optional.of(row));
        when(legalHoldRepo.existsByConversationIdAndStatusIn(eq(convId), anyCollection())).thenReturn(false);
        when(memberRepo.countMembersWhoCanSee(convId)).thenReturn(0L);
        assertThat(service.beginPurge(convId)).isEqualTo(ConversationPurgeService.PurgeGate.PROCEED);
        assertThat(row.getDirectKey()).isNull();
        verify(conversationRepo).save(row);
    }

    // ── finalizePurge ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("finalizePurge: a row revived mid-purge is left completely alone")
    void finalize_abortsOnRevivedRow() {
        when(conversationRepo.lockById(convId)).thenReturn(Optional.of(convo(ConversationType.DIRECT)));
        assertThat(service.finalizePurge(convId)).isFalse();
        verify(memberRepo, never()).deleteAllForConversation(any());
        verify(conversationRepo, never()).delete(any(Conversation.class));
    }

    @Test
    @DisplayName("finalizePurge: children go before the row; participants before their sessions")
    void finalize_deletesChildrenThenRow() {
        Conversation retired = retired(ConversationType.DIRECT);
        when(conversationRepo.lockById(convId)).thenReturn(Optional.of(retired));
        UUID callId = UUID.randomUUID();
        when(callRepo.findIdsByConversationId(convId)).thenReturn(List.of(callId));

        assertThat(service.finalizePurge(convId)).isTrue();

        InOrder order = inOrder(callParticipantRepo, callRepo, memberRepo, conversationRepo);
        order.verify(callParticipantRepo).deleteAllForCalls(List.of(callId));
        order.verify(callRepo).deleteAllForConversation(convId);
        order.verify(memberRepo).deleteAllForConversation(convId);
        order.verify(conversationRepo).delete(retired);
        verify(starRepo).deleteAllForConversation(convId);
        verify(hiddenRepo).deleteAllForConversation(convId);
        verify(pinRepo).deleteAllForConversation(convId);
        verify(draftRepo).deleteAllForConversation(convId);
        verify(scheduledRepo).deleteAllForConversation(convId);
        verify(requestRepo).deleteAllForConversation(convId);
        verify(inviteRepo).deleteAllForConversation(convId);
        verify(joinRequestRepo).deleteAllForConversation(convId);
    }

    @Test
    @DisplayName("finalizePurge: purging a discussion group unlinks its surviving channel")
    void finalize_unlinksDiscussionChannel() {
        Conversation group = retired(ConversationType.GROUP);
        when(conversationRepo.lockById(convId)).thenReturn(Optional.of(group));
        when(callRepo.findIdsByConversationId(convId)).thenReturn(List.of());
        Conversation channel = Conversation.builder()
                .id(UUID.randomUUID()).type(ConversationType.CHANNEL).linkedGroupId(convId).build();
        when(conversationRepo.findByLinkedGroupId(convId)).thenReturn(Optional.of(channel));

        assertThat(service.finalizePurge(convId)).isTrue();

        assertThat(channel.getLinkedGroupId()).isNull();
        verify(conversationRepo).save(channel);
    }

    // ── sweepStorage ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sweep: forwarded messages keep their media objects; own messages lose theirs")
    void sweep_forwardedMediaSurvives() {
        Conversation dm = convo(ConversationType.DIRECT);
        MessageByConversationEntity own = row(1L);
        own.setMedia(List.of(MediaRef.builder().kind("IMAGE").storageKey("media/x/1.jpg").build()));
        MessageByConversationEntity forwarded = row(2L);
        forwarded.setForwardedFrom(UUID.randomUUID());
        forwarded.setMedia(List.of(MediaRef.builder().kind("IMAGE").storageKey("media/y/2.jpg").build()));
        when(messageRepo.firstPage(eq(convId), anyInt(), anyInt()))
                .thenReturn(List.of(own, forwarded)).thenReturn(List.of());

        assertThat(service.sweepStorage(dm)).isTrue();

        verify(mediaIngest).deleteByStorageKey("media/x/1.jpg");
        verify(mediaIngest, never()).deleteByStorageKey("media/y/2.jpg");
        verify(messageByIdRepo).deleteAllById(List.of(1L, 2L));
        verify(chatSearch).deleteBatch(List.of(1L, 2L));
        verify(messageRepo, times(2)).deleteBucket(eq(convId), anyInt());
        verify(reactionService).clearStrict(1L);
        verify(reactionService).clearStrict(2L);
    }

    @Test
    @DisplayName("sweep: channel media is NEVER deleted (forwards share the refs); comment index + metrics go, SYSTEM rows skipped")
    void sweep_channelPostFanout() {
        Conversation channel = convo(ConversationType.CHANNEL);
        MessageByConversationEntity post = row(10L);
        post.setMedia(List.of(MediaRef.builder().kind("IMAGE").storageKey("media/z/10.jpg").build()));
        MessageByConversationEntity systemRow = row(11L);
        systemRow.setType("SYSTEM");
        when(messageRepo.firstPage(eq(convId), anyInt(), anyInt()))
                .thenReturn(List.of(post, systemRow)).thenReturn(List.of());

        assertThat(service.sweepStorage(channel)).isTrue();

        verify(mediaIngest, never()).deleteByStorageKey(anyString());
        verify(commentRepo).deleteAllForPost(10L);
        verify(commentRepo, never()).deleteAllForPost(11L);
        verify(channelMetrics).clearStrict(convId, 10L);
        verify(channelMetrics, never()).clearStrict(convId, 11L);
        verify(channelMetrics).purgeChannel(convId);
        verify(channelSearch).deleteAsync(convId);
    }

    @Test
    @DisplayName("sweep: a discussion-group comment retires its row in the CHANNEL's index (point-read guarded)")
    void sweep_discussionCommentCleansChannelIndex() {
        Conversation group = convo(ConversationType.GROUP);
        UUID channelId = UUID.randomUUID();
        Conversation channel = Conversation.builder().id(channelId).type(ConversationType.CHANNEL).build();
        when(conversationRepo.findByLinkedGroupId(convId)).thenReturn(Optional.of(channel));

        MessageByConversationEntity comment = row(21L);
        comment.setReplyToId(20L);                        // the channel post
        MessageByConversationEntity plainReply = row(23L);
        plainReply.setReplyToId(22L);                     // an ordinary in-group reply
        when(messageRepo.firstPage(eq(convId), anyInt(), anyInt()))
                .thenReturn(List.of(comment, plainReply)).thenReturn(List.of());
        MessageByIdEntity channelPost = new MessageByIdEntity();
        channelPost.setConversationId(channelId);
        MessageByIdEntity groupMessage = new MessageByIdEntity();
        groupMessage.setConversationId(convId);
        when(messageByIdRepo.findById(20L)).thenReturn(Optional.of(channelPost));
        when(messageByIdRepo.findById(22L)).thenReturn(Optional.of(groupMessage));
        when(commentRepo.findOne(20L, 21L)).thenReturn(Optional.of(new ChatCommentByPostEntity()));

        assertThat(service.sweepStorage(group)).isTrue();

        verify(commentRepo).deleteOne(20L, 21L);
        verify(channelMetrics).onCommentDelta(20L, -1);
        verify(commentRepo, never()).deleteOne(eq(22L), anyLong());
    }

    @Test
    @DisplayName("sweep re-run: a comment whose index row is already gone must not re-decrement the counter")
    void sweep_resweptCommentDoesNotDoubleDecrement() {
        Conversation group = convo(ConversationType.GROUP);
        UUID channelId = UUID.randomUUID();
        when(conversationRepo.findByLinkedGroupId(convId))
                .thenReturn(Optional.of(Conversation.builder().id(channelId).type(ConversationType.CHANNEL).build()));
        MessageByConversationEntity comment = row(21L);
        comment.setReplyToId(20L);
        when(messageRepo.firstPage(eq(convId), anyInt(), anyInt()))
                .thenReturn(List.of(comment)).thenReturn(List.of());
        MessageByIdEntity channelPost = new MessageByIdEntity();
        channelPost.setConversationId(channelId);
        when(messageByIdRepo.findById(20L)).thenReturn(Optional.of(channelPost));
        when(commentRepo.findOne(20L, 21L)).thenReturn(Optional.empty());   // first sweep took it

        assertThat(service.sweepStorage(group)).isTrue();

        verify(commentRepo, never()).deleteOne(anyLong(), anyLong());
        verify(channelMetrics, never()).onCommentDelta(anyLong(), anyLong());
    }

    @Test
    @DisplayName("sweep: an ES delete failure defers the bucket — the ids survive for the re-sweep")
    void sweep_esFailureKeepsBucket() {
        Conversation dm = convo(ConversationType.DIRECT);
        when(messageRepo.firstPage(eq(convId), anyInt(), anyInt()))
                .thenReturn(List.of(row(1L))).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new RuntimeException("es down"))
                .when(chatSearch).deleteBatch(anyCollection());

        assertThat(service.sweepStorage(dm)).isFalse();

        // The bucket holding the ids is preserved (only the empty one was
        // dropped); the by-id rows survive too.
        verify(messageRepo, times(1)).deleteBucket(eq(convId), anyInt());
        verify(messageByIdRepo, never()).deleteAllById(anyCollection());
    }

    @Test
    @DisplayName("sweep: a failed bucket reports unclean so the row is never finalized")
    void sweep_failureBlocksFinalize() {
        Conversation dm = convo(ConversationType.DIRECT);
        when(messageRepo.firstPage(eq(convId), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("cassandra down"));
        // Later steps still run (idempotent partial progress) — stub leniently.
        lenient().when(conversationRepo.findByLinkedGroupId(any())).thenReturn(Optional.empty());

        assertThat(service.sweepStorage(dm)).isFalse();

        // The gallery partitions were still attempted despite the bucket failure.
        verify(mediaGalleryRepo, org.mockito.Mockito.atLeastOnce())
                .deleteAllForConversationKind(eq(convId), anyString());
    }
}

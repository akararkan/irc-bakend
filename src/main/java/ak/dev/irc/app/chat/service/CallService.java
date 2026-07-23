package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.dto.request.CallSignalRequest;
import ak.dev.irc.app.chat.dto.response.CallParticipantResponse;
import ak.dev.irc.app.chat.dto.response.CallResponse;
import ak.dev.irc.app.chat.dto.response.CallSignalMessage;
import ak.dev.irc.app.chat.entity.CallParticipant;
import ak.dev.irc.app.chat.entity.CallSession;
import ak.dev.irc.app.chat.entity.Conversation;
import ak.dev.irc.app.chat.entity.ConversationMember;
import ak.dev.irc.app.chat.enums.CallParticipantState;
import ak.dev.irc.app.chat.enums.CallStatus;
import ak.dev.irc.app.chat.enums.CallType;
import ak.dev.irc.app.chat.permission.ChatRelationshipService;
import ak.dev.irc.app.chat.realtime.ChatRealtimeBroadcaster;
import ak.dev.irc.app.chat.realtime.ChatRealtimeEvent;
import ak.dev.irc.app.chat.realtime.ChatRealtimeEventType;
import ak.dev.irc.app.chat.repository.CallParticipantRepository;
import ak.dev.irc.app.chat.repository.CallSessionRepository;
import ak.dev.irc.app.chat.repository.ConversationMemberRepository;
import ak.dev.irc.app.chat.repository.ConversationRepository;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Voice/video call control plane. The server owns call lifecycle (ring → answer →
 * end) and is a <b>blind relay</b> for WebRTC signaling (SDP/ICE) over the existing
 * per-user SSE stream; the media itself flows peer-to-peer and never touches the
 * server. Supports 1:1 and group calls — every active conversation member is
 * invited (rung). One live call per conversation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallService {

    /** How long a call may ring unanswered before the sweep marks it MISSED. */
    private static final Duration RING_TIMEOUT = Duration.ofSeconds(60);

    private final CallSessionRepository callRepo;
    private final CallParticipantRepository participantRepo;
    private final ConversationRepository conversationRepo;
    private final ConversationMemberRepository memberRepo;
    private final ChatRelationshipService relationships;
    private final ChatRealtimeBroadcaster broadcaster;

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Transactional
    public CallResponse initiate(UUID conversationId, UUID initiatorId, CallType type) {
        Conversation convo = requireConversation(conversationId);
        requireActiveMember(conversationId, initiatorId);

        // One live call per conversation — return the existing one instead of forking.
        CallSession existing = callRepo.findFirstByConversationIdAndStatusInOrderByStartedAtDesc(
                conversationId, List.of(CallStatus.RINGING, CallStatus.ONGOING)).orElse(null);
        if (existing != null) return toResponse(existing);

        // Direct: never ring across a block.
        if (convo.isDirect()) {
            UUID peer = otherDirectMember(conversationId, initiatorId);
            if (peer != null && relationships.isBlockedEitherWay(initiatorId, peer)) {
                throw new ForbiddenException("This interaction is not allowed.", "BLOCKED");
            }
        }

        Instant now = Instant.now();
        CallSession call = callRepo.save(CallSession.builder()
                .conversationId(conversationId).initiatorId(initiatorId)
                .type(type).status(CallStatus.RINGING).startedAt(now)
                .build());
        // The initiator is JOINED; every other active member is INVITED (ringing).
        List<UUID> members = memberRepo.findActiveMemberIds(conversationId);
        List<CallParticipant> ring = new java.util.ArrayList<>(members.size() + 1);
        ring.add(participant(call.getId(), initiatorId, CallParticipantState.JOINED, now));
        for (UUID uid : members) {
            if (!uid.equals(initiatorId)) {
                ring.add(participant(call.getId(), uid, CallParticipantState.INVITED, null));
            }
        }
        participantRepo.saveAll(ring);

        CallResponse resp = toResponse(call);
        broadcaster.broadcastExcept(members, initiatorId, event(ChatRealtimeEventType.CALL_INCOMING, resp));
        return resp;
    }

    @Transactional
    public CallResponse accept(UUID callId, UUID userId) {
        CallSession call = requireActiveCall(callId);
        CallParticipant p = requireInvitee(call, userId);
        Instant now = Instant.now();
        if (call.getStatus() == CallStatus.RINGING) {
            call.setStatus(CallStatus.ONGOING);
            call.setAnsweredAt(now);
            callRepo.save(call);
        }
        p.setState(CallParticipantState.JOINED);
        if (p.getJoinedAt() == null) p.setJoinedAt(now);
        p.setLeftAt(null);
        participantRepo.save(p);

        CallResponse resp = toResponse(call);
        broadcaster.broadcast(memberRepo.findActiveMemberIds(call.getConversationId()),
                event(ChatRealtimeEventType.CALL_ACCEPTED, resp, userId));
        return resp;
    }

    @Transactional
    public void decline(UUID callId, UUID userId) {
        CallSession call = requireActiveCall(callId);
        CallParticipant p = requireInvitee(call, userId);
        p.setState(CallParticipantState.DECLINED);
        participantRepo.save(p);

        boolean direct = isDirect(call);
        // A 1:1 decline ends the call; a group decline just drops that invitee.
        if (direct || !anyoneEngaged(call)) {
            endCall(call, CallStatus.DECLINED, "declined");
        } else {
            broadcaster.broadcast(memberRepo.findActiveMemberIds(call.getConversationId()),
                    event(ChatRealtimeEventType.CALL_DECLINED, toResponse(call), userId));
        }
    }

    @Transactional
    public void end(UUID callId, UUID userId) {
        CallSession call = callRepo.findById(callId)
                .orElseThrow(() -> new ResourceNotFoundException("Call", "id", callId));
        if (!call.isActive()) return; // idempotent
        participantRepo.findByCallIdAndUserId(callId, userId).ifPresent(p -> {
            p.setState(CallParticipantState.LEFT);
            p.setLeftAt(Instant.now());
            participantRepo.save(p);
        });

        boolean direct = isDirect(call);
        boolean initiatorCancelled = call.getStatus() == CallStatus.RINGING && userId.equals(call.getInitiatorId());
        if (direct || initiatorCancelled || !anyoneEngaged(call)) {
            endCall(call, initiatorCancelled ? CallStatus.CANCELLED : CallStatus.ENDED,
                    initiatorCancelled ? "cancelled" : "hung_up");
        } else {
            broadcaster.broadcast(memberRepo.findActiveMemberIds(call.getConversationId()),
                    event(ChatRealtimeEventType.CALL_PARTICIPANT, toResponse(call), userId));
        }
    }

    /** Relay a WebRTC OFFER/ANSWER/ICE frame to one peer in the call (blind relay). */
    @Transactional(readOnly = true)
    public void signal(UUID callId, UUID fromUserId, CallSignalRequest req) {
        CallSession call = requireActiveCall(callId);
        requireInvitee(call, fromUserId);
        participantRepo.findByCallIdAndUserId(callId, req.getToUserId())
                .orElseThrow(() -> new BadRequestException("Target is not part of this call."));
        CallSignalMessage msg = new CallSignalMessage(callId, fromUserId, req.getKind().name(), req.getPayload());
        broadcaster.broadcastTo(req.getToUserId(), ChatRealtimeEvent.builder()
                .eventType(ChatRealtimeEventType.CALL_SIGNAL)
                .conversationId(call.getConversationId())
                .signal(msg)
                .build());
    }

    @Transactional(readOnly = true)
    public CallResponse get(UUID callId, UUID userId) {
        CallSession call = callRepo.findById(callId)
                .orElseThrow(() -> new ResourceNotFoundException("Call", "id", callId));
        requireActiveMember(call.getConversationId(), userId); // must be a conversation member to view
        return toResponse(call);
    }

    // ── Ring-timeout sweep ─────────────────────────────────────────────────────

    /** Mark calls that rang out with no answer as MISSED (polls every 20s). */
    @Scheduled(fixedDelay = 20_000L)
    @Transactional
    public void sweepMissed() {
        Instant cutoff = Instant.now().minus(RING_TIMEOUT);
        for (CallSession call : callRepo.findByStatusAndStartedAtBefore(CallStatus.RINGING, cutoff)) {
            endCall(call, CallStatus.MISSED, "no_answer");
        }
    }

    // ── internals ──────────────────────────────────────────────────────────────

    private void endCall(CallSession call, CallStatus status, String reason) {
        call.setStatus(status);
        call.setEndedAt(Instant.now());
        call.setEndReason(reason);
        callRepo.save(call);
        broadcaster.broadcast(memberRepo.findActiveMemberIds(call.getConversationId()),
                event(ChatRealtimeEventType.CALL_ENDED, toResponse(call)));
    }

    private boolean anyoneEngaged(CallSession call) {
        return participantRepo.findByCallId(call.getId()).stream()
                .anyMatch(p -> p.getState() == CallParticipantState.JOINED);
    }

    private boolean isDirect(CallSession call) {
        Conversation c = conversationRepo.findById(call.getConversationId()).orElse(null);
        return c != null && c.isDirect();
    }

    private CallParticipant participant(UUID callId, UUID userId, CallParticipantState state, Instant joinedAt) {
        return CallParticipant.builder()
                .callId(callId).userId(userId).state(state).joinedAt(joinedAt)
                .build();
    }

    private CallSession requireActiveCall(UUID callId) {
        CallSession call = callRepo.findById(callId)
                .orElseThrow(() -> new ResourceNotFoundException("Call", "id", callId));
        if (!call.isActive()) throw new BadRequestException("This call is no longer active.");
        return call;
    }

    private CallParticipant requireInvitee(CallSession call, UUID userId) {
        return participantRepo.findByCallIdAndUserId(call.getId(), userId)
                .orElseThrow(() -> new ForbiddenException("You are not part of this call.", "NOT_A_MEMBER"));
    }

    private Conversation requireConversation(UUID conversationId) {
        return conversationRepo.findById(conversationId)
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", "id", conversationId));
    }

    private ConversationMember requireActiveMember(UUID conversationId, UUID userId) {
        return memberRepo.findMember(conversationId, userId)
                .filter(ConversationMember::isActive)
                .orElseThrow(() -> new ForbiddenException("You are not an active member of this conversation.", "NOT_A_MEMBER"));
    }

    private UUID otherDirectMember(UUID conversationId, UUID me) {
        return memberRepo.findAllByConversation(conversationId).stream()
                .map(m -> m.getId().getUserId())
                .filter(id -> !id.equals(me))
                .findFirst().orElse(null);
    }

    private CallResponse toResponse(CallSession call) {
        List<CallParticipantResponse> parts = participantRepo.findByCallId(call.getId()).stream()
                .map(p -> new CallParticipantResponse(p.getUserId(), p.getState().name(), p.getJoinedAt(), p.getLeftAt()))
                .toList();
        return new CallResponse(call.getId(), call.getConversationId(), call.getInitiatorId(),
                call.getType().name(), call.getStatus().name(), parts,
                call.getStartedAt(), call.getAnsweredAt(), call.getEndedAt());
    }

    private ChatRealtimeEvent event(ChatRealtimeEventType type, CallResponse call) {
        return event(type, call, null);
    }

    private ChatRealtimeEvent event(ChatRealtimeEventType type, CallResponse call, UUID userId) {
        return ChatRealtimeEvent.builder()
                .eventType(type)
                .conversationId(call == null ? null : call.conversationId())
                .call(call).userId(userId)
                .build();
    }
}

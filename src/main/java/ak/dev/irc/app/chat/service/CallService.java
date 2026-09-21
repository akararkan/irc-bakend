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
import ak.dev.irc.app.common.messages.ChannelStreamMessages;
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

    /** Statuses under which a call is considered active — mirrors {@link CallSession#isActive()}. */
    private static final List<CallStatus> ACTIVE_CALL_STATUSES = List.of(CallStatus.RINGING, CallStatus.ONGOING);

    private final CallSessionRepository callRepo;
    private final CallParticipantRepository participantRepo;
    private final ConversationRepository conversationRepo;
    private final ConversationMemberRepository memberRepo;
    private final ChatRelationshipService relationships;
    private final ChatRealtimeBroadcaster broadcaster;
    private final ChatNotificationService chatNotifications;
    private final CallSignalRouteCache routeCache;

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
                throw new ForbiddenException(
                        ChannelStreamMessages.CALL_BLOCKED_MSG, ChannelStreamMessages.BLOCKED);
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
        // Prime the signal route cache so even the first OFFER frame skips the DB.
        routeCache.primeAfterCommit(call.getId(), conversationId,
                ring.stream().map(CallParticipant::getUserId).toList());

        CallResponse resp = toResponse(call, ring);
        broadcaster.broadcastExcept(members, initiatorId, event(ChatRealtimeEventType.CALL_INCOMING, resp));
        // Ring the callees' phones too — SSE only reaches a foregrounded app.
        // Direct push (calls_v1 channel, deep link to the incoming screen),
        // async, and never allowed to fail the initiate.
        try {
            chatNotifications.notifyIncomingCall(members, initiatorId, conversationId,
                    call.getId(), type == CallType.VIDEO);
        } catch (Exception e) {
            log.debug("[CALL] incoming-call push skipped: {}", e.getMessage());
        }
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
        List<CallParticipant> parts = participantRepo.findByCallId(callId);
        if (direct || !anyoneEngaged(parts)) {
            endCall(call, CallStatus.DECLINED, "declined");
        } else {
            broadcaster.broadcast(memberRepo.findActiveMemberIds(call.getConversationId()),
                    event(ChatRealtimeEventType.CALL_DECLINED, toResponse(call, parts), userId));
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
        List<CallParticipant> parts = participantRepo.findByCallId(callId);
        if (direct || initiatorCancelled || !anyoneEngaged(parts)) {
            endCall(call, initiatorCancelled ? CallStatus.CANCELLED : CallStatus.ENDED,
                    initiatorCancelled ? "cancelled" : "hung_up");
        } else {
            broadcaster.broadcast(memberRepo.findActiveMemberIds(call.getConversationId()),
                    event(ChatRealtimeEventType.CALL_PARTICIPANT, toResponse(call, parts), userId));
        }
    }

    /** Relay a WebRTC OFFER/ANSWER/ICE frame to one peer in the call (blind relay).
     *  Hottest path in the calls feature (fires per SDP/ICE frame — dozens of
     *  times per call setup), so the steady state is pure in-memory: the route
     *  cache resolves conversation + participants with no transaction and no DB
     *  round-trip, and the Redis publish happens immediately (nothing to await
     *  a commit for). Deliberately NOT {@code @Transactional} — acquiring a
     *  connection per ICE frame is exactly the overhead this path avoids. */
    public void signal(UUID callId, UUID fromUserId, CallSignalRequest req) {
        UUID conversationId = resolveSignalRoute(callId, fromUserId, req.getToUserId());
        CallSignalMessage msg = new CallSignalMessage(callId, fromUserId, req.getKind().name(), req.getPayload());
        broadcaster.broadcastTo(req.getToUserId(), ChatRealtimeEvent.builder()
                .eventType(ChatRealtimeEventType.CALL_SIGNAL)
                .conversationId(conversationId)
                .signal(msg)
                .build());
    }

    /** Fast path: cached route (primed at initiate, TTL-refreshed here). Cache miss —
     *  first frame after a restart/TTL expiry, or an invalid signal — validates with
     *  one combined query, falling back to the granular checks purely to surface the
     *  correct specific exception, then re-primes the cache. A cached route whose
     *  participant set doesn't contain both users also takes the DB path, so a
     *  non-participant can never ride a warm cache. */
    private UUID resolveSignalRoute(UUID callId, UUID fromUserId, UUID toUserId) {
        CallSignalRouteCache.Route route = routeCache.get(callId);
        if (route != null
                && route.participantIds().contains(fromUserId)
                && route.participantIds().contains(toUserId)) {
            return route.conversationId();
        }
        UUID conversationId = callRepo.findConversationIdForActiveSignal(
                        callId, fromUserId, toUserId, ACTIVE_CALL_STATUSES)
                .orElseGet(() -> {
                    CallSession call = requireActiveCall(callId);
                    requireInvitee(call, fromUserId);
                    participantRepo.findByCallIdAndUserId(callId, toUserId)
                            .orElseThrow(() -> new BadRequestException(ChannelStreamMessages.CALL_TARGET_NOT_IN_CALL_MSG));
                    return call.getConversationId();
                });
        routeCache.put(callId, conversationId, participantRepo.findUserIdsByCallId(callId));
        return conversationId;
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
        // Immediate + safe: a rolled-back end just re-primes on the next signal miss.
        routeCache.invalidate(call.getId());
        List<CallParticipant> parts = participantRepo.findByCallId(call.getId());
        broadcaster.broadcast(memberRepo.findActiveMemberIds(call.getConversationId()),
                event(ChatRealtimeEventType.CALL_ENDED, toResponse(call, parts)));
        // A ring that ended unanswered — rang out (MISSED) or the caller hung up
        // while still ringing (CANCELLED) — leaves a "missed call" bell row for
        // every invitee who never engaged. DECLINED/ENDED invitees saw the call.
        if (status == CallStatus.MISSED || status == CallStatus.CANCELLED) {
            notifyMissedInvitees(call, parts);
        }
    }

    /** One CALL_MISSED bell per still-INVITED participant; aggregated per
     *  conversation downstream ("3 missed calls from @alice"). */
    private void notifyMissedInvitees(CallSession call, List<CallParticipant> parts) {
        boolean video = call.getType() == CallType.VIDEO;
        for (CallParticipant p : parts) {
            if (p.getState() == CallParticipantState.INVITED
                    && !p.getUserId().equals(call.getInitiatorId())) {
                try {
                    chatNotifications.notifyMissedCall(
                            p.getUserId(), call.getInitiatorId(), call.getConversationId(), video);
                } catch (Exception e) {
                    log.debug("[CALL] missed-call notify {} skipped: {}", p.getUserId(), e.getMessage());
                }
            }
        }
    }

    private static boolean anyoneEngaged(List<CallParticipant> parts) {
        return parts.stream().anyMatch(p -> p.getState() == CallParticipantState.JOINED);
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
        if (!call.isActive()) throw new BadRequestException(ChannelStreamMessages.CALL_NOT_ACTIVE_MSG);
        return call;
    }

    private CallParticipant requireInvitee(CallSession call, UUID userId) {
        return participantRepo.findByCallIdAndUserId(call.getId(), userId)
                .orElseThrow(() -> new ForbiddenException(
                        ChannelStreamMessages.NOT_IN_CALL_MSG, ChannelStreamMessages.NOT_A_MEMBER));
    }

    private Conversation requireConversation(UUID conversationId) {
        return conversationRepo.findById(conversationId)
                .filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation", "id", conversationId));
    }

    private ConversationMember requireActiveMember(UUID conversationId, UUID userId) {
        return memberRepo.findMember(conversationId, userId)
                .filter(ConversationMember::isActive)
                .orElseThrow(() -> new ForbiddenException(
                        ChannelStreamMessages.NOT_ACTIVE_CONVERSATION_MEMBER_MSG, ChannelStreamMessages.NOT_A_MEMBER));
    }

    private UUID otherDirectMember(UUID conversationId, UUID me) {
        return memberRepo.findAllByConversation(conversationId).stream()
                .map(m -> m.getId().getUserId())
                .filter(id -> !id.equals(me))
                .findFirst().orElse(null);
    }

    private CallResponse toResponse(CallSession call) {
        return toResponse(call, participantRepo.findByCallId(call.getId()));
    }

    private CallResponse toResponse(CallSession call, List<CallParticipant> participants) {
        List<CallParticipantResponse> parts = participants.stream()
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

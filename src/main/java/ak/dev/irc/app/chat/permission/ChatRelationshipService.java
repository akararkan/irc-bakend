package ak.dev.irc.app.chat.permission;

import ak.dev.irc.app.chat.entity.Conversation;
import ak.dev.irc.app.chat.entity.MessageRequest;
import ak.dev.irc.app.chat.enums.ConversationType;
import ak.dev.irc.app.chat.enums.MessageRequestStatus;
import ak.dev.irc.app.chat.repository.ConversationMemberRepository;
import ak.dev.irc.app.chat.repository.ConversationRepository;
import ak.dev.irc.app.chat.repository.MessageRequestRepository;
import ak.dev.irc.app.chat.util.DirectKeys;
import ak.dev.irc.app.common.service.SocialGuard;
import ak.dev.irc.app.user.repository.UserFollowRepository;
import ak.dev.irc.app.user.repository.UserRestrictionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Maps the platform's existing social graph (follow / block / restrict) onto the
 * four chat relationship states the send-permission engine needs. Keeping the
 * derivation in one place means that if "connected" ever changes (e.g. an
 * explicit friendship edge is added) only this class changes.
 *
 * <ul>
 *   <li><b>Connected</b> = mutual follow.</li>
 *   <li><b>Blocked</b>   = either side blocked the other (symmetric).</li>
 *   <li><b>Restricted</b>= recipient restricted the sender (asymmetric, silent).</li>
 *   <li><b>Accepted thread</b> = a DIRECT conversation exists and is not a still-pending request.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ChatRelationshipService {

    private final SocialGuard socialGuard;
    private final UserRestrictionRepository restrictionRepo;
    private final UserFollowRepository followRepo;
    private final ConversationRepository conversationRepo;
    private final ConversationMemberRepository memberRepo;
    private final MessageRequestRepository messageRequestRepo;

    @Transactional(readOnly = true)
    public boolean isBlockedEitherWay(UUID a, UUID b) {
        return socialGuard.isBlockedBetween(a, b);
    }

    /** Everyone in a block relation (either direction) with {@code userId} — one
     *  cached lookup, for filtering a whole candidate batch without a per-id query. */
    @Transactional(readOnly = true)
    public java.util.Set<UUID> blockedEitherWayIds(UUID userId) {
        return new java.util.HashSet<>(socialGuard.findRelatedBlockedIds(userId));
    }

    /** True when {@code recipient} has restricted {@code sender}. */
    @Transactional(readOnly = true)
    public boolean isRestrictedBy(UUID recipient, UUID sender) {
        return restrictionRepo.isRestricting(recipient, sender);
    }

    /** Connected = mutual follow. */
    @Transactional(readOnly = true)
    public boolean isConnected(UUID a, UUID b) {
        if (a == null || b == null || a.equals(b)) return false;
        return followRepo.isFollowing(a, b) && followRepo.isFollowing(b, a);
    }

    /**
     * Whether ephemeral signals (typing, read/delivered receipts) must be
     * <b>suppressed</b> for a message from {@code actorId} in this conversation —
     * so the other party never learns the actor is around before the relationship
     * is settled. True when a request is still pending, or (for a DIRECT thread) a
     * restrict relationship exists in either direction.
     *
     * <p>Short-TTL cached: this gate runs on every typing ping and every
     * delivered/read receipt (keystroke cadence) and costs ~5 relational queries
     * cold. A few seconds of staleness on an ephemeral signal is invisible; the
     * query collapse is not.</p>
     */
    @Transactional(readOnly = true)
    @org.springframework.cache.annotation.Cacheable(value = "chat-suppress-ephemeral",
            key = "#conversationId + ':' + #actorId")
    public boolean suppressEphemeral(UUID conversationId, UUID actorId) {
        MessageRequest req = messageRequestRepo.findByConversationId(conversationId).orElse(null);
        if (req != null && req.getStatus() == MessageRequestStatus.PENDING) return true;

        Conversation c = conversationRepo.findById(conversationId).orElse(null);
        if (c != null && c.getType() == ConversationType.DIRECT) {
            UUID peer = memberRepo.findAllByConversation(conversationId).stream()
                    .map(m -> m.getId().getUserId())
                    .filter(id -> !id.equals(actorId))
                    .findFirst().orElse(null);
            if (peer != null && (isRestrictedBy(peer, actorId) || isRestrictedBy(actorId, peer))) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when a normal (graduated) DIRECT thread already exists between the two
     * users — a conversation with either no request row or an ACCEPTED one. A
     * still-PENDING (or declined/blocked) request is not an accepted thread.
     */
    @Transactional(readOnly = true)
    public boolean hasAcceptedThread(UUID a, UUID b) {
        Conversation convo = conversationRepo.findByDirectKey(DirectKeys.of(a, b)).orElse(null);
        if (convo == null) return false;
        MessageRequest req = messageRequestRepo.findByConversationId(convo.getId()).orElse(null);
        if (req != null) return req.getStatus() == MessageRequestStatus.ACCEPTED;
        // No request row: only an ESTABLISHED thread (one with actual history)
        // counts as accepted. A bare conversation row must not — anyone may create
        // a DIRECT conversation, and counting it as accepted would let a stranger
        // bypass the whole message-request quarantine (and its pre-accept cap)
        // simply by creating the thread before their first send.
        return convo.getLastMessageId() != null;
    }
}

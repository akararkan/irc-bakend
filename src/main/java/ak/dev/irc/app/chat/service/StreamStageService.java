package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.dto.request.SendGiftRequest;
import ak.dev.irc.app.chat.dto.request.StreamReactionRequest;
import ak.dev.irc.app.chat.dto.response.*;
import ak.dev.irc.app.chat.entity.LiveStream;
import ak.dev.irc.app.chat.entity.StreamGiftTally;
import ak.dev.irc.app.chat.entity.StreamGuest;
import ak.dev.irc.app.chat.enums.LiveStreamStatus;
import ak.dev.irc.app.chat.enums.StreamGift;
import ak.dev.irc.app.chat.enums.StreamGuestStatus;
import ak.dev.irc.app.chat.enums.StreamReactionType;
import ak.dev.irc.app.chat.realtime.ChatRealtimeBroadcaster;
import ak.dev.irc.app.chat.realtime.ChatRealtimeEvent;
import ak.dev.irc.app.chat.realtime.ChatRealtimeEventType;
import ak.dev.irc.app.chat.repository.LiveStreamRepository;
import ak.dev.irc.app.chat.repository.StreamGiftTallyRepository;
import ak.dev.irc.app.chat.repository.StreamGuestRepository;
import ak.dev.irc.app.chat.repository.StreamViewerRepository;
import ak.dev.irc.app.common.cache.RateLimiter;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The TikTok-style <b>multi-guest stage</b> for a live stream, plus its live
 * <b>reactions</b> and <b>gifts</b> — all over the same per-user SSE stream the
 * rest of chat and live streaming ride.
 *
 * <h2>The stage</h2>
 * The host runs the broadcast (see {@link LiveStreamService}); this service lets
 * the host bring viewers "up" to talk beside them. A viewer <b>raises their hand</b>
 * ({@code requestUp}) or the host <b>invites</b> them ({@code invite}); once
 * {@code ACTIVE} the guest gets their <b>own</b> media path and publishes their
 * camera to it — so a live stream becomes several publishers, and everyone
 * subscribes to every active guest. The host can {@code mute}/{@code unmute} or
 * {@code removeGuest}; guests can {@code leaveStage} themselves. The stage is
 * capped ({@code app.streaming.stage.max-guests}, default 6).
 *
 * <h2>Mute is authoritative</h2>
 * A host mute is a state on the guest row that rides every {@code stream.stage}
 * roster broadcast — every client (viewers, the guest, other guests) mutes that
 * guest's audio locally, so a muted guest is silent for <b>everyone</b>, not just
 * the host. There is no per-track server-side mute at this layer; the roster is the
 * single source of truth and {@code removeGuest} is the hard stop (it also kicks
 * the guest's media session).
 *
 * <h2>Credentials</h2>
 * A guest's secret publish credential ({@code whipUrl} + {@code publishKey}) is
 * delivered <b>only to that guest</b> — in the {@code accept} response and the
 * private {@code stream.stage.grant} frame — never in the roster everyone sees.
 * The auth hook ({@link LiveStreamService#authorizeMediaAccess}) authorizes a guest
 * publish only for an {@code ACTIVE} guest presenting the matching key.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamStageService {

    private final LiveStreamRepository streamRepo;
    private final StreamGuestRepository guestRepo;
    private final StreamGiftTallyRepository giftRepo;
    private final StreamViewerRepository viewerRepo;
    private final ChatRealtimeBroadcaster broadcaster;
    private final UserRepository userRepository;
    private final RateLimiter rateLimiter;
    private final MediaControlClient mediaControl;

    @Value("${app.streaming.webrtc-base:http://localhost:8889}")
    private String webrtcBase;

    /** How many guests may be up at once (the host is always on, not counted). */
    @Value("${app.streaming.stage.max-guests:6}")
    private int maxGuests;

    // ── Stage: read ──────────────────────────────────────────────────────────────

    /** The current stage roster (host + active guests). Anyone may read it. Two
     *  queries: the active guests, then their identities (host + guests) in one
     *  join-fetched batch. */
    @Transactional(readOnly = true)
    public StageState stage(UUID streamId, UUID userId) {
        LiveStream s = requireStream(streamId);
        List<StreamGuest> active = activeGuests(s.getId());
        return stageStateOf(s, active, stageUsers(s, active));
    }

    /** The host's pending hand-raise queue (viewers waiting to come up). */
    @Transactional(readOnly = true)
    public List<StageMember> pendingRequests(UUID streamId, UUID hostId) {
        LiveStream s = requireOwnedStream(streamId, hostId);
        List<StreamGuest> reqs = guestRepo.findByStreamIdAndStatusOrderByRequestedAtAsc(
                s.getId(), StreamGuestStatus.REQUESTED);
        Map<UUID, User> users = usersById(reqs.stream().map(StreamGuest::getUserId).toList());
        return reqs.stream()
                .map(g -> guestMember(s, g, users.get(g.getUserId()), false))
                .collect(Collectors.toList());
    }

    // ── Stage: viewer-driven ─────────────────────────────────────────────────────

    /** A viewer raises their hand to come up. The host is notified
     *  ({@code stream.stage.request}) and may {@code approve}/{@code deny}. */
    @Transactional
    public StageMember requestUp(UUID streamId, UUID userId) {
        LiveStream s = requireLive(streamId);
        if (s.getHostId().equals(userId)) throw new BadRequestException("You are the host of this stream.");
        if (!viewerRepo.existsByStreamIdAndUserIdAndActiveTrue(streamId, userId)) {
            throw new ForbiddenException("Join the stream before asking to come up.", "NOT_A_MEMBER");
        }
        rateLimiter.check("stream-stage-request", userId, 5, Duration.ofSeconds(30));

        StreamGuest g = guestRepo.findByStreamIdAndUserId(streamId, userId).orElseGet(() ->
                StreamGuest.builder().streamId(streamId).userId(userId).build());
        if (g.getStatus() == StreamGuestStatus.ACTIVE) {
            throw new BadRequestException("You are already on stage.");
        }
        g.setStatus(StreamGuestStatus.REQUESTED);
        g.setRequestedAt(Instant.now());
        g.setPublishPath(null);
        g.setPublishKey(null);
        g.setMuted(false);
        guestRepo.save(g);

        // Tell the host only — this is their moderation queue, not the audience's.
        User requester = loadUser(userId);
        StageMember self = guestMember(s, g, requester, false);
        broadcaster.broadcastTo(s.getHostId(), event(ChatRealtimeEventType.STREAM_STAGE_REQUEST)
                .stageMember(self).userId(userId).build());
        return self;
    }

    /** An invited viewer accepts and comes up. Returns their OWN member frame WITH
     *  publish credentials so they can start their camera straight away. */
    @Transactional
    public StageMember accept(UUID streamId, UUID userId) {
        LiveStream s = requireLive(streamId);
        StreamGuest g = guestRepo.findByStreamIdAndUserId(streamId, userId)
                .filter(x -> x.getStatus() == StreamGuestStatus.INVITED)
                .orElseThrow(() -> new BadRequestException("You have no pending invite to come up."));
        promoteToStage(s, g);
        guestRepo.save(g);
        Map<UUID, User> users = broadcastRoster(s); // reuse its user map — no extra load
        return grantMember(s, g, users.get(userId)); // creds included — the guest called this themselves
    }

    /** An invited viewer declines. */
    @Transactional
    public void declineInvite(UUID streamId, UUID userId) {
        StreamGuest g = guestRepo.findByStreamIdAndUserId(streamId, userId).orElse(null);
        if (g == null || g.getStatus() != StreamGuestStatus.INVITED) return; // idempotent
        g.setStatus(StreamGuestStatus.DECLINED);
        guestRepo.save(g);
    }

    /** A guest steps down off the stage themselves. */
    @Transactional
    public void leaveStage(UUID streamId, UUID userId) {
        LiveStream s = streamRepo.findById(streamId).orElse(null);
        if (s == null) return;
        StreamGuest g = guestRepo.findByStreamIdAndUserId(streamId, userId).orElse(null);
        if (g == null || g.getStatus() != StreamGuestStatus.ACTIVE) return; // idempotent
        takeDown(g);
        guestRepo.save(g);
        broadcastRoster(s);
    }

    // ── Stage: host-driven ───────────────────────────────────────────────────────

    /** The host approves a hand-raise: the viewer comes up on stage. */
    @Transactional
    public StageMember approve(UUID streamId, UUID hostId, UUID guestUserId) {
        LiveStream s = requireOwnedLive(streamId, hostId);
        StreamGuest g = guestRepo.findByStreamIdAndUserId(streamId, guestUserId)
                .filter(x -> x.getStatus() == StreamGuestStatus.REQUESTED
                          || x.getStatus() == StreamGuestStatus.INVITED)
                .orElseThrow(() -> new BadRequestException("No pending request from this user."));
        promoteToStage(s, g);
        guestRepo.save(g);
        Map<UUID, User> users = broadcastRoster(s);   // one user map, reused twice below
        User gu = users.get(guestUserId);
        // The guest didn't make an HTTP call, so push them their private credentials.
        broadcaster.broadcastTo(guestUserId, event(ChatRealtimeEventType.STREAM_STAGE_GRANT)
                .stageMember(grantMember(s, g, gu)).userId(guestUserId).build());
        return guestMember(s, g, gu, false); // host's response never carries the guest's key
    }

    /** The host denies a hand-raise. */
    @Transactional
    public void deny(UUID streamId, UUID hostId, UUID guestUserId) {
        LiveStream s = requireOwnedStream(streamId, hostId);
        StreamGuest g = guestRepo.findByStreamIdAndUserId(streamId, guestUserId).orElse(null);
        if (g == null || g.getStatus() != StreamGuestStatus.REQUESTED) return; // idempotent
        g.setStatus(StreamGuestStatus.DECLINED);
        guestRepo.save(g);
        // Let the requester's "waiting to come up" UI clear. Only userId + status
        // are read on the client, so no identity load is needed (null user).
        broadcaster.broadcastTo(guestUserId, event(ChatRealtimeEventType.STREAM_STAGE_GRANT)
                .stageMember(guestMember(s, g, null, false)).userId(guestUserId).build());
    }

    /** The host invites a viewer up. They are notified ({@code stream.stage.invite})
     *  and may {@code accept}/{@code declineInvite}. */
    @Transactional
    public void invite(UUID streamId, UUID hostId, UUID guestUserId) {
        LiveStream s = requireOwnedLive(streamId, hostId);
        if (guestUserId.equals(hostId)) throw new BadRequestException("You are the host of this stream.");
        userRepository.findById(guestUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", guestUserId));

        StreamGuest g = guestRepo.findByStreamIdAndUserId(streamId, guestUserId).orElseGet(() ->
                StreamGuest.builder().streamId(streamId).userId(guestUserId).build());
        if (g.getStatus() == StreamGuestStatus.ACTIVE) throw new BadRequestException("They are already on stage.");
        g.setStatus(StreamGuestStatus.INVITED);
        g.setRequestedAt(Instant.now());
        guestRepo.save(g);

        // Carry the host's identity so the invitee can render "@host invited you up".
        User host = loadUser(hostId);
        broadcaster.broadcastTo(guestUserId, event(ChatRealtimeEventType.STREAM_STAGE_INVITE)
                .stageMember(hostMember(s, host)).userId(hostId).build());
    }

    /** The host takes a guest off the stage. Hard stop: credentials revoked, the
     *  guest's media session kicked, the roster and the guest both told. */
    @Transactional
    public void removeGuest(UUID streamId, UUID hostId, UUID guestUserId) {
        LiveStream s = requireOwnedStream(streamId, hostId);
        StreamGuest g = guestRepo.findByStreamIdAndUserId(streamId, guestUserId).orElse(null);
        if (g == null || g.getStatus() != StreamGuestStatus.ACTIVE) return; // idempotent
        String path = g.getPublishPath();
        takeDown(g);
        guestRepo.save(g);
        if (path != null) mediaControl.kickPublisher(path); // best-effort hard drop
        broadcastRoster(s);
        // Tell the removed guest to tear their publisher down. Only userId + status
        // are read on the client, so no identity load is needed (null user).
        broadcaster.broadcastTo(guestUserId, event(ChatRealtimeEventType.STREAM_STAGE_GRANT)
                .stageMember(guestMember(s, g, null, false)).userId(guestUserId).build());
    }

    /** The host mutes or unmutes a guest. Authoritative — every client enforces it. */
    @Transactional
    public void setMuted(UUID streamId, UUID hostId, UUID guestUserId, boolean muted) {
        LiveStream s = requireOwnedStream(streamId, hostId);
        StreamGuest g = guestRepo.findByStreamIdAndUserId(streamId, guestUserId).orElse(null);
        if (g == null || g.getStatus() != StreamGuestStatus.ACTIVE) {
            throw new BadRequestException("That user is not on stage.");
        }
        if (g.isMuted() == muted) return; // no-op, no needless broadcast
        g.setMuted(muted);
        guestRepo.save(g);
        broadcastRoster(s);
    }

    // ── Reactions (ephemeral) ────────────────────────────────────────────────────

    /** Tap a floating reaction. Broadcast to everyone watching; never stored. */
    @Transactional(readOnly = true)
    public void react(UUID streamId, UUID userId, StreamReactionRequest req) {
        LiveStream s = requireLive(streamId);
        rateLimiter.check("stream-react", userId, 30, Duration.ofSeconds(10));
        // One audience build serves BOTH the permission check and the recipient set:
        // if you're not in it (not host / viewer / active guest) you're not watching.
        Set<UUID> audience = audienceSet(s, activeGuests(streamId));
        requireInAudience(audience, userId);
        StreamReactionType type = parseReaction(req == null ? null : req.getType());
        StreamReaction r = new StreamReaction(streamId, userId, type.name(), Instant.now());
        broadcaster.broadcast(audience, event(ChatRealtimeEventType.STREAM_REACTION)
                .streamReaction(r).userId(userId).build());
    }

    // ── Gifts (symbolic — no wallet, no money) ───────────────────────────────────

    /** The gift catalogue for the picker. */
    @Transactional(readOnly = true)
    public List<GiftCatalogEntry> giftCatalog() {
        return Arrays.stream(StreamGift.values())
                .map(g -> new GiftCatalogEntry(g.name(), g.displayName(), g.iconKey(), g.coins()))
                .collect(Collectors.toList());
    }

    /** Send a symbolic gift. Broadcasts the animation and bumps the sender's
     *  running coin total (the "top supporters" leaderboard). */
    @Transactional
    public StreamGiftEvent sendGift(UUID streamId, UUID userId, SendGiftRequest req) {
        LiveStream s = requireLive(streamId);
        rateLimiter.check("stream-gift", userId, 10, Duration.ofSeconds(10));
        Set<UUID> audience = audienceSet(s, activeGuests(streamId)); // check + recipients in one build
        requireInAudience(audience, userId);
        StreamGift gift = parseGift(req == null ? null : req.getGiftId());

        // Bump the sender's running total. The increment is ATOMIC in the DB
        // (coins = coins + delta), so concurrent gifts from the same sender can't
        // lose an update; 0 rows updated ⇒ their first gift, so insert the row.
        Instant now = Instant.now();
        int updated = giftRepo.addCoins(streamId, userId, gift.coins(), now);
        long senderTotal;
        if (updated == 0) {
            giftRepo.save(StreamGiftTally.builder().streamId(streamId).userId(userId)
                    .coins(gift.coins()).giftCount(1).lastGiftAt(now).build());
            senderTotal = gift.coins();
        } else {
            // Read back the authoritative post-increment total (one indexed lookup).
            senderTotal = giftRepo.findByStreamIdAndUserId(streamId, userId)
                    .map(StreamGiftTally::getCoins).orElse((long) gift.coins());
        }

        User sender = loadUser(userId);
        StreamGiftEvent ev = new StreamGiftEvent(
                streamId, userId,
                sender != null ? sender.getUsername() : "user",
                sender != null ? sender.getProfileImage() : null,
                gift.name(), gift.displayName(), gift.iconKey(), gift.coins(),
                senderTotal, now);
        broadcaster.broadcast(audience, event(ChatRealtimeEventType.STREAM_GIFT)
                .streamGift(ev).userId(userId).build());
        return ev;
    }

    /** The stream's "top supporters" — biggest symbolic gifters first. */
    @Transactional(readOnly = true)
    public List<GiftSupporter> topSupporters(UUID streamId, int limit) {
        int n = Math.max(1, Math.min(limit, 50));
        List<StreamGiftTally> rows = giftRepo.findByStreamIdOrderByCoinsDescLastGiftAtDesc(
                streamId, PageRequest.of(0, n));
        Map<UUID, User> users = usersById(rows.stream().map(StreamGiftTally::getUserId).toList());
        return rows.stream().map(t -> {
            User u = users.get(t.getUserId());
            return new GiftSupporter(t.getUserId(),
                    u != null ? u.getUsername() : null,
                    u != null ? u.getFullName() : null,
                    u != null ? u.getProfileImage() : null,
                    t.getCoins(), t.getGiftCount());
        }).collect(Collectors.toList());
    }

    // ── internals ────────────────────────────────────────────────────────────────

    /** Promote a REQUESTED/INVITED guest to ACTIVE, minting their own media path
     *  + secret key. Enforces the stage cap. */
    private void promoteToStage(LiveStream s, StreamGuest g) {
        // Best-effort cap. Two approvals/accepts for DIFFERENT users racing can each
        // read maxGuests-1 and both promote, briefly overshooting by one — harmless
        // (a 7th tile until someone steps down). A hard guarantee would need a row
        // lock on the stream; not worth it for a moderator-driven flow at this scale.
        long active = guestRepo.countByStreamIdAndStatus(s.getId(), StreamGuestStatus.ACTIVE);
        if (active >= maxGuests) {
            throw new BadRequestException("The stage is full (" + maxGuests + " guests).");
        }
        g.setStatus(StreamGuestStatus.ACTIVE);
        g.setPublishPath(mintId());
        g.setPublishKey(mintId());
        g.setMuted(false);
        g.setJoinedAt(Instant.now());
        g.setLeftAt(null);
    }

    /** Move an ACTIVE guest off the stage and revoke their credentials. */
    private void takeDown(StreamGuest g) {
        g.setStatus(StreamGuestStatus.REMOVED);
        g.setPublishPath(null);
        g.setPublishKey(null);
        g.setMuted(false);
        g.setLeftAt(Instant.now());
    }

    /** The active guests, oldest-first (stable stage order). One indexed query
     *  (idx_stream_guest_status); the stage is tiny (≤ maxGuests rows). */
    private List<StreamGuest> activeGuests(UUID streamId) {
        return guestRepo.findByStreamIdAndStatusOrderByJoinedAtAsc(streamId, StreamGuestStatus.ACTIVE);
    }

    /** Everyone who should receive stage / reaction / gift frames: active viewers +
     *  the host + the active guests (a guest is on stage even if never a plain
     *  viewer). Deduplicated. Returned as a Set so a membership test is O(1) and the
     *  broadcaster (which takes a Collection) fans out over it directly — no copy. */
    private Set<UUID> audienceSet(LiveStream s, List<StreamGuest> active) {
        Set<UUID> set = new LinkedHashSet<>(viewerRepo.findActiveViewerIds(s.getId()));
        set.add(s.getHostId());
        active.forEach(g -> set.add(g.getUserId()));
        return set;
    }

    /** Host + every active guest's identity in ONE profile-join-fetched query
     *  (avatars populated, no N+1). */
    private Map<UUID, User> stageUsers(LiveStream s, List<StreamGuest> active) {
        List<UUID> ids = new ArrayList<>(active.size() + 1);
        ids.add(s.getHostId());
        active.forEach(g -> ids.add(g.getUserId()));
        return usersById(ids);
    }

    /** Build the roster from already-fetched guests + users — no queries. */
    private StageState stageStateOf(LiveStream s, List<StreamGuest> active, Map<UUID, User> users) {
        List<StageMember> members = new ArrayList<>(active.size() + 1);
        members.add(hostMember(s, users.get(s.getHostId())));
        for (StreamGuest g : active) members.add(guestMember(s, g, users.get(g.getUserId()), false));
        return new StageState(s.getId(), s.getHostId(), members, active.size(), maxGuests);
    }

    /**
     * Broadcast the current roster to everyone watching, and return the fetched
     * user map so a caller can build one guest's member frame WITHOUT another query.
     * Exactly three queries total (active guests, their identities, the viewers) —
     * the active-guest list is fetched once and shared by the audience and the roster
     * (it used to be fetched twice).
     */
    private Map<UUID, User> broadcastRoster(LiveStream s) {
        List<StreamGuest> active = activeGuests(s.getId());
        Map<UUID, User> users = stageUsers(s, active);
        broadcaster.broadcast(audienceSet(s, active),
                event(ChatRealtimeEventType.STREAM_STAGE).stage(stageStateOf(s, active, users)).build());
        return users;
    }

    // ── member builders ──────────────────────────────────────────────────────────

    private StageMember hostMember(LiveStream s, User host) {
        return new StageMember(s.getId(), s.getHostId(),
                host != null ? host.getUsername() : null,
                host != null ? host.getFullName() : null,
                host != null ? host.getProfileImage() : null,
                "HOST", StreamGuestStatus.ACTIVE.name(), false,
                webrtcBase + "/" + s.getId() + "/whep",  // host's camera is the stream path
                null, null, s.getStartedAt());
    }

    /** Private guest view — carries the secret publish credentials. Delivered only
     *  to the guest themselves. {@code u} is the guest's already-fetched identity. */
    private StageMember grantMember(LiveStream s, StreamGuest g, User u) {
        return guestMember(s, g, u, true);
    }

    private StageMember guestMember(LiveStream s, StreamGuest g, User u, boolean withCreds) {
        String path = g.getPublishPath();
        String whep = path != null ? webrtcBase + "/" + path + "/whep" : null;
        String whip = (withCreds && path != null)
                ? webrtcBase + "/" + path + "/whip?pass=" + g.getPublishKey() : null;
        String key  = withCreds ? g.getPublishKey() : null;
        return new StageMember(s.getId(), g.getUserId(),
                u != null ? u.getUsername() : null,
                u != null ? u.getFullName() : null,
                u != null ? u.getProfileImage() : null,
                "GUEST", g.getStatus().name(), g.isMuted(),
                whep, whip, key, g.getJoinedAt());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private ChatRealtimeEvent.ChatRealtimeEventBuilder event(ChatRealtimeEventType type) {
        return ChatRealtimeEvent.builder().eventType(type);
    }

    /** Load users WITH their profile join-fetched (one query, no N+1) so every
     *  member's avatar (User.getProfileImage() → profile.avatarUrl, a LAZY field)
     *  is populated — matching how post/notification authors resolve avatars. A
     *  bare findById would leave the roster's avatars null the moment the session
     *  closes (the live-row P3 symptom). */
    private Map<UUID, User> usersById(Collection<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return userRepository.findActiveWithProfileByIdIn(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));
    }

    /** Single user with the profile join-fetched (avatar populated); null if gone. */
    private User loadUser(UUID id) {
        return userRepository.findActiveWithProfileByIdIn(List.of(id)).stream().findFirst().orElse(null);
    }

    private static String mintId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static StreamReactionType parseReaction(String raw) {
        if (raw == null || raw.isBlank()) return StreamReactionType.LIKE;
        try {
            return StreamReactionType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            return StreamReactionType.LIKE; // unknown tap degrades to a heart
        }
    }

    private static StreamGift parseGift(String raw) {
        if (raw == null || raw.isBlank()) throw new BadRequestException("A gift id is required.");
        try {
            return StreamGift.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            throw new BadRequestException("Unknown gift '" + raw + "'.");
        }
    }

    private LiveStream requireStream(UUID id) {
        return streamRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("LiveStream", "id", id));
    }

    private LiveStream requireLive(UUID id) {
        LiveStream s = requireStream(id);
        if (s.getStatus() != LiveStreamStatus.LIVE) throw new BadRequestException("This stream is not live.");
        return s;
    }

    private LiveStream requireOwnedStream(UUID streamId, UUID hostId) {
        LiveStream s = requireStream(streamId);
        if (!s.getHostId().equals(hostId)) {
            throw new ForbiddenException("Only the host can manage this stream's stage.", "ACCESS_FORBIDDEN");
        }
        return s;
    }

    private LiveStream requireOwnedLive(UUID streamId, UUID hostId) {
        LiveStream s = requireOwnedStream(streamId, hostId);
        if (s.getStatus() != LiveStreamStatus.LIVE) throw new BadRequestException("This stream is not live.");
        return s;
    }

    /** Being in the audience (host, an active viewer, or an active guest) IS the
     *  definition of "watching" — so the recipient set doubles as the permission
     *  gate for reactions/gifts, with no extra query. O(1) membership on the Set. */
    private void requireInAudience(Set<UUID> audience, UUID userId) {
        if (!audience.contains(userId)) {
            throw new ForbiddenException("Join the stream first.", "NOT_A_MEMBER");
        }
    }
}

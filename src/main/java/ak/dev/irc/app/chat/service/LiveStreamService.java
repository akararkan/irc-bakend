package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.dto.request.LiveChatRequest;
import ak.dev.irc.app.chat.dto.request.StartStreamRequest;
import ak.dev.irc.app.chat.dto.response.LiveChatMessage;
import ak.dev.irc.app.chat.dto.response.LiveStreamResponse;
import ak.dev.irc.app.chat.entity.LiveStream;
import ak.dev.irc.app.chat.entity.StreamViewer;
import ak.dev.irc.app.chat.enums.LiveStreamStatus;
import ak.dev.irc.app.chat.realtime.ChatRealtimeBroadcaster;
import ak.dev.irc.app.chat.realtime.ChatRealtimeEvent;
import ak.dev.irc.app.chat.realtime.ChatRealtimeEventType;
import ak.dev.irc.app.chat.repository.LiveStreamRepository;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Live streaming control plane: go-live / end, a live viewer registry (drives the
 * viewer count + presence), discovery, and ephemeral live chat — all over the
 * existing per-user SSE stream. The audio/video is ingested to and served from an
 * external media server addressed by the stream key; the media never flows through
 * this app.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveStreamService {

    private final LiveStreamRepository streamRepo;
    private final StreamViewerRepository viewerRepo;
    private final ChatRealtimeBroadcaster broadcaster;
    private final UserRepository userRepository;
    private final RateLimiter rateLimiter;

    /** External media-server endpoints. Defaults are placeholders — point these at
     *  your RTMP/WebRTC ingest and HLS/WebRTC playback origins. */
    @Value("${app.streaming.ingest-base:rtmp://localhost:1935}")
    private String ingestBase;
    @Value("${app.streaming.playback-base:http://localhost:8888}")
    private String playbackBase;

    /** Web origin the share links point at (frontend routes /live/{id}). */
    @Value("${irc.base-url:https://irc.example.com}")
    private String baseUrl;

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Transactional
    public LiveStreamResponse start(UUID hostId, StartStreamRequest req) {
        if (!StringUtils.hasText(req.getTitle())) throw new BadRequestException("A stream requires a title.");
        LiveStream s = streamRepo.save(LiveStream.builder()
                .hostId(hostId)
                .title(req.getTitle().trim())
                .description(StringUtils.hasText(req.getDescription()) ? req.getDescription().trim() : null)
                .status(LiveStreamStatus.LIVE)
                .streamKey(UUID.randomUUID().toString().replace("-", ""))
                .viewerCount(0).peakViewerCount(0)
                .startedAt(Instant.now())
                .build());
        return toResponse(s, true); // host gets the ingest URL
    }

    @Transactional
    public void end(UUID streamId, UUID hostId) {
        LiveStream s = requireStream(streamId);
        if (!s.getHostId().equals(hostId)) {
            throw new ForbiddenException("Only the host can end this stream.", "ACCESS_FORBIDDEN");
        }
        if (s.getStatus() == LiveStreamStatus.ENDED) return; // idempotent
        List<UUID> audience = withHost(activeViewerIds(streamId), s.getHostId());
        s.setStatus(LiveStreamStatus.ENDED);
        s.setEndedAt(Instant.now());
        s.setViewerCount(0);
        streamRepo.save(s);
        viewerRepo.deactivateAll(streamId);
        broadcaster.broadcast(audience, ChatRealtimeEvent.builder()
                .eventType(ChatRealtimeEventType.STREAM_ENDED).stream(toResponse(s, false)).build());
    }

    // ── Viewers ────────────────────────────────────────────────────────────────

    @Transactional
    public LiveStreamResponse join(UUID streamId, UUID userId) {
        LiveStream s = requireLive(streamId);
        StreamViewer v = viewerRepo.findByStreamIdAndUserId(streamId, userId).orElse(null);
        boolean nowActive = (v == null) || !v.isActive();
        if (v == null) {
            v = StreamViewer.builder().streamId(streamId).userId(userId).active(true).joinedAt(Instant.now()).build();
        } else {
            v.setActive(true);
            v.setJoinedAt(Instant.now());
            v.setLeftAt(null);
        }
        viewerRepo.save(v);
        if (nowActive) {
            // One id-list read serves both the new count and the broadcast
            // recipients (was a COUNT query + a second id-list query).
            List<UUID> viewers = activeViewerIds(streamId);
            int count = viewers.size();
            s.setViewerCount(count);
            if (count > s.getPeakViewerCount()) s.setPeakViewerCount(count);
            streamRepo.save(s);
            broadcastViewer(s, viewers, userId, "JOINED");
        }
        return toResponse(s, s.getHostId().equals(userId));
    }

    @Transactional
    public void leave(UUID streamId, UUID userId) {
        LiveStream s = streamRepo.findById(streamId).orElse(null);
        if (s == null) return;
        StreamViewer v = viewerRepo.findByStreamIdAndUserId(streamId, userId).orElse(null);
        if (v == null || !v.isActive()) return; // idempotent
        v.setActive(false);
        v.setLeftAt(Instant.now());
        viewerRepo.save(v);
        List<UUID> viewers = activeViewerIds(streamId); // post-save: excludes the leaver
        if (s.getStatus() == LiveStreamStatus.LIVE) {
            s.setViewerCount(viewers.size());
            streamRepo.save(s);
        }
        broadcastViewer(s, viewers, userId, "LEFT");
    }

    // ── Live chat (ephemeral) ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public void chat(UUID streamId, UUID userId, LiveChatRequest req) {
        if (!StringUtils.hasText(req.getText())) throw new BadRequestException("Message text is required.");
        rateLimiter.check("stream-chat", userId, 20, Duration.ofSeconds(10));
        LiveStream s = requireLive(streamId);
        boolean host = s.getHostId().equals(userId);
        if (!host && !viewerRepo.existsByStreamIdAndUserIdAndActiveTrue(streamId, userId)) {
            throw new ForbiddenException("Join the stream before chatting.", "NOT_A_MEMBER");
        }
        String username = userRepository.findById(userId).map(User::getUsername).orElse("user");
        String text = req.getText().length() <= 500 ? req.getText() : req.getText().substring(0, 500);
        LiveChatMessage msg = new LiveChatMessage(streamId, userId, username, text, Instant.now());
        broadcaster.broadcast(withHost(activeViewerIds(streamId), s.getHostId()),
                ChatRealtimeEvent.builder().eventType(ChatRealtimeEventType.STREAM_CHAT).streamChat(msg).build());
    }

    // ── Read ──────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<LiveStreamResponse> listLive() {
        return streamRepo.findByStatusOrderByViewerCountDesc(LiveStreamStatus.LIVE).stream()
                .map(s -> toResponse(s, false)).toList();
    }

    @Transactional(readOnly = true)
    public LiveStreamResponse get(UUID streamId, UUID userId) {
        LiveStream s = requireStream(streamId);
        return toResponse(s, s.getHostId().equals(userId));
    }

    // ── Media-server authorization ───────────────────────────────────────────────

    /**
     * Called by {@code MediaAuthController} for every publish/read the media server
     * (MediaMTX) attempts. The media path is the stream id.
     * <ul>
     *   <li><b>publish</b> — allowed only for a LIVE stream when the caller presents
     *       the stream's secret {@code streamKey} (keeps the ingest host-only).</li>
     *   <li><b>read / playback</b> — public HLS playback: allowed for any LIVE stream.</li>
     * </ul>
     * Any unknown path, ended stream, wrong key, or unexpected action is denied.
     */
    @Transactional(readOnly = true)
    public boolean authorizeMediaAccess(String action, String path, String password, String query) {
        LiveStream s = pathToStream(path);
        if (s == null) return false;
        boolean live = s.getStatus() == LiveStreamStatus.LIVE;
        return switch (action == null ? "" : action) {
            case "publish" -> live && streamKeyMatches(s, password, query);
            case "read", "playback" -> live;
            default -> false;
        };
    }

    private LiveStream pathToStream(String path) {
        if (!StringUtils.hasText(path)) return null;
        try {
            return streamRepo.findById(UUID.fromString(path.trim())).orElse(null);
        } catch (IllegalArgumentException notAUuid) {
            return null; // paths that aren't a stream id are never authorized
        }
    }

    /** The publish credential is the streamKey; MediaMTX puts it in `password`,
     *  falling back to the raw `?pass=` query param. Compared in constant time. */
    private boolean streamKeyMatches(LiveStream s, String password, String query) {
        String provided = StringUtils.hasText(password) ? password : queryParam(query, "pass");
        if (provided == null || s.getStreamKey() == null) return false;
        return java.security.MessageDigest.isEqual(
                provided.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                s.getStreamKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String queryParam(String query, String name) {
        if (!StringUtils.hasText(query)) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) return pair.substring(eq + 1);
        }
        return null;
    }

    // ── internals ──────────────────────────────────────────────────────────────

    private void broadcastViewer(LiveStream s, List<UUID> activeViewers, UUID userId, String change) {
        broadcaster.broadcast(withHost(activeViewers, s.getHostId()),
                ChatRealtimeEvent.builder()
                        .eventType(ChatRealtimeEventType.STREAM_VIEWER)
                        .stream(toResponse(s, false)).userId(userId).memberChange(change)
                        .build());
    }

    private List<UUID> activeViewerIds(UUID streamId) {
        return viewerRepo.findActiveViewerIds(streamId);
    }

    private List<UUID> withHost(List<UUID> viewers, UUID hostId) {
        if (viewers.contains(hostId)) return viewers;
        List<UUID> out = new ArrayList<>(viewers);
        out.add(hostId);
        return out;
    }

    private LiveStream requireStream(UUID id) {
        return streamRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("LiveStream", "id", id));
    }

    private LiveStream requireLive(UUID id) {
        LiveStream s = requireStream(id);
        if (s.getStatus() != LiveStreamStatus.LIVE) throw new BadRequestException("This stream is not live.");
        return s;
    }

    private LiveStreamResponse toResponse(LiveStream s, boolean includeIngest) {
        // Publish and playback share the public stream-id path. Playback (HLS) is
        // public; the ingest URL additionally carries the secret streamKey as an
        // RTMP publish credential (?pass=), which the media server forwards to the
        // media-auth hook (authorizeMediaAccess) — so only the host who holds this
        // URL can publish, while the ingest URL itself stays host-only.
        String playbackUrl = playbackBase + "/" + s.getId() + "/index.m3u8";
        String ingestUrl = includeIngest
                ? ingestBase + "/" + s.getId() + "?user=publisher&pass=" + s.getStreamKey()
                : null;
        String shareUrl = ak.dev.irc.app.chat.util.ShareLinks.of(baseUrl, "/live/" + s.getId());
        return new LiveStreamResponse(s.getId(), s.getHostId(), s.getTitle(), s.getDescription(),
                s.getStatus().name(), playbackUrl, ingestUrl, s.getViewerCount(),
                s.getStartedAt(), s.getEndedAt(), shareUrl);
    }
}

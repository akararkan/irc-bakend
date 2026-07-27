package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.dto.request.LiveChatRequest;
import ak.dev.irc.app.chat.dto.request.StartStreamRequest;
import ak.dev.irc.app.chat.dto.request.UpdateStreamRequest;
import ak.dev.irc.app.chat.dto.response.LiveChatMessage;
import ak.dev.irc.app.chat.dto.response.LiveStreamResponse;
import ak.dev.irc.app.chat.dto.response.RecordingInfo;
import ak.dev.irc.app.chat.entity.LiveStream;
import ak.dev.irc.app.chat.entity.StreamViewer;
import ak.dev.irc.app.chat.enums.LiveStreamStatus;
import ak.dev.irc.app.chat.enums.RecordingStatus;
import ak.dev.irc.app.common.util.Pages;
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
import ak.dev.irc.app.user.repository.UserFollowRepository;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private final UserFollowRepository userFollowRepo;
    private final RateLimiter rateLimiter;
    private final LiveStreamFanoutService streamFanout;
    private final RecordingStorageService recordings;
    private final MediaControlClient mediaControl;

    /** Default recording choice when {@code StartStreamRequest.record} is absent. */
    @Value("${app.streaming.recording.default-on:false}")
    private boolean recordingDefaultOn;

    /** External media-server endpoints. Defaults target the local `mediamtx` service. */
    @Value("${app.streaming.webrtc-base:http://localhost:8889}")
    private String webrtcBase;
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
        boolean record = req.getRecord() != null ? req.getRecord() : recordingDefaultOn;
        LiveStream s = streamRepo.save(LiveStream.builder()
                .hostId(hostId)
                .title(req.getTitle().trim())
                .description(StringUtils.hasText(req.getDescription()) ? req.getDescription().trim() : null)
                .status(LiveStreamStatus.LIVE)
                .streamKey(UUID.randomUUID().toString().replace("-", ""))
                .viewerCount(0).peakViewerCount(0)
                .startedAt(Instant.now())
                .recordingEnabled(record)
                .recordingStatus(record ? RecordingStatus.RECORDING : RecordingStatus.DISABLED)
                .build());

        // Per-path recording opt-in: tell MediaMTX to record THIS path (and only
        // this one) before the browser publishes. Best-effort — if it fails the
        // stream still goes live, it just won't be recorded (manifest → EMPTY).
        if (record) mediaControl.enableRecording(s.getId());

        // "@host is live" fan-out to followers — realtime stream.started + inbox
        // notification, async & capped. Broadcast the PUBLIC view (includeIngest
        // = false) so the secret stream key NEVER reaches a follower. The public
        // view carries the host's identity (avatar + handle) so a follower's live
        // row prepends the ring with no extra fetch. Host is loaded once here and
        // reused for the label, the fan-out card, and the host's own response.
        User host = loadHost(hostId);
        String hostLabel = host != null ? "@" + host.getUsername() : "Someone";
        streamFanout.fanoutStreamStarted(toResponse(s, false, host), hostId, hostLabel);

        return toResponse(s, true, host); // host gets the ingest URLs (WHIP + RTMP)
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
        finalizeRecording(s);
        streamRepo.save(s);
        viewerRepo.deactivateAll(streamId);
        LiveStreamResponse ended = toResponse(s, false);
        broadcaster.broadcast(audience, ChatRealtimeEvent.builder()
                .eventType(ChatRealtimeEventType.STREAM_ENDED).stream(ended).build());
        // Also tell the host's FOLLOWERS so their "following is live" rail drops the
        // card — the started fan-out's mirror image (realtime only, no inbox notif).
        streamFanout.fanoutStreamEnded(ended, s.getHostId());
    }

    /**
     * Settle a stream's recording as it ends. Only opted-in streams were ever
     * recorded (per-path opt-in), so there's nothing to discard for opt-outs. For
     * an opted-in stream we stop its recording path (flushes the final segment,
     * frees the MediaMTX config entry) and mark it AVAILABLE (parts on disk) or
     * EMPTY (nothing was published). Never throws — a hiccup must not block ending;
     * {@link #recording} self-heals a stale EMPTY once the flush lands.
     */
    private void finalizeRecording(LiveStream s) {
        if (!s.isRecordingEnabled()) { s.setRecordingStatus(RecordingStatus.DISABLED); return; }
        try {
            mediaControl.removeRecordingPath(s.getId());
            s.setRecordingStatus(recordings.hasRecording(s.getId())
                    ? RecordingStatus.AVAILABLE : RecordingStatus.EMPTY);
        } catch (Exception e) {
            log.warn("[REC] finalize failed for {}: {}", s.getId(), e.getMessage());
        }
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
        // The viewer opens the watch page — enrich with the host's identity.
        return toResponse(s, s.getHostId().equals(userId), loadHost(s.getHostId()));
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
        return toResponses(streamRepo.findByStatusOrderByViewerCountDesc(LiveStreamStatus.LIVE));
    }

    /**
     * The "following is live" row: the streams from people this user follows that
     * are live right now, most-recently-started first — the TikTok / Instagram
     * live rail. Each card carries the host's avatar + handle so the frontend
     * renders the ring straight from this one response.
     */
    @Transactional(readOnly = true)
    public List<LiveStreamResponse> listFollowingLive(UUID userId) {
        List<UUID> followingIds = userFollowRepo.findFollowingIds(userId);
        if (followingIds.isEmpty()) return List.of();   // no IN () — nobody followed
        return toResponses(streamRepo.findByStatusAndHostIdInOrderByStartedAtDesc(
                LiveStreamStatus.LIVE, followingIds));
    }

    @Transactional(readOnly = true)
    public LiveStreamResponse get(UUID streamId, UUID userId) {
        LiveStream s = requireStream(streamId);
        return toResponse(s, s.getHostId().equals(userId), loadHost(s.getHostId()));
    }

    // ── Management (owner-only) ──────────────────────────────────────────────────

    /**
     * The host's own streams (LIVE + ENDED), newest-first — the manage/history
     * list. One indexed, clamped page: O(log N + pageSize). The host is loaded
     * once and shared across the page (every row has the same host).
     */
    @Transactional(readOnly = true)
    public Page<LiveStreamResponse> listMine(UUID hostId, int page, int size) {
        User host = loadHost(hostId);
        Page<LiveStream> streams = streamRepo.findByHostIdOrderByStartedAtDesc(
                hostId, PageRequest.of(Math.max(0, page), Pages.clamp(size)));
        // Ingest URLs only make sense while a stream is still LIVE.
        return streams.map(s -> toResponse(s, s.getStatus() == LiveStreamStatus.LIVE, host));
    }

    /**
     * Edit discovery metadata (title/description). Owner-only; a blank title is
     * rejected (a stream must always have one). While the stream is LIVE, active
     * viewers get a {@code stream.updated} event so the title changes in place.
     */
    @Transactional
    public LiveStreamResponse update(UUID streamId, UUID hostId, UpdateStreamRequest req) {
        LiveStream s = requireOwnedStream(streamId, hostId);
        if (req.getTitle() != null) {
            if (!StringUtils.hasText(req.getTitle())) throw new BadRequestException("Title cannot be blank.");
            s.setTitle(req.getTitle().trim());
        }
        if (req.getDescription() != null) {
            String d = req.getDescription().trim();
            s.setDescription(d.isEmpty() ? null : d);
        }
        streamRepo.save(s);
        if (s.getStatus() == LiveStreamStatus.LIVE) {
            broadcaster.broadcast(withHost(activeViewerIds(streamId), s.getHostId()),
                    ChatRealtimeEvent.builder()
                            .eventType(ChatRealtimeEventType.STREAM_UPDATED)
                            .stream(toResponse(s, false)).build());
        }
        return toResponse(s, s.getHostId().equals(hostId), loadHost(hostId));
    }

    /**
     * Delete a stream and its recording. A still-LIVE stream is ended first
     * (viewers notified, presence cleared). Owner-only. O(1) row + O(viewers)
     * presence purge (indexed) + O(parts) file cleanup.
     */
    @Transactional
    public void delete(UUID streamId, UUID hostId) {
        LiveStream s = requireOwnedStream(streamId, hostId);
        if (s.getStatus() == LiveStreamStatus.LIVE) {
            s.setStatus(LiveStreamStatus.ENDED);
            s.setEndedAt(Instant.now());
            List<UUID> audience = withHost(activeViewerIds(streamId), s.getHostId());
            LiveStreamResponse ended = toResponse(s, false);
            broadcaster.broadcast(audience, ChatRealtimeEvent.builder()
                    .eventType(ChatRealtimeEventType.STREAM_ENDED).stream(ended).build());
            streamFanout.fanoutStreamEnded(ended, s.getHostId()); // drop the card on followers' rails
        }
        mediaControl.removeRecordingPath(streamId); // stop recording + free config entry
        recordings.deleteRecording(streamId);       // discard media parts
        viewerRepo.deleteByStreamId(streamId);      // clear presence rows
        streamRepo.delete(s);                       // remove the control-plane record
    }

    /**
     * Recording manifest for the owner — status + the individual downloadable
     * parts. Self-heals a stale status when MediaMTX flushed parts after the
     * stream had already ended.
     */
    @Transactional
    public RecordingInfo recording(UUID streamId, UUID hostId) {
        LiveStream s = requireOwnedStream(streamId, hostId);
        List<Path> parts = recordings.listParts(streamId);
        if (s.isRecordingEnabled() && !parts.isEmpty()
                && s.getRecordingStatus() != RecordingStatus.AVAILABLE
                && s.getRecordingStatus() != RecordingStatus.DELETED) {
            s.setRecordingStatus(RecordingStatus.AVAILABLE);
            streamRepo.save(s);
        }
        List<RecordingInfo.RecordingPart> partDtos = parts.stream()
                .map(p -> new RecordingInfo.RecordingPart(
                        p.getFileName().toString(),
                        recordings.sizeOf(p),
                        recordings.modifiedAt(p),
                        downloadUrl(streamId, p.getFileName().toString())))
                .toList();
        long total = partDtos.stream().mapToLong(RecordingInfo.RecordingPart::sizeBytes).sum();
        boolean available = s.getRecordingStatus() == RecordingStatus.AVAILABLE && !parts.isEmpty();
        String primaryFile = primaryPart(parts).map(p -> p.getFileName().toString()).orElse(null);
        return new RecordingInfo(streamId, s.getRecordingStatus().name(),
                available, partDtos.size(), total, primaryFile, partDtos);
    }

    /**
     * Delete only the recording, keeping the stream record. Owner-only.
     * O(parts).
     */
    @Transactional
    public void deleteRecording(UUID streamId, UUID hostId) {
        LiveStream s = requireOwnedStream(streamId, hostId);
        recordings.deleteRecording(streamId);
        s.setRecordingStatus(RecordingStatus.DELETED);
        streamRepo.save(s);
    }

    /**
     * Owner-authorized resolution of a recording file to stream back. With no
     * {@code part} it returns the <em>primary</em> part — the largest, which is
     * the one carrying video when a broadcast's audio prelude split into its own
     * tiny part (so "download my recording" always yields the watchable file, not
     * a black audio-only prelude). A named {@code part} returns exactly that file.
     */
    @Transactional(readOnly = true)
    public Path resolveRecordingFile(UUID streamId, UUID hostId, String part) {
        requireOwnedStream(streamId, hostId);
        List<Path> parts = recordings.listParts(streamId);
        if (parts.isEmpty()) throw new ResourceNotFoundException("Recording", "streamId", streamId);
        if (!StringUtils.hasText(part)) {
            return primaryPart(parts).orElse(parts.get(0));
        }
        return recordings.resolvePart(streamId, part)
                .orElseThrow(() -> new ResourceNotFoundException("Recording part", "file", part));
    }

    /** The primary part = the largest file. A browser broadcast whose audio
     *  registered a beat before video splits into a tiny audio-only prelude + the
     *  real (much larger) video part; picking the largest hands back the video
     *  one without needing to probe codecs. */
    private java.util.Optional<Path> primaryPart(List<Path> parts) {
        return parts.stream().max(Comparator.comparingLong(recordings::sizeOf));
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

    private LiveStream requireOwnedStream(UUID streamId, UUID hostId) {
        LiveStream s = requireStream(streamId);
        if (!s.getHostId().equals(hostId)) {
            throw new ForbiddenException("Only the host can manage this stream.", "ACCESS_FORBIDDEN");
        }
        return s;
    }

    /** Owner-only API path to download a specific recording part. */
    private String downloadUrl(UUID streamId, String part) {
        return "/api/v1/streams/" + streamId + "/recording/download?part=" + part;
    }

    private LiveStream requireLive(UUID id) {
        LiveStream s = requireStream(id);
        if (s.getStatus() != LiveStreamStatus.LIVE) throw new BadRequestException("This stream is not live.");
        return s;
    }

    /** Lightweight view — no host identity. Used by the frequent realtime
     *  broadcasts (viewer / chat / ended), where the client already holds the
     *  host's identity from when it joined and only the counters change. */
    private LiveStreamResponse toResponse(LiveStream s, boolean includeIngest) {
        return toResponse(s, includeIngest, null);
    }

    /**
     * Full view, optionally carrying the host's identity ({@code host} may be
     * {@code null} to skip it). Used by the read/discovery endpoints and the
     * go-live / join responses so a card or live-row ring renders straight from
     * this one payload.
     */
    private LiveStreamResponse toResponse(LiveStream s, boolean includeIngest, User host) {
        // Publish and playback share the public stream-id path. Playback is public
        // (HLS at :8888, WebRTC/WHEP at :8889). The publish URLs additionally carry
        // the secret streamKey as a ?pass= credential, which the media server forwards
        // to the media-auth hook (authorizeMediaAccess) — so only the host who holds
        // these URLs can publish, while the URLs themselves stay host-only:
        //   • whipUrl  — the browser camera publishes here over WebRTC/WHIP.
        //   • ingestUrl — OBS/ffmpeg/desktop encoders publish here over RTMP.
        String id = s.getId().toString();
        String playbackUrl = playbackBase + "/" + id + "/index.m3u8";     // HLS
        String whepUrl     = webrtcBase   + "/" + id + "/whep";           // WebRTC playback
        String whipUrl = includeIngest
                ? webrtcBase + "/" + id + "/whip?pass=" + s.getStreamKey()
                : null;
        String ingestUrl = includeIngest
                ? ingestBase + "/" + id + "?user=publisher&pass=" + s.getStreamKey()
                : null;
        String shareUrl = ak.dev.irc.app.chat.util.ShareLinks.of(baseUrl, "/live/" + id);
        String hostUsername    = host != null ? host.getUsername()     : null;
        String hostDisplayName = host != null ? host.getFullName()     : null;
        String hostAvatarUrl   = host != null ? host.getProfileImage() : null;
        // Recording view comes straight off the entity (no disk I/O) — the
        // download link is present only once a finished recording exists.
        RecordingStatus rec = s.getRecordingStatus();
        String  recStatus      = rec != null ? rec.name() : null;
        boolean recAvailable   = rec == RecordingStatus.AVAILABLE;
        String  recDownloadUrl = recAvailable ? "/api/v1/streams/" + id + "/recording/download" : null;
        return new LiveStreamResponse(s.getId(), s.getHostId(), hostUsername, hostDisplayName,
                hostAvatarUrl, s.getTitle(), s.getDescription(),
                s.getStatus().name(), playbackUrl, whepUrl, whipUrl, ingestUrl, s.getViewerCount(),
                s.getStartedAt(), s.getEndedAt(), shareUrl,
                recStatus, recAvailable, recDownloadUrl);
    }

    /** Batch-enriched list view: one query resolves every distinct host (with the
     *  avatar joined), so a live row of N streams costs a single host round trip
     *  rather than N. */
    private List<LiveStreamResponse> toResponses(List<LiveStream> streams) {
        if (streams.isEmpty()) return List.of();
        List<UUID> hostIds = streams.stream().map(LiveStream::getHostId).distinct().toList();
        Map<UUID, User> hosts = userRepository.findActiveWithProfileByIdIn(hostIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return streams.stream().map(s -> toResponse(s, false, hosts.get(s.getHostId()))).toList();
    }

    /** Single host with the avatar joined; {@code null} if the host was deleted. */
    private User loadHost(UUID hostId) {
        return userRepository.findActiveWithProfileByIdIn(List.of(hostId)).stream().findFirst().orElse(null);
    }
}

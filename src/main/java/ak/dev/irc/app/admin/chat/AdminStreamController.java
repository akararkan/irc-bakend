package ak.dev.irc.app.admin.chat;

import ak.dev.irc.app.admin.moderation.ModerationRecorder;
import ak.dev.irc.app.admin.support.AdminAuditor;
import ak.dev.irc.app.admin.support.RequiresStepUp;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.chat.entity.LiveStream;
import ak.dev.irc.app.chat.entity.StreamGuest;
import ak.dev.irc.app.chat.enums.LiveStreamStatus;
import ak.dev.irc.app.chat.enums.RecordingStatus;
import ak.dev.irc.app.chat.enums.StreamGuestStatus;
import ak.dev.irc.app.chat.repository.LiveStreamRepository;
import ak.dev.irc.app.chat.repository.StreamGiftTallyRepository;
import ak.dev.irc.app.chat.repository.StreamGuestRepository;
import ak.dev.irc.app.chat.service.LiveStreamService;
import ak.dev.irc.app.chat.service.MediaControlClient;
import ak.dev.irc.app.chat.service.RecordingStorageService;
import ak.dev.irc.app.chat.service.StreamStageService;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.util.Pages;
import ak.dev.irc.app.user.service.NotificationService;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Live-stream administration (blueprint §3.5, chat-channels-live.md §6):
 * platform-wide stream browse, force-stop (host-equality was the only gate),
 * stream-key rotation, guest removal, recordings oversight, and the platform
 * gift rollup. {@code stream_key}/{@code publish_key} never appear in any
 * projection here — force-stop and rotation return nothing.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/streams")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")   // §6 matrix: live moderation is MODERATOR RW; key rotation stays ADMIN
public class AdminStreamController {

    private final LiveStreamRepository streamRepository;
    private final StreamGuestRepository guestRepository;
    private final StreamGiftTallyRepository giftTallyRepository;
    private final LiveStreamService liveStreamService;
    private final StreamStageService streamStageService;
    private final MediaControlClient mediaControlClient;
    private final RecordingStorageService recordingStorage;
    private final ModerationRecorder moderationRecorder;
    private final AdminAuditor adminAuditor;
    private final NotificationService notificationService;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminStreamRow(UUID id, UUID hostId, String title, String status,
                                 int viewerCount, int peakViewerCount,
                                 boolean recordingEnabled, String recordingStatus,
                                 Instant startedAt, Instant endedAt) {

        static AdminStreamRow of(LiveStream s) {
            return new AdminStreamRow(s.getId(), s.getHostId(), s.getTitle(),
                    s.getStatus() != null ? s.getStatus().name() : null,
                    s.getViewerCount(), s.getPeakViewerCount(),
                    s.isRecordingEnabled(),
                    s.getRecordingStatus() != null ? s.getRecordingStatus().name() : null,
                    s.getStartedAt(), s.getEndedAt());
        }
    }

    public record ReasonBody(@Size(max = 500) String reason, UUID reportId) {
    }

    // ── reads ───────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<Page<AdminStreamRow>> browse(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID hostId,
            @PageableDefault(size = 25) Pageable pageable) {
        Pageable clamped = PageRequest.of(Math.max(0, pageable.getPageNumber()),
                Pages.clamp(pageable.getPageSize()));
        LiveStreamStatus parsed = parseStatus(status);
        Page<LiveStream> page;
        if (parsed != null && hostId != null) {
            page = streamRepository.findByStatusAndHostIdOrderByStartedAtDesc(parsed, hostId, clamped);
        } else if (parsed != null) {
            page = streamRepository.findByStatusOrderByStartedAtDesc(parsed, clamped);
        } else if (hostId != null) {
            page = streamRepository.findByHostIdOrderByStartedAtDesc(hostId, clamped);
        } else {
            page = streamRepository.findAllByOrderByStartedAtDesc(clamped);
        }
        return ResponseEntity.ok(page.map(AdminStreamRow::of));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable UUID id) {
        LiveStream s = require(id);
        List<Map<String, Object>> guests = new ArrayList<>();
        for (StreamGuest g : guestRepository.findByStreamIdAndStatusOrderByJoinedAtAsc(
                id, StreamGuestStatus.ACTIVE)) {
            guests.add(Map.of(
                    "userId", g.getUserId(),
                    "muted", g.isMuted(),
                    "publishPath", String.valueOf(g.getPublishPath())));   // path, never the key
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("stream", AdminStreamRow.of(s));
        body.put("activeGuests", guests);
        body.put("recording", Map.of(
                "exists", recordingStorage.hasRecording(id),
                "totalBytes", recordingStorage.totalBytes(id)));
        return ResponseEntity.ok(body);
    }

    /** Recording metadata — content-adjacent, therefore step-up + audited. */
    @GetMapping("/{id}/recording")
    @RequiresStepUp
    public ResponseEntity<Map<String, Object>> recording(@PathVariable UUID id) {
        LiveStream s = require(id);
        adminAuditor.record(AuditOperation.READ, "LiveStream", id, "ADMIN_RECORDING_VIEW");
        return ResponseEntity.ok(Map.of(
                "streamId", id,
                "recordingStatus", s.getRecordingStatus() == null
                        ? RecordingStatus.DISABLED.name() : s.getRecordingStatus().name(),
                "exists", recordingStorage.hasRecording(id),
                "totalBytes", recordingStorage.totalBytes(id),
                "parts", recordingStorage.listParts(id).size()));
    }

    /** Recordings fleet: every on-disk recording with its stream row (when one
     *  still exists), sizes, and orphan flags — the disk-usage board. */
    @GetMapping("/recordings")
    @RequiresStepUp
    public ResponseEntity<Map<String, Object>> recordingsFleet(
            @RequestParam(defaultValue = "100") int limit) {
        adminAuditor.record(AuditOperation.READ, "LiveStream", null, "ADMIN_RECORDINGS_FLEET_VIEW");
        List<UUID> ids = recordingStorage.listAllStreamIds();
        long fleetBytes = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        int cap = ak.dev.irc.app.common.util.Pages.clamp(limit);
        for (UUID streamId : ids) {
            long bytes = recordingStorage.totalBytes(streamId);
            fleetBytes += bytes;
            if (rows.size() >= cap) continue;   // keep summing, stop detailing
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("streamId", streamId);
            row.put("bytes", bytes);
            row.put("parts", recordingStorage.listParts(streamId).size());
            var stream = streamRepository.findById(streamId);
            if (stream.isPresent()) {
                row.put("hostId", stream.get().getHostId());
                row.put("status", stream.get().getStatus());
                row.put("startedAt", stream.get().getStartedAt());
            } else {
                row.put("orphan", true);   // dir on disk, no stream row — delete candidate
            }
            rows.add(row);
        }
        rows.sort((a, b) -> Long.compare((long) b.get("bytes"), (long) a.get("bytes")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recordings", ids.size());
        body.put("fleetBytes", fleetBytes);
        body.put("rows", rows);
        if (ids.size() > cap) {
            body.put("note", "Detailed rows capped at " + cap + " of " + ids.size()
                    + " recordings; fleetBytes still covers everything.");
        }
        return ResponseEntity.ok(body);
    }

    /** Platform gift rollup — the cross-stream view that never existed. */
    @GetMapping("/gifts/top")
    public ResponseEntity<Map<String, Object>> topGifters(
            @RequestParam(defaultValue = "20") int limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : giftTallyRepository.topGiftersPlatform(
                PageRequest.of(0, Pages.clamp(limit)))) {
            rows.add(Map.of(
                    "userId", r[0],
                    "coins", ((Number) r[1]).longValue(),
                    "gifts", ((Number) r[2]).longValue()));
        }
        return ResponseEntity.ok(Map.of(
                "totalCoins", giftTallyRepository.totalCoins(),
                "topGifters", rows));
    }

    // ── mutations ───────────────────────────────────────────────────────

    /**
     * Force-stop: kick the host + every active guest publisher off MediaMTX,
     * then run the full owner end-path (viewers, guests, recording finalize,
     * fan-out) by acting with the host's id — no parallel shutdown logic.
     */
    @PostMapping("/{id}/force-stop")
    @RequiresStepUp
    public ResponseEntity<Void> forceStop(@PathVariable UUID id,
                                          @RequestBody(required = false) ReasonBody body) {
        LiveStream s = require(id);
        if (s.getStatus() != LiveStreamStatus.LIVE) {
            throw new BadRequestException("Stream is not live.", "STREAM_NOT_LIVE");
        }
        mediaControlClient.kickPublisher(id.toString());
        for (StreamGuest g : guestRepository.findByStreamIdAndStatusOrderByJoinedAtAsc(
                id, StreamGuestStatus.ACTIVE)) {
            if (g.getPublishPath() != null) {
                mediaControlClient.kickPublisher(g.getPublishPath());
            }
        }
        liveStreamService.end(id, s.getHostId());

        String reason = body != null ? body.reason() : null;
        moderationRecorder.decision("STREAM", id.toString(), "ADMIN_STREAM_FORCE_STOP",
                reason, body != null ? body.reportId() : null,
                "peakViewers=" + s.getPeakViewerCount());
        adminAuditor.record(AuditOperation.UPDATE, "LiveStream", id,
                "ADMIN_STREAM_FORCE_STOP", reason);
        notifyHost(s, "Your live stream was ended by moderation",
                "Your live stream \"" + s.getTitle() + "\" was ended by platform moderation"
                        + (reason != null && !reason.isBlank() ? " (" + reason + ")" : "") + ".");
        return ResponseEntity.noContent().build();
    }

    /**
     * Rotate the stream key: the old key dies at the auth hook immediately and
     * the current publisher is kicked. Returns nothing — the new key is
     * delivered to the host alone via their notification channel.
     */
    @PostMapping("/{id}/rotate-key")
    @RequiresStepUp
    @PreAuthorize("hasRole('ADMIN')")   // key-level control never delegates to staff tiers
    public ResponseEntity<Void> rotateKey(@PathVariable UUID id,
                                          @RequestBody(required = false) ReasonBody body) {
        LiveStream s = require(id);
        String newKey = UUID.randomUUID().toString().replace("-", "");
        s.setStreamKey(newKey);
        streamRepository.save(s);
        mediaControlClient.kickPublisher(id.toString());
        adminAuditor.record(AuditOperation.UPDATE, "LiveStream", id,
                "ADMIN_STREAM_KEY_ROTATE", body != null ? body.reason() : null);
        notifyHost(s, "Your stream key was rotated",
                "For security, the stream key of \"" + s.getTitle() + "\" was rotated. "
                        + "Your new key: " + newKey);
        return ResponseEntity.noContent().build();
    }

    /** Stage-remove a co-host (host-only today) + kick their publisher. */
    @DeleteMapping("/{id}/stage/{userId}")
    @RequiresStepUp
    public ResponseEntity<Void> removeGuest(@PathVariable UUID id, @PathVariable UUID userId,
                                            @RequestBody(required = false) ReasonBody body) {
        LiveStream s = require(id);
        guestRepository.findByStreamIdAndUserId(id, userId)
                .map(StreamGuest::getPublishPath)
                .filter(java.util.Objects::nonNull)
                .ifPresent(mediaControlClient::kickPublisher);
        streamStageService.removeGuest(id, s.getHostId(), userId);
        adminAuditor.record(AuditOperation.UPDATE, "LiveStream", id,
                "ADMIN_STREAM_GUEST_REMOVE", "guest=" + userId
                        + (body != null && body.reason() != null ? ", " + body.reason() : ""));
        return ResponseEntity.noContent().build();
    }

    /** Irreversible: recording files are the only copy. */
    @DeleteMapping("/{id}/recording")
    @RequiresStepUp
    public ResponseEntity<Void> deleteRecording(@PathVariable UUID id,
                                                @RequestBody(required = false) ReasonBody body) {
        LiveStream s = require(id);
        int files = recordingStorage.deleteRecording(id);
        s.setRecordingStatus(RecordingStatus.DELETED);
        streamRepository.save(s);
        String reason = body != null ? body.reason() : null;
        moderationRecorder.decision("STREAM", id.toString(), "ADMIN_RECORDING_DELETE",
                reason, body != null ? body.reportId() : null, "files=" + files);
        adminAuditor.record(AuditOperation.DELETE, "LiveStream", id,
                "ADMIN_RECORDING_DELETE", reason);
        return ResponseEntity.noContent().build();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private LiveStream require(UUID id) {
        return streamRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("LiveStream", "id", id));
    }

    private void notifyHost(LiveStream s, String title, String body) {
        try {
            notificationService.sendSystemNotification(s.getHostId(), title, body);
        } catch (Exception e) {
            log.debug("[ADMIN-STREAM] host notification skipped: {}", e.getMessage());
        }
    }

    private static LiveStreamStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LiveStreamStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown status. Allowed: LIVE, ENDED.", "INVALID_STATUS");
        }
    }
}

package ak.dev.irc.app.admin.sound;

import ak.dev.irc.app.admin.moderation.ModerationRecorder;
import ak.dev.irc.app.admin.support.AdminAuditor;
import ak.dev.irc.app.audit.enums.AuditOperation;
import ak.dev.irc.app.common.exception.BadRequestException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.common.messages.AdminContentMessages;
import ak.dev.irc.app.post.cassandra.entity.PostBySoundEntity;
import ak.dev.irc.app.post.cassandra.entity.SoundEntity;
import ak.dev.irc.app.post.cassandra.service.CassandraSoundService;
import ak.dev.irc.app.post.enums.SoundCategory;
import ak.dev.irc.app.post.enums.SoundStatus;
import ak.dev.irc.app.post.search.service.PostSearchService;
import ak.dev.irc.app.post.search.service.SoundSearchService;
import ak.dev.irc.app.rabbitmq.event.post.SoundApprovedEvent;
import ak.dev.irc.app.rabbitmq.event.post.SoundRejectedEvent;
import ak.dev.irc.app.rabbitmq.publisher.PostEventPublisher;
import ak.dev.irc.app.security.SecurityUtils;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin surface over the sound library (sound-library.md §6, blueprint §3.3):
 * the review queue read that never existed, the missing state-machine
 * transitions, rights takedown with adopting-post mute, official bulk-seed,
 * and the trending-exclude flag. Every decision snapshots {@code use_count}
 * (blast radius is forensically meaningful and the counter is monotonic).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminSoundService {

    private final CassandraSoundService soundService;
    private final SoundSearchService soundSearchService;
    private final PostSearchService postSearchService;
    private final PostEventPublisher postEventPublisher;
    private final ModerationRecorder moderationRecorder;
    private final AdminAuditor adminAuditor;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminSoundRow(UUID id, String title, String artistName, String category,
                                String status, Integer durationSeconds, UUID uploaderId,
                                Boolean official, Boolean trendingExcluded,
                                String audioUrl, String coverArtUrl,
                                Long useCount, Instant createdAt, Instant updatedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminSoundDetail(AdminSoundRow sound, List<Map<String, Object>> recentPosts) {
    }

    public record ImportItem(String title, String artistName, String audioUrl,
                             String coverArtUrl, Integer durationSeconds, String category) {
    }

    // ── reads ───────────────────────────────────────────────────────────

    /**
     * The single-sound admin-dashboard create path (sound-library.md §5). Sounds
     * are no longer user-uploadable — this is the only way a new sound enters the
     * library — so an admin add is trusted by construction: it lands straight on
     * APPROVED, skipping the PENDING_REVIEW queue that exists for the (now
     * removed) end-user upload flow.
     */
    public AdminSoundRow create(String title, String artistName, String audioUrl,
                                String coverArtUrl, Integer durationSeconds,
                                String category, boolean official) {
        if (title == null || title.isBlank() || audioUrl == null || audioUrl.isBlank()) {
            throw new BadRequestException(AdminContentMessages.INVALID_IMPORT_ITEM_MSG,
                    AdminContentMessages.INVALID_IMPORT_ITEM);
        }
        UUID adminId = SecurityUtils.requireCurrentUserId();
        String parsedCategory = parseCategory(category == null
                ? SoundCategory.PLATFORM_MUSIC.name() : category);
        SoundEntity created = soundService.createSound(title, artistName, audioUrl, coverArtUrl,
                durationSeconds, parsedCategory, adminId, true);
        if (official) created = soundService.setOfficial(created.getId(), true);
        adminAuditor.record(AuditOperation.CREATE, "Sound", created.getId(),
                "ADMIN_SOUND_CREATE", created.getTitle());
        return toRow(created);
    }

    public List<AdminSoundRow> queue(String status, UUID uploaderId, Instant cursor, int pageSize) {
        String normalized = parseStatus(status == null || status.isBlank()
                ? "PENDING_REVIEW" : status);
        List<UUID> ids = soundSearchService.idsByStatus(normalized, uploaderId, cursor, pageSize);
        return hydrate(ids);
    }

    public List<AdminSoundRow> uploaderHistory(UUID uploaderId, Instant cursor, int pageSize) {
        return hydrate(soundSearchService.idsByUploader(uploaderId, cursor, pageSize));
    }

    public Map<String, Long> statusCounts() {
        return soundSearchService.countsByStatus();
    }

    public AdminSoundDetail detail(UUID soundId) {
        SoundEntity s = require(soundId);
        List<Map<String, Object>> posts = new ArrayList<>();
        for (PostBySoundEntity p : soundService.postsUsingSound(soundId, 20)) {
            posts.add(Map.of(
                    "postId", p.getPostId(),
                    "authorId", p.getAuthorId(),
                    "createdAt", String.valueOf(p.getCreatedAt())));
        }
        return new AdminSoundDetail(toRow(s), posts);
    }

    // ── transitions ─────────────────────────────────────────────────────

    public void approve(UUID soundId) {
        SoundEntity s = require(soundId);
        soundService.approve(soundId);
        moderationRecorder.decision("SOUND", soundId.toString(), "ADMIN_SOUND_APPROVE",
                null, null, blastRadius(soundId));
        adminAuditor.record(AuditOperation.UPDATE, "Sound", soundId, "ADMIN_SOUND_APPROVE");
        postEventPublisher.publishSoundApproved(SoundApprovedEvent.builder()
                .soundId(soundId).uploaderId(s.getUploaderId()).soundTitle(s.getTitle()).build());
    }

    public void reject(UUID soundId, String reasonCode, String note) {
        SoundEntity s = soundService.reject(soundId);
        String reason = join(reasonCode, note);
        moderationRecorder.decision("SOUND", soundId.toString(), "ADMIN_SOUND_REJECT",
                reason, null, blastRadius(soundId));
        adminAuditor.record(AuditOperation.UPDATE, "Sound", soundId, "ADMIN_SOUND_REJECT", reason);
        postEventPublisher.publishSoundRejected(SoundRejectedEvent.builder()
                .soundId(soundId).uploaderId(s.getUploaderId())
                .soundTitle(s.getTitle()).reason(reasonCode).build());
    }

    public void archive(UUID soundId, String reason) {
        soundService.archive(soundId);
        moderationRecorder.decision("SOUND", soundId.toString(), "ADMIN_SOUND_ARCHIVE",
                reason, null, blastRadius(soundId));
        adminAuditor.record(AuditOperation.UPDATE, "Sound", soundId, "ADMIN_SOUND_ARCHIVE", reason);
    }

    public void restore(UUID soundId, String reason) {
        soundService.restore(soundId);
        moderationRecorder.decision("SOUND", soundId.toString(), "ADMIN_SOUND_RESTORE",
                reason, null, null);
        adminAuditor.record(AuditOperation.UPDATE, "Sound", soundId, "ADMIN_SOUND_RESTORE", reason);
    }

    /** Rights/safety takedown — archive + optionally mute every adopting post. */
    public int takedown(UUID soundId, String reason, boolean muteAdoptingPosts, String rightsClaimId) {
        String radius = blastRadius(soundId);
        int muted = soundService.takedown(soundId, muteAdoptingPosts,
                post -> postSearchService.indexAsync(post));
        moderationRecorder.decision("SOUND", soundId.toString(), "ADMIN_SOUND_TAKEDOWN",
                reason, null, radius + ", mutedPosts=" + muted
                        + (rightsClaimId != null ? ", rightsClaim=" + rightsClaimId : ""));
        adminAuditor.record(AuditOperation.UPDATE, "Sound", soundId, "ADMIN_SOUND_TAKEDOWN",
                reason + " (muted " + muted + " posts)");
        return muted;
    }

    public void recategorize(UUID soundId, String category) {
        String parsed = parseCategory(category);
        soundService.recategorize(soundId, parsed);
        adminAuditor.record(AuditOperation.UPDATE, "Sound", soundId,
                "ADMIN_SOUND_RECATEGORIZE", "→ " + parsed);
    }

    public AdminSoundRow edit(UUID soundId, String title, String artistName, String coverArtUrl) {
        SoundEntity s = soundService.editMetadata(soundId, title, artistName, coverArtUrl);
        adminAuditor.record(AuditOperation.UPDATE, "Sound", soundId, "ADMIN_SOUND_EDIT");
        return toRow(s);
    }

    public void hardDelete(UUID soundId, String reason) {
        String radius = blastRadius(soundId);
        soundService.hardDelete(soundId);
        moderationRecorder.decision("SOUND", soundId.toString(), "ADMIN_SOUND_DELETE",
                reason, null, radius);
        adminAuditor.record(AuditOperation.DELETE, "Sound", soundId, "ADMIN_SOUND_DELETE", reason);
    }

    public void setTrendingExcluded(UUID soundId, boolean excluded) {
        soundService.setTrendingExcluded(soundId, excluded);
        adminAuditor.record(AuditOperation.UPDATE, "Sound", soundId,
                "ADMIN_SOUND_TRENDING_EXCLUDE", "excluded=" + excluded);
    }

    /** Official bulk-seed: first-party audio, auto-approved, official-flagged. */
    public List<AdminSoundRow> importOfficial(List<ImportItem> items) {
        if (items == null || items.isEmpty() || items.size() > 100) {
            throw new BadRequestException(AdminContentMessages.INVALID_IMPORT_BATCH_MSG,
                    AdminContentMessages.INVALID_IMPORT_BATCH);
        }
        UUID adminId = SecurityUtils.requireCurrentUserId();
        List<AdminSoundRow> out = new ArrayList<>();
        for (ImportItem item : items) {
            if (item.title() == null || item.title().isBlank()
                    || item.audioUrl() == null || item.audioUrl().isBlank()) {
                throw new BadRequestException(AdminContentMessages.INVALID_IMPORT_ITEM_MSG,
                        AdminContentMessages.INVALID_IMPORT_ITEM);
            }
            String category = parseCategory(item.category() == null
                    ? SoundCategory.PLATFORM_MUSIC.name() : item.category());
            SoundEntity created = soundService.createSound(item.title(), item.artistName(),
                    item.audioUrl(), item.coverArtUrl(), item.durationSeconds(),
                    category, adminId, true);
            out.add(toRow(soundService.setOfficial(created.getId(), true)));
        }
        adminAuditor.record(AuditOperation.CREATE, "Sound", null,
                "ADMIN_SOUND_IMPORT", items.size() + " official sound(s)");
        return out;
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private SoundEntity require(UUID soundId) {
        SoundEntity s = soundService.getById(soundId);
        if (s == null) throw new ResourceNotFoundException("Sound", "id", soundId);
        return s;
    }

    private List<AdminSoundRow> hydrate(List<UUID> ids) {
        List<AdminSoundRow> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            SoundEntity s = soundService.getById(id);
            if (s != null) out.add(toRow(s));
        }
        return out;
    }

    private AdminSoundRow toRow(SoundEntity s) {
        return new AdminSoundRow(s.getId(), s.getTitle(), s.getArtistName(), s.getCategory(),
                s.getStatus(), s.getDurationSeconds(), s.getUploaderId(),
                s.getOfficial(), s.getTrendingExcluded(),
                s.getAudioUrl(), s.getCoverArtUrl(),
                soundService.useCountFor(s.getId()), s.getCreatedAt(), s.getUpdatedAt());
    }

    private String blastRadius(UUID soundId) {
        try {
            return "useCount=" + soundService.useCountFor(soundId);
        } catch (Exception e) {
            return "useCount=?";
        }
    }

    private static String parseStatus(String raw) {
        try {
            return SoundStatus.valueOf(raw.trim().toUpperCase()).name();
        } catch (Exception e) {
            throw new BadRequestException(AdminContentMessages.INVALID_STATUS_MSG.formatted(
                    java.util.Arrays.toString(SoundStatus.values())),
                    AdminContentMessages.INVALID_STATUS);
        }
    }

    private static String parseCategory(String raw) {
        try {
            return SoundCategory.valueOf(raw.trim().toUpperCase()).name();
        } catch (Exception e) {
            throw new BadRequestException(AdminContentMessages.INVALID_CATEGORY_MSG.formatted(
                    java.util.Arrays.toString(SoundCategory.values())),
                    AdminContentMessages.INVALID_CATEGORY);
        }
    }

    private static String join(String a, String b) {
        if (a == null || a.isBlank()) return b;
        if (b == null || b.isBlank()) return a;
        return a + ": " + b;
    }
}

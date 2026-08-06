package ak.dev.irc.app.post.cassandra.service;

import ak.dev.irc.app.post.cassandra.entity.PostBySoundEntity;
import ak.dev.irc.app.post.cassandra.entity.SoundByCategoryEntity;
import ak.dev.irc.app.post.cassandra.entity.SoundEntity;
import ak.dev.irc.app.post.cassandra.repository.PostBySoundRepository;
import ak.dev.irc.app.post.cassandra.repository.SoundByCategoryRepository;
import ak.dev.irc.app.post.cassandra.repository.SoundCounterRepository;
import ak.dev.irc.app.post.cassandra.repository.SoundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reusable sound library (NASHEED, QURAN_RECITATION, LECTURE_CLIP, etc.).
 *
 * Write fan-out per APPROVED sound:
 *   • sounds_by_id            ← canonical
 *   • sounds_by_category      ← browser by category (only on APPROVED)
 *
 * Write fan-out per post adopting a sound:
 *   • posts_by_sound          ← "all posts using sound X" feed
 *   • sound_counters++        ← use_count for trending logic
 *
 * Status semantics:
 *   PENDING_REVIEW → lives only in sounds_by_id; moderators read uploads via
 *                    a separate review query (out of scope here).
 *   APPROVED       → also written to sounds_by_category so users can browse.
 *   REJECTED / ARCHIVED → removed from category but kept in sounds_by_id for audit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CassandraSoundService {

    private final SoundRepository           soundRepo;
    private final SoundByCategoryRepository soundByCategoryRepo;
    private final PostBySoundRepository     postBySoundRepo;
    private final SoundCounterRepository    soundCounterRepo;
    private final CounterService            counterService;
    private final ak.dev.irc.app.post.search.service.SoundSearchService soundSearch;
    private final SoundAdoptionCounter soundAdoptionCounter;
    private final ak.dev.irc.app.post.cassandra.repository.PostByIdRepository postByIdRepo;

    // ── Create / approve ────────────────────────────────────────────────────

    public SoundEntity createSound(String title, String artistName, String audioUrl,
                                   String coverArtUrl, Integer durationSeconds,
                                   String category, UUID uploaderId, boolean autoApprove) {
        UUID    id  = UUID.randomUUID();
        Instant now = Instant.now();
        String  status = autoApprove ? "APPROVED" : "PENDING_REVIEW";

        SoundEntity sound = SoundEntity.builder()
                .id(id).title(title).artistName(artistName)
                .audioUrl(audioUrl).coverArtUrl(coverArtUrl)
                .durationSeconds(durationSeconds).category(category)
                .status(status).uploaderId(uploaderId)
                .createdAt(now).updatedAt(now)
                .build();
        soundRepo.save(sound);

        if (autoApprove) writeCategoryRow(sound);
        soundSearch.indexAsync(sound, 0L);
        return sound;
    }

    /** Mark a sound APPROVED → fans out to the category browser. */
    public void approve(UUID soundId) {
        SoundEntity s = soundRepo.findById(soundId).orElse(null);
        if (s == null || "APPROVED".equals(s.getStatus())) return;
        s.setStatus("APPROVED");
        s.setUpdatedAt(Instant.now());
        soundRepo.save(s);
        writeCategoryRow(s);
        soundSearch.refreshAsync(soundId);
    }

    /**
     * Ranked sound-library search for the reels/stories sound picker —
     * typo-tolerant title/artist match, APPROVED-only, popularity-boosted.
     * ES returns ranked ids; rows hydrate from {@code sounds_by_id}
     * preserving that order (a stale id that no longer resolves is dropped).
     */
    public List<SoundEntity> search(String q, String category, int limit) {
        if (q == null || q.isBlank()) return List.of();
        List<UUID> ids;
        try {
            ids = soundSearch.searchIds(q.trim(), category, limit);
        } catch (Exception e) {
            log.warn("[SOUND] search unavailable: {}", e.getMessage());
            return List.of();
        }
        List<SoundEntity> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            SoundEntity s = soundRepo.findById(id).orElse(null);
            if (s != null && "APPROVED".equals(s.getStatus())) out.add(s);
        }
        return out;
    }

    private void writeCategoryRow(SoundEntity s) {
        soundByCategoryRepo.save(SoundByCategoryEntity.builder()
                .category(s.getCategory()).createdAt(s.getCreatedAt()).soundId(s.getId())
                .title(s.getTitle()).artistName(s.getArtistName())
                .audioUrl(s.getAudioUrl()).coverArtUrl(s.getCoverArtUrl())
                .durationSeconds(s.getDurationSeconds())
                .build());
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    public SoundEntity getById(UUID soundId) {
        return soundRepo.findById(soundId).orElse(null);
    }

    public List<SoundByCategoryEntity> listByCategory(String category, int pageSize) {
        return soundByCategoryRepo.firstPage(category, pageSize);
    }

    public List<SoundByCategoryEntity> listByCategoryAfter(String category, Instant cursor, int pageSize) {
        return soundByCategoryRepo.nextPage(category, cursor, pageSize);
    }

    public List<PostBySoundEntity> postsUsingSound(UUID soundId, int pageSize) {
        return postBySoundRepo.firstPage(soundId, pageSize);
    }

    public Long useCountFor(UUID soundId) {
        return soundCounterRepo.findBySoundId(soundId)
                .map(c -> c.getUseCount()).orElse(0L);
    }

    // ── Adoption (called from CassandraPostService when a post has a sound) ─

    public void recordPostUsage(UUID soundId, UUID postId, UUID authorId, Instant createdAt) {
        try {
            postBySoundRepo.save(PostBySoundEntity.builder()
                    .soundId(soundId).createdAt(createdAt).postId(postId)
                    .authorId(authorId)
                    .build());
            counterService.incrementSoundUse(soundId);
            soundAdoptionCounter.bump(soundId);  // day-bucketed trending tee
            soundSearch.refreshAsync(soundId);   // keep popularity ranking fresh
        } catch (Exception e) {
            log.warn("[SOUND] usage record failed for sound {} post {}: {}",
                    soundId, postId, e.getMessage());
        }
    }

    // ── Admin state machine (sound-library.md §5-6) ─────────────────────────
    // The enum always declared PENDING_REVIEW → APPROVED | REJECTED | ARCHIVED
    // but only the approve transition had a writer. These complete it.

    /** PENDING_REVIEW → REJECTED. Row retained for audit; ES doc removed. */
    public SoundEntity reject(UUID soundId) {
        SoundEntity s = requireSound(soundId);
        s.setStatus("REJECTED");
        s.setUpdatedAt(Instant.now());
        soundRepo.save(s);
        removeCategoryRow(s);
        soundSearch.deleteAsync(soundId);
        return s;
    }

    /** APPROVED → ARCHIVED: stops NEW adoption; existing posts keep their audio. */
    public SoundEntity archive(UUID soundId) {
        SoundEntity s = requireSound(soundId);
        s.setStatus("ARCHIVED");
        s.setUpdatedAt(Instant.now());
        soundRepo.save(s);
        removeCategoryRow(s);
        soundSearch.deleteAsync(soundId);
        return s;
    }

    /** REJECTED/ARCHIVED → APPROVED (re-review override / counter-notice restore). */
    public SoundEntity restore(UUID soundId) {
        SoundEntity s = requireSound(soundId);
        s.setStatus("APPROVED");
        s.setUpdatedAt(Instant.now());
        soundRepo.save(s);
        writeCategoryRow(s);
        soundSearch.refreshAsync(soundId);
        return s;
    }

    /**
     * Rights/safety takedown: archive + optionally strip the audio from every
     * adopting post (the TikTok "muted audio" behaviour), walking
     * {@code posts_by_sound} as the work-list. Returns muted-post count.
     */
    public int takedown(UUID soundId, boolean muteAdoptingPosts,
                        java.util.function.Consumer<ak.dev.irc.app.post.cassandra.entity.PostByIdEntity> onMuted) {
        archive(soundId);
        if (!muteAdoptingPosts) return 0;

        int muted = 0;
        final int page = 500, maxPosts = 20_000;
        List<PostBySoundEntity> batch = postBySoundRepo.firstPage(soundId, page);
        while (!batch.isEmpty() && muted < maxPosts) {
            for (PostBySoundEntity use : batch) {
                try {
                    var post = postByIdRepo.findById(use.getPostId()).orElse(null);
                    if (post == null) continue;
                    if (post.getAudioTrackUrl() == null && post.getAudioTrackName() == null) continue;
                    post.setAudioTrackUrl(null);
                    post.setAudioTrackName(null);
                    post.setUpdatedAt(Instant.now());
                    postByIdRepo.save(post);
                    if (onMuted != null) onMuted.accept(post);
                    muted++;
                } catch (Exception e) {
                    log.warn("[SOUND] mute of post {} failed: {}", use.getPostId(), e.getMessage());
                }
            }
            if (batch.size() < page) break;
            Instant cursor = batch.get(batch.size() - 1).getCreatedAt();
            batch = postBySoundRepo.nextPage(soundId, cursor, page);
        }
        log.info("[SOUND] takedown of {} muted {} adopting post(s)", soundId, muted);
        return muted;
    }

    /** Move a sound to another category — rewrites its category-browse row. */
    public SoundEntity recategorize(UUID soundId, String newCategory) {
        SoundEntity s = requireSound(soundId);
        removeCategoryRow(s);
        s.setCategory(newCategory);
        s.setUpdatedAt(Instant.now());
        soundRepo.save(s);
        if ("APPROVED".equals(s.getStatus())) writeCategoryRow(s);
        soundSearch.refreshAsync(soundId);
        return s;
    }

    public SoundEntity editMetadata(UUID soundId, String title, String artistName, String coverArtUrl) {
        SoundEntity s = requireSound(soundId);
        if (title != null && !title.isBlank()) s.setTitle(title);
        if (artistName != null) s.setArtistName(artistName);
        if (coverArtUrl != null) s.setCoverArtUrl(coverArtUrl);
        s.setUpdatedAt(Instant.now());
        soundRepo.save(s);
        if ("APPROVED".equals(s.getStatus())) writeCategoryRow(s);
        soundSearch.refreshAsync(soundId);
        return s;
    }

    public SoundEntity setOfficial(UUID soundId, boolean official) {
        SoundEntity s = requireSound(soundId);
        s.setOfficial(official);
        s.setUpdatedAt(Instant.now());
        soundRepo.save(s);
        soundSearch.refreshAsync(soundId);
        return s;
    }

    public SoundEntity setTrendingExcluded(UUID soundId, boolean excluded) {
        SoundEntity s = requireSound(soundId);
        s.setTrendingExcluded(excluded);
        s.setUpdatedAt(Instant.now());
        soundRepo.save(s);
        soundSearch.refreshAsync(soundId);
        return s;
    }

    /** GDPR-grade purge of every sound store + adoption detach. */
    public void hardDelete(UUID soundId) {
        SoundEntity s = soundRepo.findById(soundId).orElse(null);
        if (s != null) removeCategoryRow(s);
        soundRepo.deleteById(soundId);
        try {
            soundCounterRepo.deleteBySoundId(soundId);
        } catch (Exception e) {
            log.warn("[SOUND] counter purge failed for {}: {}", soundId, e.getMessage());
        }
        try {
            postBySoundRepo.deleteAllForSound(soundId);
        } catch (Exception e) {
            log.warn("[SOUND] posts_by_sound purge failed for {}: {}", soundId, e.getMessage());
        }
        soundSearch.deleteAsync(soundId);
    }

    private SoundEntity requireSound(UUID soundId) {
        SoundEntity s = soundRepo.findById(soundId).orElse(null);
        if (s == null) {
            throw new ak.dev.irc.app.common.exception.ResourceNotFoundException("Sound", "id", soundId);
        }
        return s;
    }

    private void removeCategoryRow(SoundEntity s) {
        if (s.getCategory() == null || s.getCreatedAt() == null) return;
        try {
            soundByCategoryRepo.deleteRow(s.getCategory(), s.getCreatedAt(), s.getId());
        } catch (Exception e) {
            log.warn("[SOUND] category-row delete failed for {}: {}", s.getId(), e.getMessage());
        }
    }
}

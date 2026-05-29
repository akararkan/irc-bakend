package ak.dev.irc.app.research.cassandra.service;

import ak.dev.irc.app.research.cassandra.entity.*;
import ak.dev.irc.app.research.cassandra.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Cassandra-side mirror for Research engagement (reactions, views, downloads,
 * saves, comment likes).
 *
 * <p>{@code ResearchService} (and the controller layer above it) continues to
 * own the Postgres canonical writes for the research entity itself — the
 * citation tree and formal publication semantics need transactional integrity
 * that Cassandra doesn't provide. Each engagement-write path
 * should additionally call the matching {@code mirror*} method here so the
 * Cassandra side reaches parity. Fire-and-forget {@code @Async} keeps
 * Postgres-side latency unchanged.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CassandraResearchEngagementService {

    private final ResearchReactionByResearchRepository  reactionByResearchRepo;
    private final ResearchReactionByUserRepository      reactionByUserRepo;
    private final ResearchViewRepository                viewRepo;
    private final ResearchDownloadRepository            downloadRepo;
    private final ResearchSaveByUserRepository          saveByUserRepo;
    private final ResearchSaveLookupRepository          saveLookupRepo;
    private final ResearchCommentLikeRepository         commentLikeRepo;

    // ── Reactions ────────────────────────────────────────────────────────────

    @Async
    public void mirrorReactionAdded(UUID researchId, UUID userId, String reactionType) {
        try {
            Instant now = Instant.now();
            reactionByResearchRepo.save(ResearchReactionByResearchEntity.builder()
                    .researchId(researchId).userId(userId)
                    .reactionType(reactionType).createdAt(now).build());
            reactionByUserRepo.save(ResearchReactionByUserEntity.builder()
                    .userId(userId).createdAt(now).researchId(researchId).build());
        } catch (Exception e) {
            log.debug("[RESEARCH-MIRROR] reaction add skipped: {}", e.getMessage());
        }
    }

    @Async
    public void mirrorReactionRemoved(UUID researchId, UUID userId) {
        try {
            Optional<ResearchReactionByResearchEntity> existing =
                    reactionByResearchRepo.find(researchId, userId);
            reactionByResearchRepo.delete(researchId, userId);
            existing.ifPresent(r ->
                    reactionByUserRepo.delete(userId, r.getCreatedAt(), researchId));
        } catch (Exception e) {
            log.debug("[RESEARCH-MIRROR] reaction remove skipped: {}", e.getMessage());
        }
    }

    public boolean hasReacted(UUID researchId, UUID userId) {
        return reactionByResearchRepo.find(researchId, userId).isPresent();
    }

    // ── Views ───────────────────────────────────────────────────────────────

    @Async
    public void mirrorView(UUID researchId, UUID userId) {
        try {
            if (viewRepo.find(researchId, userId).isPresent()) return;
            viewRepo.save(ResearchViewEntity.builder()
                    .researchId(researchId).userId(userId)
                    .firstViewedAt(Instant.now()).build());
        } catch (Exception e) {
            log.debug("[RESEARCH-MIRROR] view skipped: {}", e.getMessage());
        }
    }

    // ── Downloads (append-only) ─────────────────────────────────────────────

    @Async
    public void mirrorDownload(UUID researchId, UUID userId, UUID mediaId, String ipAddress) {
        try {
            downloadRepo.save(ResearchDownloadEntity.builder()
                    .researchId(researchId).createdAt(Instant.now())
                    .downloadId(UUID.randomUUID())
                    .userId(userId).mediaId(mediaId).ipAddress(ipAddress)
                    .build());
        } catch (Exception e) {
            log.debug("[RESEARCH-MIRROR] download skipped: {}", e.getMessage());
        }
    }

    public List<ResearchDownloadEntity> recentDownloads(UUID researchId, int pageSize) {
        return downloadRepo.recent(researchId, pageSize);
    }

    // ── Saves ───────────────────────────────────────────────────────────────

    @Async
    public void mirrorSaveAdded(UUID researchId, UUID userId, String collection) {
        try {
            Instant now = Instant.now();
            saveByUserRepo.save(ResearchSaveByUserEntity.builder()
                    .userId(userId).createdAt(now).researchId(researchId)
                    .collectionName(collection).build());
            saveLookupRepo.save(ResearchSaveLookupEntity.builder()
                    .researchId(researchId).userId(userId).createdAt(now).build());
        } catch (Exception e) {
            log.debug("[RESEARCH-MIRROR] save add skipped: {}", e.getMessage());
        }
    }

    @Async
    public void mirrorSaveRemoved(UUID researchId, UUID userId) {
        try {
            Optional<ResearchSaveLookupEntity> existing = saveLookupRepo.find(researchId, userId);
            saveLookupRepo.delete(researchId, userId);
            existing.ifPresent(s ->
                    saveByUserRepo.delete(userId, s.getCreatedAt(), researchId));
        } catch (Exception e) {
            log.debug("[RESEARCH-MIRROR] save remove skipped: {}", e.getMessage());
        }
    }

    public boolean isSaved(UUID researchId, UUID userId) {
        return saveLookupRepo.find(researchId, userId).isPresent();
    }

    // ── Comment likes ───────────────────────────────────────────────────────

    @Async
    public void mirrorCommentLikeAdded(UUID commentId, UUID userId) {
        try {
            commentLikeRepo.save(ResearchCommentLikeEntity.builder()
                    .commentId(commentId).userId(userId)
                    .createdAt(Instant.now()).build());
        } catch (Exception e) {
            log.debug("[RESEARCH-MIRROR] comment like add skipped: {}", e.getMessage());
        }
    }

    @Async
    public void mirrorCommentLikeRemoved(UUID commentId, UUID userId) {
        try {
            commentLikeRepo.delete(commentId, userId);
        } catch (Exception e) {
            log.debug("[RESEARCH-MIRROR] comment like remove skipped: {}", e.getMessage());
        }
    }
}

package ak.dev.irc.app.qna.cassandra.service;

import ak.dev.irc.app.qna.cassandra.entity.QnaReactionByAnswerEntity;
import ak.dev.irc.app.qna.cassandra.entity.QnaReactionByUserEntity;
import ak.dev.irc.app.qna.cassandra.entity.QuestionSaveByUserEntity;
import ak.dev.irc.app.qna.cassandra.entity.QuestionSaveLookupEntity;
import ak.dev.irc.app.qna.cassandra.entity.QuestionViewEntity;
import ak.dev.irc.app.qna.cassandra.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Cassandra-side mirror for Q&amp;A engagement (answer reactions, question
 * views, question saves).
 *
 * <p>{@link ak.dev.irc.app.qna.service.impl.QuestionServiceImpl} continues to
 * own the Postgres canonical writes (so the JPA transaction model + accept-
 * best-answer atomicity stays intact). Each of those write paths should call
 * the matching {@code mirror*} method here — fire-and-forget {@code @Async}
 * so Postgres latency is unchanged. Once dashboards confirm Cassandra parity,
 * the JPA engagement tables can be dropped and reads can flip over.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CassandraQnaEngagementService {

    private final QnaReactionByAnswerRepository  reactionByAnswerRepo;
    private final QnaReactionByUserRepository    reactionByUserRepo;
    private final QuestionViewRepository         viewRepo;
    private final QuestionSaveByUserRepository   saveByUserRepo;
    private final QuestionSaveLookupRepository   saveLookupRepo;

    // ── Reactions ────────────────────────────────────────────────────────────

    @Async
    public void mirrorReactionAdded(UUID answerId, UUID userId, String reactionType) {
        try {
            Instant now = Instant.now();
            reactionByAnswerRepo.save(QnaReactionByAnswerEntity.builder()
                    .answerId(answerId).userId(userId)
                    .reactionType(reactionType).createdAt(now).build());
            reactionByUserRepo.save(QnaReactionByUserEntity.builder()
                    .userId(userId).createdAt(now).answerId(answerId)
                    .reactionType(reactionType).build());
        } catch (Exception e) {
            log.debug("[QNA-MIRROR] reaction add skipped: {}", e.getMessage());
        }
    }

    @Async
    public void mirrorReactionRemoved(UUID answerId, UUID userId) {
        try {
            Optional<QnaReactionByAnswerEntity> existing =
                    reactionByAnswerRepo.find(answerId, userId);
            reactionByAnswerRepo.delete(answerId, userId);
            // We need the user-side row's createdAt to delete it (clustering key);
            // existing.getCreatedAt() supplies it.
            existing.ifPresent(r ->
                    reactionByUserRepo.delete(userId, r.getCreatedAt(), answerId));
        } catch (Exception e) {
            log.debug("[QNA-MIRROR] reaction remove skipped: {}", e.getMessage());
        }
    }

    public List<QnaReactionByUserEntity> recentReactionsFor(UUID userId, int pageSize) {
        return reactionByUserRepo.recent(userId, pageSize);
    }

    public boolean hasReacted(UUID answerId, UUID userId) {
        return reactionByAnswerRepo.find(answerId, userId).isPresent();
    }

    // ── Views ───────────────────────────────────────────────────────────────

    @Async
    public void mirrorView(UUID questionId, UUID userId) {
        try {
            if (viewRepo.find(questionId, userId).isPresent()) return;
            viewRepo.save(QuestionViewEntity.builder()
                    .questionId(questionId).userId(userId)
                    .firstViewedAt(Instant.now()).build());
        } catch (Exception e) {
            log.debug("[QNA-MIRROR] view skipped: {}", e.getMessage());
        }
    }

    // ── Saves ───────────────────────────────────────────────────────────────

    @Async
    public void mirrorSaveAdded(UUID questionId, UUID userId, String collection) {
        try {
            Instant now = Instant.now();
            saveByUserRepo.save(QuestionSaveByUserEntity.builder()
                    .userId(userId).createdAt(now).questionId(questionId)
                    .collectionName(collection).build());
            saveLookupRepo.save(QuestionSaveLookupEntity.builder()
                    .questionId(questionId).userId(userId).createdAt(now).build());
        } catch (Exception e) {
            log.debug("[QNA-MIRROR] save add skipped: {}", e.getMessage());
        }
    }

    @Async
    public void mirrorSaveRemoved(UUID questionId, UUID userId) {
        try {
            Optional<QuestionSaveLookupEntity> existing =
                    saveLookupRepo.find(questionId, userId);
            saveLookupRepo.delete(questionId, userId);
            existing.ifPresent(s ->
                    saveByUserRepo.delete(userId, s.getCreatedAt(), questionId));
        } catch (Exception e) {
            log.debug("[QNA-MIRROR] save remove skipped: {}", e.getMessage());
        }
    }

    public boolean isSaved(UUID questionId, UUID userId) {
        return saveLookupRepo.find(questionId, userId).isPresent();
    }

    public List<QuestionSaveByUserEntity> savesFor(UUID userId, int pageSize) {
        return saveByUserRepo.firstPage(userId, pageSize);
    }
}

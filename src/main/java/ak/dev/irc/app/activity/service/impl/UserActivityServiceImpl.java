package ak.dev.irc.app.activity.service.impl;

import ak.dev.irc.app.activity.cassandra.entity.ActivityLookupEntity;
import ak.dev.irc.app.activity.cassandra.entity.UserActivityByTypeEntity;
import ak.dev.irc.app.activity.cassandra.entity.UserActivityEntity;
import ak.dev.irc.app.activity.cassandra.repository.ActivityLookupRepository;
import ak.dev.irc.app.activity.cassandra.repository.UserActivityByTypeRepository;
import ak.dev.irc.app.activity.cassandra.repository.UserActivityCassandraRepository;
import ak.dev.irc.app.activity.dto.UserActivityResponse;
import ak.dev.irc.app.activity.enums.UserActivityType;
import ak.dev.irc.app.activity.mapper.UserActivityMapper;
import ak.dev.irc.app.activity.realtime.UserActivityRealtimeBroadcaster;
import ak.dev.irc.app.activity.realtime.UserActivityRealtimeEvent;
import ak.dev.irc.app.activity.service.UserActivityService;
import ak.dev.irc.app.common.exception.ForbiddenException;
import ak.dev.irc.app.common.exception.ResourceNotFoundException;
import ak.dev.irc.app.post.enums.PostReactionType;
import ak.dev.irc.app.qna.enums.AnswerReactionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Cassandra-backed user-activity history. The service contract, the
 * controller paths, and the response shape are unchanged — only the
 * storage moved.
 *
 * <p>Each {@code record*} method writes to three tables:</p>
 * <ul>
 *   <li>{@code activity_by_user}          — the "all my activity" feed</li>
 *   <li>{@code activity_by_user_and_type} — the per-type filter feed</li>
 *   <li>{@code activity_lookup}           — point lookup by activity_id (delete path)</li>
 * </ul>
 *
 * <p>Pagination is cursor-based under the hood; the {@code Pageable} signature
 * on the read API is preserved for backwards compatibility — the client
 * receives a {@link PageImpl} whose {@code totalElements} is approximate
 * (Cassandra has no cheap partition count). Subsequent pages should be driven
 * by the {@code createdAt} of the last returned row.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserActivityServiceImpl implements UserActivityService {

    private static final int QUERY_MAX_LEN = 200;

    private final UserActivityCassandraRepository activityRepo;
    private final UserActivityByTypeRepository    byTypeRepo;
    private final ActivityLookupRepository        lookupRepo;
    private final UserActivityMapper              mapper;
    private final UserActivityRealtimeBroadcaster realtimeBroadcaster;

    // ── Reads ────────────────────────────────────────────────────────────────

    @Override
    public Page<UserActivityResponse> listMyActivity(UUID userId, UserActivityType filter,
                                                     Pageable pageable) {
        // Back-compat shim: delegate to the multi-filter overload.
        Set<UserActivityType> single = filter == null ? null : Set.of(filter);
        return listMyActivity(userId, single, null, null, pageable);
    }

    @Override
    public Page<UserActivityResponse> listMyActivity(UUID userId,
                                                     Collection<UserActivityType> types,
                                                     Instant from,
                                                     Instant to,
                                                     Pageable pageable) {
        int pageSize = pageable.getPageSize();
        boolean noTypes = types == null || types.isEmpty();
        boolean hasFrom = from != null;
        boolean hasTo   = to   != null;

        List<UserActivityResponse> content;

        if (noTypes) {
            // ─ All types path: scan activity_by_user ────────────────────
            List<UserActivityEntity> rows;
            if (hasFrom && hasTo)         rows = activityRepo.firstPageBetween(userId, from, to, pageSize);
            else if (hasFrom)             rows = activityRepo.firstPageSince(userId, from, pageSize);
            else if (hasTo)               rows = activityRepo.firstPageUntil(userId, to, pageSize);
            else                          rows = activityRepo.firstPage(userId, pageSize);
            content = rows.stream().map(mapper::toResponse).toList();
        } else if (types.size() == 1) {
            // ─ Single type path: scan activity_by_user_and_type ─────────
            String typeName = types.iterator().next().name();
            List<UserActivityByTypeEntity> rows;
            if (hasFrom && hasTo)         rows = byTypeRepo.firstPageBetween(userId, typeName, from, to, pageSize);
            else if (hasFrom)             rows = byTypeRepo.firstPageSince(userId, typeName, from, pageSize);
            else if (hasTo)               rows = byTypeRepo.firstPageUntil(userId, typeName, to, pageSize);
            else                          rows = byTypeRepo.firstPage(userId, typeName, pageSize);
            content = rows.stream().map(mapper::toResponseByType).toList();
        } else {
            // ─ Multi-type union: scan each type partition, merge & trim ─
            // Cassandra can't OR across partition keys in one query, so we
            // do N small partition scans (one per type) and k-way merge by
            // createdAt DESC. Each per-type scan honours the date range.
            List<UserActivityByTypeEntity> merged = new ArrayList<>(pageSize * types.size());
            for (UserActivityType t : new HashSet<>(types)) {
                List<UserActivityByTypeEntity> page;
                if (hasFrom && hasTo)     page = byTypeRepo.firstPageBetween(userId, t.name(), from, to, pageSize);
                else if (hasFrom)         page = byTypeRepo.firstPageSince(userId, t.name(), from, pageSize);
                else if (hasTo)           page = byTypeRepo.firstPageUntil(userId, t.name(), to, pageSize);
                else                      page = byTypeRepo.firstPage(userId, t.name(), pageSize);
                merged.addAll(page);
            }
            merged.sort(Comparator.comparing(UserActivityByTypeEntity::getCreatedAt,
                                             Comparator.reverseOrder()));
            content = merged.stream()
                    .limit(pageSize)
                    .map(mapper::toResponseByType)
                    .toList();
        }
        return new PageImpl<>(content, pageable, content.size());
    }

    @Override
    public void deleteOne(UUID userId, UUID activityId) {
        ActivityLookupEntity meta = lookupRepo.findById(activityId)
                .orElseThrow(() -> new ResourceNotFoundException("UserActivity", "id", activityId));
        if (!meta.getUserId().equals(userId)) {
            throw new ForbiddenException("You cannot delete another user's activity");
        }
        activityRepo.delete(meta.getUserId(), meta.getCreatedAt(), activityId);
        byTypeRepo.delete(meta.getUserId(), meta.getActivityType(),
                          meta.getCreatedAt(), activityId);
        lookupRepo.deleteById(activityId);
    }

    @Override
    public int deleteAll(UUID userId, UserActivityType filter) {
        final int BATCH = 200;
        final int MAX_BATCHES = 50;     // up to 10k rows per call
        int deleted = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            List<UserActivityEntity> page = activityRepo.firstPage(userId, BATCH);
            if (page.isEmpty()) break;
            if (filter != null) {
                page = page.stream()
                        .filter(r -> filter.name().equals(r.getActivityType()))
                        .toList();
            }
            if (page.isEmpty()) break;
            for (UserActivityEntity row : page) {
                deleteOneInternal(row);
                deleted++;
            }
            if (page.size() < BATCH) break;
        }
        return deleted;
    }

    private void deleteOneInternal(UserActivityEntity row) {
        try {
            activityRepo.delete(row.getUserId(), row.getCreatedAt(), row.getActivityId());
            byTypeRepo.delete(row.getUserId(), row.getActivityType(),
                              row.getCreatedAt(), row.getActivityId());
            lookupRepo.deleteById(row.getActivityId());
        } catch (Exception e) {
            log.warn("[ACTIVITY] bulk delete row {} failed: {}", row.getActivityId(), e.getMessage());
        }
    }

    // ── Record methods ──────────────────────────────────────────────────────

    @Override @Async
    public void recordPostCreated(UUID userId, UUID postId, String postType) {
        if (userId == null || postId == null) return;
        // postType is reused on the reactionType column to avoid a schema
        // change — the mapper / frontend reads it back via the same field
        // when activityType == POST_CREATED. Cheap denormalisation.
        write(userId, UserActivityType.POST_CREATED,
              b -> { b.postId(postId);
                     b.reactionType(postType); });
    }

    @Override
    public void recordPostReaction(UUID userId, UUID postId, PostReactionType reactionType) {
        write(userId, UserActivityType.POST_REACTION,
              b -> { b.postId(postId);
                     b.reactionType(reactionType == null ? null : reactionType.name()); });
    }

    @Override
    public void recordPostComment(UUID userId, UUID postId, UUID commentId) {
        write(userId, UserActivityType.POST_COMMENT,
              b -> { b.postId(postId); b.commentId(commentId); });
    }

    @Override
    public void recordPostCommentReaction(UUID userId, UUID postId, UUID commentId,
                                          PostReactionType reactionType) {
        write(userId, UserActivityType.POST_COMMENT_REACTION,
              b -> { b.postId(postId); b.commentId(commentId);
                     b.reactionType(reactionType == null ? null : reactionType.name()); });
    }

    @Override
    public void recordPostShare(UUID userId, UUID postId) {
        write(userId, UserActivityType.POST_SHARE, b -> b.postId(postId));
    }

    @Override @Async
    public void recordPostSaved(UUID userId, UUID postId) {
        if (userId == null || postId == null) return;
        write(userId, UserActivityType.POST_SAVED, b -> b.postId(postId));
    }

    @Override
    public void recordReelWatch(UUID userId, UUID postId, Integer watchedSeconds) {
        write(userId, UserActivityType.REEL_WATCH,
              b -> { b.postId(postId); b.watchedSeconds(watchedSeconds); });
    }

    @Override @Async
    public void recordGlobalSearch(UUID userId, String query, String searchScope, int hitCount) {
        if (userId == null || query == null || query.isBlank()) return;
        write(userId, UserActivityType.GLOBAL_SEARCH,
              b -> { b.query(truncate(query, QUERY_MAX_LEN));
                     b.searchScope(truncate(searchScope, 120));
                     b.hitCount(hitCount); });
    }

    @Override @Async
    public void recordHashtagSearch(UUID userId, String tag, int hitCount) {
        if (userId == null || tag == null || tag.isBlank()) return;
        String normalized = tag.startsWith("#") ? tag : "#" + tag;
        write(userId, UserActivityType.HASHTAG_SEARCH,
              b -> { b.query(truncate(normalized, QUERY_MAX_LEN)); b.hitCount(hitCount); });
    }

    @Override @Async
    public void recordMentionLookup(UUID userId, String query, UUID targetUserId, int hitCount) {
        if (userId == null) return;
        write(userId, UserActivityType.MENTION_LOOKUP,
              b -> { b.query(truncate(query, QUERY_MAX_LEN));
                     b.targetUserId(targetUserId); b.hitCount(hitCount); });
    }

    @Override @Async
    public void recordProfileView(UUID viewerId, UUID profileUserId) {
        if (viewerId == null || profileUserId == null || viewerId.equals(profileUserId)) return;
        write(viewerId, UserActivityType.PROFILE_VIEW, b -> b.targetUserId(profileUserId));
    }

    @Override @Async
    public void recordUserMentioned(UUID mentionedUserId, UUID mentionerId,
                                    String sourceType, UUID sourceId) {
        if (mentionedUserId == null || mentionerId == null) return;
        if (mentionedUserId.equals(mentionerId)) return;     // self-mention noise
        // Recipient is the activity owner. The mentioner is denormalised onto
        // target_user_id; the source post/comment/question/answer/research id
        // is denormalised onto post_id (when sourceType POST/POST_COMMENT) or
        // question_id (QUESTION/QUESTION_ANSWER) or research_id otherwise.
        write(mentionedUserId, UserActivityType.USER_MENTIONED, b -> {
            b.targetUserId(mentionerId);
            b.query(truncate(sourceType, 60));      // re-use the query column as the source-type label
            if (sourceType == null) {
                b.postId(sourceId);
            } else switch (sourceType) {
                case "POST", "POST_COMMENT" -> b.postId(sourceId);
                case "QUESTION", "QUESTION_ANSWER" -> b.questionId(sourceId);
                case "RESEARCH", "RESEARCH_COMMENT" -> b.researchId(sourceId);
                default -> b.postId(sourceId);
            }
        });
    }

    @Override @Async
    public void recordFollowedUser(UUID actorId, UUID targetUserId) {
        if (actorId == null || targetUserId == null) return;
        if (actorId.equals(targetUserId)) return;
        write(actorId, UserActivityType.FOLLOWED_USER, b -> b.targetUserId(targetUserId));
    }

    @Override @Async
    public void recordQnaQuestionCreated(UUID userId, UUID questionId) {
        if (userId == null || questionId == null) return;
        write(userId, UserActivityType.QNA_QUESTION_CREATED, b -> b.questionId(questionId));
    }

    @Override @Async
    public void recordQnaQuestionSaved(UUID userId, UUID questionId) {
        if (userId == null || questionId == null) return;
        write(userId, UserActivityType.QNA_QUESTION_SAVED, b -> b.questionId(questionId));
    }

    @Override @Async
    public void recordQnaAnswerCreated(UUID userId, UUID questionId, UUID answerId, UUID parentAnswerId) {
        if (userId == null || answerId == null) return;
        UserActivityType type = parentAnswerId != null
                ? UserActivityType.QNA_REANSWER_CREATED
                : UserActivityType.QNA_ANSWER_CREATED;
        write(userId, type, b -> { b.questionId(questionId); b.answerId(answerId); });
    }

    @Override @Async
    public void recordQnaAnswerReaction(UUID userId, UUID questionId, UUID answerId,
                                        AnswerReactionType reactionType) {
        if (userId == null || answerId == null) return;
        write(userId, UserActivityType.QNA_ANSWER_REACTION,
              b -> { b.questionId(questionId); b.answerId(answerId);
                     b.qnaReactionType(reactionType == null ? null : reactionType.name()); });
    }

    @Override @Async
    public void recordQnaBestAnswerVote(UUID voterId, UUID questionId, UUID answerId, boolean voted) {
        if (voterId == null || answerId == null) return;
        write(voterId, UserActivityType.QNA_BEST_ANSWER_VOTE,
              b -> { b.questionId(questionId); b.answerId(answerId); });
    }

    @Override @Async
    public void recordQnaAnswerFeedback(UUID userId, UUID questionId, UUID answerId) {
        if (userId == null || answerId == null) return;
        write(userId, UserActivityType.QNA_ANSWER_FEEDBACK,
              b -> { b.questionId(questionId); b.answerId(answerId); });
    }

    @Override @Async
    public void recordResearchPublished(UUID userId, UUID researchId) {
        if (userId == null || researchId == null) return;
        write(userId, UserActivityType.RESEARCH_PUBLISHED, b -> b.researchId(researchId));
    }

    @Override @Async
    public void recordResearchSaved(UUID userId, UUID researchId) {
        if (userId == null || researchId == null) return;
        write(userId, UserActivityType.RESEARCH_SAVED, b -> b.researchId(researchId));
    }

    @Override @Async
    public void recordResearchReaction(UUID userId, UUID researchId) {
        if (userId == null || researchId == null) return;
        write(userId, UserActivityType.RESEARCH_REACTION, b -> b.researchId(researchId));
    }

    @Override @Async
    public void recordResearchComment(UUID userId, UUID researchId, UUID commentId) {
        if (userId == null || researchId == null || commentId == null) return;
        write(userId, UserActivityType.RESEARCH_COMMENT,
              b -> { b.researchId(researchId); b.researchCommentId(commentId); });
    }

    @Override @Async
    public void recordResearchCommentReaction(UUID userId, UUID researchId, UUID commentId) {
        if (userId == null || researchId == null || commentId == null) return;
        write(userId, UserActivityType.RESEARCH_COMMENT_REACTION,
              b -> { b.researchId(researchId); b.researchCommentId(commentId); });
    }

    // ── Core write primitive ────────────────────────────────────────────────

    private void write(UUID userId, UserActivityType type,
                       Consumer<UserActivityEntity.UserActivityEntityBuilder> customizer) {
        UUID    id  = UUID.randomUUID();
        Instant now = Instant.now();

        UserActivityEntity.UserActivityEntityBuilder b = UserActivityEntity.builder()
                .userId(userId).createdAt(now).activityId(id)
                .activityType(type.name());
        customizer.accept(b);
        UserActivityEntity row = b.build();
        activityRepo.save(row);

        byTypeRepo.save(UserActivityByTypeEntity.builder()
                .userId(userId).activityType(type.name())
                .createdAt(now).activityId(id)
                .postId(row.getPostId()).commentId(row.getCommentId())
                .reactionType(row.getReactionType())
                .watchedSeconds(row.getWatchedSeconds())
                .query(row.getQuery()).searchScope(row.getSearchScope())
                .hitCount(row.getHitCount())
                .targetUserId(row.getTargetUserId())
                .questionId(row.getQuestionId()).answerId(row.getAnswerId())
                .qnaReactionType(row.getQnaReactionType())
                .researchId(row.getResearchId()).researchCommentId(row.getResearchCommentId())
                .build());

        lookupRepo.save(ActivityLookupEntity.builder()
                .activityId(id).userId(userId)
                .activityType(type.name()).createdAt(now)
                .build());

        broadcast(row);
    }

    private void broadcast(UserActivityEntity row) {
        try {
            realtimeBroadcaster.broadcast(row.getUserId(),
                    UserActivityRealtimeEvent.builder()
                            .userId(row.getUserId())
                            .activityId(row.getActivityId())
                            .activityType(UserActivityType.valueOf(row.getActivityType()))
                            .timestamp(row.getCreatedAt() == null ? null
                                    : java.time.LocalDateTime.ofInstant(row.getCreatedAt(),
                                                                        java.time.ZoneOffset.UTC))
                            .build());
        } catch (Exception e) {
            log.debug("[ACTIVITY] realtime broadcast skipped: {}", e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}

package ak.dev.irc.app.qna.repository;

import ak.dev.irc.app.qna.entity.QuestionSave;
import ak.dev.irc.app.qna.entity.QuestionSaveId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public interface QuestionSaveRepository extends JpaRepository<QuestionSave, QuestionSaveId> {

    boolean existsById(QuestionSaveId id);

    // ── Moderation-aware bookmark listings (docs/moderation/) ───────────────
    // A bookmark outlives the state of the thing it points at: a question saved
    // while it was published can be edited back into review, or rejected
    // outright. The saved list renders the full title and body, so without the
    // same predicate the feed queries carry it would hand held text to a reader
    // who is not its author — the one surface where a stale bookmark, not a
    // feed query, is the delivery mechanism. The author carve-out is kept so a
    // user who bookmarked their own question still sees it.

    @Query(value = """
        SELECT s FROM QuestionSave s
        WHERE s.user.id = :userId
          AND (s.question.moderationStatus IS NULL
               OR s.question.moderationStatus = ak.dev.irc.app.moderation.enums.ModerationStatus.APPROVED
               OR s.question.author.id = :userId)
        ORDER BY s.createdAt DESC
        """,
        countQuery = """
        SELECT COUNT(s) FROM QuestionSave s
        WHERE s.user.id = :userId
          AND (s.question.moderationStatus IS NULL
               OR s.question.moderationStatus = ak.dev.irc.app.moderation.enums.ModerationStatus.APPROVED
               OR s.question.author.id = :userId)
        """)
    Page<QuestionSave> findVisibleByUserId(@Param("userId") UUID userId, Pageable pageable);

    @Query(value = """
        SELECT s FROM QuestionSave s
        WHERE s.user.id = :userId
          AND s.collectionName = :collectionName
          AND (s.question.moderationStatus IS NULL
               OR s.question.moderationStatus = ak.dev.irc.app.moderation.enums.ModerationStatus.APPROVED
               OR s.question.author.id = :userId)
        ORDER BY s.createdAt DESC
        """,
        countQuery = """
        SELECT COUNT(s) FROM QuestionSave s
        WHERE s.user.id = :userId
          AND s.collectionName = :collectionName
          AND (s.question.moderationStatus IS NULL
               OR s.question.moderationStatus = ak.dev.irc.app.moderation.enums.ModerationStatus.APPROVED
               OR s.question.author.id = :userId)
        """)
    Page<QuestionSave> findVisibleByUserIdAndCollectionName(@Param("userId") UUID userId,
                                                            @Param("collectionName") String collectionName,
                                                            Pageable pageable);

    @Query("""
        SELECT DISTINCT s.collectionName FROM QuestionSave s
        WHERE s.user.id = :userId
        ORDER BY s.collectionName ASC
    """)
    List<String> findDistinctCollectionNamesByUserId(@Param("userId") UUID userId);

    /** Batch lookup so feed renders can mark isSaved per question without N+1 round trips. */
    @Query("""
        SELECT s.id.questionId FROM QuestionSave s
        WHERE s.user.id = :userId AND s.id.questionId IN :questionIds
    """)
    Set<UUID> findSavedQuestionIds(@Param("userId") UUID userId,
                                   @Param("questionIds") List<UUID> questionIds);

    @Modifying
    @Query("""
        UPDATE QuestionSave s SET s.collectionName = :newName
        WHERE s.user.id = :userId AND s.collectionName = :oldName
    """)
    int renameCollection(@Param("userId") UUID userId,
                         @Param("oldName") String oldName,
                         @Param("newName") String newName);

    /** Source-of-truth count for the reconciler to rebuild {@code question.saveCount}. */
    @Query("SELECT COUNT(s) FROM QuestionSave s WHERE s.id.questionId = :questionId")
    long countByQuestionId(@Param("questionId") UUID questionId);

    /** Cascade purge — used when the parent question is hard-deleted. */
    @Modifying
    @Query("DELETE FROM QuestionSave s WHERE s.id.questionId = :questionId")
    int deleteAllByQuestionId(@Param("questionId") UUID questionId);
}

package ak.dev.irc.app.qna.repository;

import ak.dev.irc.app.qna.entity.Question;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface QuestionRepository extends JpaRepository<Question, UUID> {

    Page<Question> findByDeletedAtIsNullOrderByCreatedAtDesc(Pageable pageable);

    // Cursor-paginated feed — O(log n) deep paging that does not degrade as
    // the user scrolls. Split into two methods so :cursor has a concrete type
    // bound on Postgres.
    @Query("""
        SELECT q FROM Question q
        WHERE q.deletedAt IS NULL
        ORDER BY q.createdAt DESC
        """)
    List<Question> findFeedFirstPage(Pageable pageable);

    @Query("""
        SELECT q FROM Question q
        WHERE q.deletedAt IS NULL
          AND q.createdAt < :cursor
        ORDER BY q.createdAt DESC
        """)
    List<Question> findFeedAfter(@Param("cursor") java.time.LocalDateTime cursor, Pageable pageable);

    // Following feed: questions from followed users
    @Query("""
        SELECT q FROM Question q
        WHERE q.author.id IN :authorIds
          AND q.deletedAt IS NULL
        ORDER BY q.createdAt DESC
        """)
    Page<Question> findFollowingFeed(@Param("authorIds") List<UUID> authorIds, Pageable pageable);

    Page<Question> findByAuthorIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID authorId, Pageable pageable);

    Optional<Question> findByIdAndDeletedAtIsNull(UUID id);
}
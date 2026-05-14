package ak.dev.irc.app.post.repository;

import ak.dev.irc.app.post.entity.PostView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Repository
public interface PostViewRepository extends JpaRepository<PostView, PostView.PostViewId> {

    /**
     * Atomic claim-this-pair-or-skip primitive. Postgres
     * {@code INSERT ... ON CONFLICT DO NOTHING} → returns 1 if the row was
     * actually inserted (i.e. this is the user's first ever view of the post),
     * 0 if the pair already existed.
     *
     * <p>Caller increments {@code post.view_count} only when this returns 1.
     * Race-safe under concurrent first-views from the same user — exactly one
     * caller sees the {@code 1} return.
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO post_views (post_id, user_id, first_viewed_at)
        VALUES (:postId, :userId, NOW())
        ON CONFLICT (post_id, user_id) DO NOTHING
        """, nativeQuery = true)
    int tryRecord(@Param("postId") UUID postId, @Param("userId") UUID userId);

    /** Source-of-truth count for the per-post unique-viewer reconciler. */
    @Query("SELECT COUNT(v) FROM PostView v WHERE v.id.postId = :postId")
    long countByPostId(@Param("postId") UUID postId);

    boolean existsByIdPostIdAndIdUserId(UUID postId, UUID userId);
}

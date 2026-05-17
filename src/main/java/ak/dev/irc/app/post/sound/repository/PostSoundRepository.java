package ak.dev.irc.app.post.sound.repository;

import ak.dev.irc.app.post.sound.entity.PostSound;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PostSoundRepository extends JpaRepository<PostSound, UUID> {

    Optional<PostSound> findByPostId(UUID postId);

    Optional<PostSound> findByStoryId(UUID storyId);

    @Query(value = """
        SELECT ps.sound_id FROM post_sounds ps
        WHERE ps.post_id IN (
            SELECT p.id FROM posts p WHERE p.author_id = :userId
            UNION
            SELECT s.id FROM stories s WHERE s.author_id = :userId
        )
        GROUP BY ps.sound_id
        ORDER BY MAX(ps.created_at) DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<UUID> findRecentlyUsedSoundIdsByUser(@Param("userId") UUID userId,
                                              @Param("limit") int limit);
}

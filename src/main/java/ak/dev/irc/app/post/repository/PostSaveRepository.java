package ak.dev.irc.app.post.repository;

import ak.dev.irc.app.post.entity.PostSave;
import ak.dev.irc.app.post.entity.PostSaveId;
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
public interface PostSaveRepository extends JpaRepository<PostSave, PostSaveId> {

    boolean existsById(PostSaveId id);

    Page<PostSave> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<PostSave> findByUserIdAndCollectionNameOrderByCreatedAtDesc(
            UUID userId, String collectionName, Pageable pageable);

    @Query("""
        SELECT DISTINCT s.collectionName FROM PostSave s
        WHERE s.user.id = :userId
        ORDER BY s.collectionName ASC
    """)
    List<String> findDistinctCollectionNamesByUserId(@Param("userId") UUID userId);

    /** Batch lookup so feed renders can mark isSaved per post without N+1 round trips. */
    @Query("""
        SELECT s.id.postId FROM PostSave s
        WHERE s.user.id = :userId AND s.id.postId IN :postIds
    """)
    Set<UUID> findSavedPostIds(@Param("userId") UUID userId,
                               @Param("postIds") List<UUID> postIds);

    @Modifying
    @Query("""
        UPDATE PostSave s SET s.collectionName = :newName
        WHERE s.user.id = :userId AND s.collectionName = :oldName
    """)
    int renameCollection(@Param("userId") UUID userId,
                         @Param("oldName") String oldName,
                         @Param("newName") String newName);
}

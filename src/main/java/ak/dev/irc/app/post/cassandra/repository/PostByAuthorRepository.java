package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.PostByAuthorEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PostByAuthorRepository extends CassandraRepository<PostByAuthorEntity, MapId> {

    /** First page of the author's profile feed (newest first). */
    @Query("SELECT * FROM posts_by_author WHERE author_id = :authorId LIMIT :pageSize")
    List<PostByAuthorEntity> firstPage(@Param("authorId") UUID authorId,
                                       @Param("pageSize") int pageSize);

    /** Next page — pass the createdAt of the last row from the previous slice. */
    @Query("SELECT * FROM posts_by_author WHERE author_id = :authorId " +
           "AND created_at < :cursor LIMIT :pageSize")
    List<PostByAuthorEntity> nextPage(@Param("authorId") UUID authorId,
                                      @Param("cursor")   java.time.Instant cursor,
                                      @Param("pageSize") int pageSize);

    /** Used to remove the author's own profile-feed row after a post delete. */
    @Query("DELETE FROM posts_by_author WHERE author_id = :authorId " +
           "AND created_at = :createdAt AND post_id = :postId")
    void deleteRow(@Param("authorId") UUID authorId,
                   @Param("createdAt") java.time.Instant createdAt,
                   @Param("postId") UUID postId);
}

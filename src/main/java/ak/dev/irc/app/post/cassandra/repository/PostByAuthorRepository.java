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

    /**
     * REEL-only slice of a single author's partition — used by the following-reels fan-in.
     * ALLOW FILTERING is safe here because the partition key (author_id) is always bound;
     * Cassandra only scans within that one partition, not the full table.
     */
    @Query("SELECT * FROM posts_by_author WHERE author_id = :authorId " +
           "AND post_type = 'REEL' LIMIT :pageSize ALLOW FILTERING")
    List<PostByAuthorEntity> reelsByAuthor(@Param("authorId") UUID authorId,
                                           @Param("pageSize") int pageSize);

    @Query("SELECT * FROM posts_by_author WHERE author_id = :authorId " +
           "AND post_type = 'REEL' AND created_at < :cursor LIMIT :pageSize ALLOW FILTERING")
    List<PostByAuthorEntity> reelsByAuthorAfter(@Param("authorId") UUID authorId,
                                                @Param("cursor")   java.time.Instant cursor,
                                                @Param("pageSize") int pageSize);

    /**
     * Profile-stat count: how many posts the author has. Single-partition COUNT
     * (author_id is bound), so Cassandra scans only this user's partition — not
     * the whole table. Counts every live row in {@code posts_by_author} (a delete
     * removes the row), i.e. published posts incl. reels.
     */
    @Query("SELECT COUNT(*) FROM posts_by_author WHERE author_id = :authorId")
    long countByAuthor(@Param("authorId") UUID authorId);

    /**
     * Profile-stat count: how many of the author's posts are REELs. ALLOW FILTERING
     * is safe — the partition key (author_id) is bound, so only this user's partition
     * is scanned. The non-reel "posts" count is derived as {@code countByAuthor − this}.
     */
    @Query("SELECT COUNT(*) FROM posts_by_author WHERE author_id = :authorId " +
           "AND post_type = 'REEL' ALLOW FILTERING")
    long countReelsByAuthor(@Param("authorId") UUID authorId);

    /** Used to remove the author's own profile-feed row after a post delete. */
    @Query("DELETE FROM posts_by_author WHERE author_id = :authorId " +
           "AND created_at = :createdAt AND post_id = :postId")
    void deleteRow(@Param("authorId") UUID authorId,
                   @Param("createdAt") java.time.Instant createdAt,
                   @Param("postId") UUID postId);
}

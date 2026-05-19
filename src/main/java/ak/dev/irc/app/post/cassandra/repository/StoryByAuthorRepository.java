package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.StoryByAuthorEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface StoryByAuthorRepository extends CassandraRepository<StoryByAuthorEntity, MapId> {

    @Query("SELECT * FROM stories_by_author WHERE author_id = :authorId")
    List<StoryByAuthorEntity> activeStories(@Param("authorId") UUID authorId);

    @Query("DELETE FROM stories_by_author WHERE author_id = :authorId " +
           "AND created_at = :createdAt AND story_id = :storyId")
    void delete(@Param("authorId") UUID authorId,
                @Param("createdAt") Instant createdAt,
                @Param("storyId") UUID storyId);
}

package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.StoryInHighlightEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface StoryInHighlightRepository extends CassandraRepository<StoryInHighlightEntity, MapId> {

    @Query("SELECT * FROM stories_in_highlight WHERE highlight_id = :highlightId")
    List<StoryInHighlightEntity> listFor(@Param("highlightId") UUID highlightId);

    @Query("DELETE FROM stories_in_highlight WHERE highlight_id = :highlightId " +
           "AND created_at = :createdAt AND story_id = :storyId")
    void delete(@Param("highlightId") UUID highlightId,
                @Param("createdAt") Instant createdAt,
                @Param("storyId") UUID storyId);
}

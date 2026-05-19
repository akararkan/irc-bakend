package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.StoryViewEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface StoryViewRepository extends CassandraRepository<StoryViewEntity, MapId> {

    @Query("SELECT * FROM story_views_by_story WHERE story_id = :storyId LIMIT :pageSize")
    List<StoryViewEntity> recent(@Param("storyId") UUID storyId,
                                 @Param("pageSize") int pageSize);
}

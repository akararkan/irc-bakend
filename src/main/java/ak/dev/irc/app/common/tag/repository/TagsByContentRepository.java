package ak.dev.irc.app.common.tag.repository;

import ak.dev.irc.app.common.tag.entity.TagsByContentEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TagsByContentRepository extends CassandraRepository<TagsByContentEntity, MapId> {

    @Query("SELECT * FROM tags_by_content WHERE content_id = :contentId")
    List<TagsByContentEntity> findByContentId(@Param("contentId") UUID contentId);
}

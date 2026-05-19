package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.MediaByPostEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MediaByPostRepository extends CassandraRepository<MediaByPostEntity, MapId> {

    @Query("SELECT * FROM media_by_post WHERE post_id = :postId")
    List<MediaByPostEntity> listFor(@Param("postId") UUID postId);

    @Query("DELETE FROM media_by_post WHERE post_id = :postId " +
           "AND sort_order = :sortOrder AND media_id = :mediaId")
    void delete(@Param("postId") UUID postId,
                @Param("sortOrder") Integer sortOrder,
                @Param("mediaId") UUID mediaId);

    @Query("DELETE FROM media_by_post WHERE post_id = :postId")
    void deleteAllFor(@Param("postId") UUID postId);
}

package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.ShareByPostEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ShareByPostRepository extends CassandraRepository<ShareByPostEntity, MapId> {

    @Query("SELECT * FROM shares_by_post WHERE post_id = :postId LIMIT :pageSize")
    List<ShareByPostEntity> recent(@Param("postId") UUID postId,
                                   @Param("pageSize") int pageSize);
}

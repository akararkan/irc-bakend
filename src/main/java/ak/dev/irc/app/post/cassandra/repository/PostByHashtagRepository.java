package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.PostByHashtagEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface PostByHashtagRepository extends CassandraRepository<PostByHashtagEntity, MapId> {

    @Query("SELECT * FROM posts_by_hashtag WHERE hashtag = :tag LIMIT :pageSize")
    List<PostByHashtagEntity> firstPage(@Param("tag") String tag,
                                        @Param("pageSize") int pageSize);

    @Query("SELECT * FROM posts_by_hashtag WHERE hashtag = :tag " +
           "AND created_at < :cursor LIMIT :pageSize")
    List<PostByHashtagEntity> nextPage(@Param("tag") String tag,
                                       @Param("cursor") Instant cursor,
                                       @Param("pageSize") int pageSize);
}

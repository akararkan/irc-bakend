package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.PostBySoundEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface PostBySoundRepository extends CassandraRepository<PostBySoundEntity, MapId> {

    @Query("DELETE FROM posts_by_sound WHERE sound_id = :soundId")
    void deleteAllForSound(@Param("soundId") UUID soundId);

    @Query("SELECT * FROM posts_by_sound WHERE sound_id = :soundId LIMIT :pageSize")
    List<PostBySoundEntity> firstPage(@Param("soundId") UUID soundId,
                                      @Param("pageSize") int pageSize);

    @Query("SELECT * FROM posts_by_sound WHERE sound_id = :soundId " +
           "AND created_at < :cursor LIMIT :pageSize")
    List<PostBySoundEntity> nextPage(@Param("soundId") UUID soundId,
                                     @Param("cursor") Instant cursor,
                                     @Param("pageSize") int pageSize);
}

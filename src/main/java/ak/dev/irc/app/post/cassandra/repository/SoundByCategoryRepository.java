package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.SoundByCategoryEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface SoundByCategoryRepository extends CassandraRepository<SoundByCategoryEntity, MapId> {

    @Query("DELETE FROM sounds_by_category WHERE category = :category " +
           "AND created_at = :createdAt AND sound_id = :soundId")
    void deleteRow(@Param("category") String category,
                   @Param("createdAt") java.time.Instant createdAt,
                   @Param("soundId") java.util.UUID soundId);

    @Query("SELECT * FROM sounds_by_category WHERE category = :category LIMIT :pageSize")
    List<SoundByCategoryEntity> firstPage(@Param("category") String category,
                                          @Param("pageSize") int pageSize);

    @Query("SELECT * FROM sounds_by_category WHERE category = :category " +
           "AND created_at < :cursor LIMIT :pageSize")
    List<SoundByCategoryEntity> nextPage(@Param("category") String category,
                                         @Param("cursor") Instant cursor,
                                         @Param("pageSize") int pageSize);
}

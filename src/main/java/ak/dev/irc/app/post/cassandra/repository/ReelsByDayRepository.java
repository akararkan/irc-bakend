package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.ReelsByDayEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface ReelsByDayRepository extends CassandraRepository<ReelsByDayEntity, MapId> {

    @Query("SELECT * FROM reels_by_day WHERE day_bucket = :day LIMIT :pageSize")
    List<ReelsByDayEntity> firstPage(@Param("day") String day,
                                     @Param("pageSize") int pageSize);

    @Query("SELECT * FROM reels_by_day WHERE day_bucket = :day " +
           "AND created_at < :cursor LIMIT :pageSize")
    List<ReelsByDayEntity> nextPage(@Param("day")       String day,
                                    @Param("cursor")   Instant cursor,
                                    @Param("pageSize") int pageSize);
}

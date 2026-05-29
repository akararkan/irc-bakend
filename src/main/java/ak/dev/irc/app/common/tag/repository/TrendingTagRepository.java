package ak.dev.irc.app.common.tag.repository;

import ak.dev.irc.app.common.tag.entity.TrendingTagEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TrendingTagRepository extends CassandraRepository<TrendingTagEntity, MapId> {

    /** Top tags for a scope, already ordered by rank ASC (most-used first). */
    @Query("SELECT * FROM trending_tags WHERE scope = :scope LIMIT :limit")
    List<TrendingTagEntity> topForScope(@Param("scope") String scope, @Param("limit") int limit);
}

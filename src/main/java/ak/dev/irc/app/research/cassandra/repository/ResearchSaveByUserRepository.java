package ak.dev.irc.app.research.cassandra.repository;

import ak.dev.irc.app.research.cassandra.entity.ResearchSaveByUserEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface ResearchSaveByUserRepository
        extends CassandraRepository<ResearchSaveByUserEntity, MapId> {

    @Query("SELECT * FROM research_saves_by_user WHERE user_id = :uid LIMIT :pageSize")
    List<ResearchSaveByUserEntity> firstPage(@Param("uid") UUID userId,
                                             @Param("pageSize") int pageSize);

    @Query("DELETE FROM research_saves_by_user WHERE user_id = :uid " +
           "AND created_at = :createdAt AND research_id = :rid")
    void delete(@Param("uid") UUID userId,
                @Param("createdAt") Instant createdAt,
                @Param("rid") UUID researchId);
}

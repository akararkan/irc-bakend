package ak.dev.irc.app.research.cassandra.repository;

import ak.dev.irc.app.research.cassandra.entity.ResearchReactionByUserEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface ResearchReactionByUserRepository
        extends CassandraRepository<ResearchReactionByUserEntity, MapId> {

    @Query("SELECT * FROM research_reactions_by_user WHERE user_id = :userId LIMIT :pageSize")
    List<ResearchReactionByUserEntity> recent(@Param("userId") UUID userId,
                                              @Param("pageSize") int pageSize);

    @Query("DELETE FROM research_reactions_by_user WHERE user_id = :userId " +
           "AND created_at = :createdAt AND research_id = :researchId")
    void delete(@Param("userId") UUID userId,
                @Param("createdAt") Instant createdAt,
                @Param("researchId") UUID researchId);
}

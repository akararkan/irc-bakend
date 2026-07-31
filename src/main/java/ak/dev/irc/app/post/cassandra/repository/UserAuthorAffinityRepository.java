package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.UserAuthorAffinityEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface UserAuthorAffinityRepository
        extends CassandraRepository<UserAuthorAffinityEntity, MapId> {

    /**
     * Affinity rows for one viewer against a page of authors — single
     * partition + clustering IN, one round-trip per feed page.
     */
    @Query("SELECT * FROM user_author_affinity WHERE user_id = :uid AND author_id IN :authorIds")
    List<UserAuthorAffinityEntity> findFor(@Param("uid") UUID userId,
                                           @Param("authorIds") Collection<UUID> authorIds);

    /**
     * Every author this viewer has engaged with — single-partition scan,
     * bounded. Feeds the interaction-graph candidate source for friend
     * suggestions ("people whose content you engage with").
     */
    @Query("SELECT * FROM user_author_affinity WHERE user_id = :uid LIMIT :limit")
    List<UserAuthorAffinityEntity> findAllForUser(@Param("uid") UUID userId,
                                                  @Param("limit") int limit);
}

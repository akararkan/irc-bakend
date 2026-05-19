package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.NotifActiveGroupEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotifActiveGroupRepository extends CassandraRepository<NotifActiveGroupEntity, MapId> {

    @Query("SELECT * FROM notif_active_group_by_user WHERE user_id = :userId AND group_key = :groupKey")
    Optional<NotifActiveGroupEntity> find(@Param("userId") UUID userId,
                                          @Param("groupKey") String groupKey);
}

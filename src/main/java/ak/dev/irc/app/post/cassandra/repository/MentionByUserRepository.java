package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.MentionByUserEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MentionByUserRepository extends CassandraRepository<MentionByUserEntity, MapId> {

    @Query("SELECT * FROM mentions_by_user WHERE mentioned_user_id = :userId LIMIT :pageSize")
    List<MentionByUserEntity> firstPage(@Param("userId") UUID userId,
                                        @Param("pageSize") int pageSize);
}

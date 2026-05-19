package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.CloseFriendEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CloseFriendRepository extends CassandraRepository<CloseFriendEntity, MapId> {

    @Query("SELECT * FROM close_friends_by_owner WHERE owner_id = :ownerId")
    List<CloseFriendEntity> listFor(@Param("ownerId") UUID ownerId);

    @Query("SELECT * FROM close_friends_by_owner WHERE owner_id = :ownerId AND friend_id = :friendId")
    Optional<CloseFriendEntity> find(@Param("ownerId") UUID ownerId,
                                     @Param("friendId") UUID friendId);

    @Query("DELETE FROM close_friends_by_owner WHERE owner_id = :ownerId AND friend_id = :friendId")
    void delete(@Param("ownerId") UUID ownerId,
                @Param("friendId") UUID friendId);
}

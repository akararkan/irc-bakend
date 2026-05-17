package ak.dev.irc.app.user.repository;

import ak.dev.irc.app.user.entity.CloseFriendsList;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Set;
import java.util.UUID;

@Repository
public interface CloseFriendsRepository
        extends JpaRepository<CloseFriendsList, CloseFriendsList.CloseFriendsId> {

    @Query("SELECT cf FROM CloseFriendsList cf WHERE cf.id.ownerId = :ownerId ORDER BY cf.createdAt DESC")
    Page<CloseFriendsList> findByOwnerId(@Param("ownerId") UUID ownerId, Pageable pageable);

    boolean existsByIdOwnerIdAndIdFriendId(UUID ownerId, UUID friendId);

    /** Set of friend IDs for a given owner — used for story visibility filtering. */
    @Query("SELECT cf.id.friendId FROM CloseFriendsList cf WHERE cf.id.ownerId = :ownerId")
    Set<UUID> findFriendIds(@Param("ownerId") UUID ownerId);
}

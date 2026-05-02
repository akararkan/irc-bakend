package ak.dev.irc.app.activity.repository;

import ak.dev.irc.app.activity.entity.UserActivity;
import ak.dev.irc.app.activity.enums.UserActivityType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface UserActivityRepository extends JpaRepository<UserActivity, UUID> {

    Page<UserActivity> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<UserActivity> findByUserIdAndActivityTypeOrderByCreatedAtDesc(
            UUID userId, UserActivityType activityType, Pageable pageable);

    List<UserActivity> findAllByUserId(UUID userId);

    List<UserActivity> findAllByUserIdAndActivityType(UUID userId, UserActivityType activityType);

    @Modifying
    @Query("DELETE FROM UserActivity a WHERE a.user.id = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);

    @Modifying
    @Query("DELETE FROM UserActivity a WHERE a.user.id = :userId AND a.activityType = :activityType")
    int deleteAllByUserIdAndActivityType(@Param("userId") UUID userId,
                                         @Param("activityType") UserActivityType activityType);
}

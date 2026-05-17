package ak.dev.irc.app.user.repository;

import ak.dev.irc.app.user.entity.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {

    @Query("SELECT p FROM UserProfile p WHERE p.user.id = :userId")
    Optional<UserProfile> findByUserId(@Param("userId") UUID userId);

    @Query("SELECT p FROM UserProfile p LEFT JOIN FETCH p.specializations s LEFT JOIN FETCH s.topic WHERE p.user.id = :userId")
    Optional<UserProfile> findByUserIdWithSpecializations(@Param("userId") UUID userId);
}

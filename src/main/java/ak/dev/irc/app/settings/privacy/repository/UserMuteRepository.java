package ak.dev.irc.app.settings.privacy.repository;

import ak.dev.irc.app.settings.privacy.entity.UserMute;
import ak.dev.irc.app.settings.privacy.entity.UserMute.UserMuteId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface UserMuteRepository extends JpaRepository<UserMute, UserMuteId> {

    boolean existsByIdMuterIdAndIdMutedId(UUID muterId, UUID mutedId);

    @Query("SELECT m.id.mutedId FROM UserMute m WHERE m.id.muterId = :muterId")
    List<UUID> findMutedIds(@Param("muterId") UUID muterId);
}

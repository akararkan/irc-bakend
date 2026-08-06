package ak.dev.irc.app.media.repository;

import ak.dev.irc.app.media.entity.MediaQuota;
import ak.dev.irc.app.user.enums.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MediaQuotaRepository extends JpaRepository<MediaQuota, Role> {
}

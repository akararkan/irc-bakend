package ak.dev.irc.app.moderation.repository;

import ak.dev.irc.app.moderation.entity.ModerationSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ModerationSettingRepository extends JpaRepository<ModerationSetting, String> {
}

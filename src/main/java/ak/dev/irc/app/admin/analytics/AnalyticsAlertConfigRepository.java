package ak.dev.irc.app.admin.analytics;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AnalyticsAlertConfigRepository extends JpaRepository<AnalyticsAlertConfig, String> {

    @Query("SELECT c FROM AnalyticsAlertConfig c WHERE c.enabled = TRUE")
    List<AnalyticsAlertConfig> enabledConfigs();
}

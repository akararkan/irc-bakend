package ak.dev.irc.app.admin.logs;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface LogAlertRuleRepository extends JpaRepository<LogAlertRule, UUID> {

    @Query("SELECT r FROM LogAlertRule r WHERE r.enabled = TRUE")
    List<LogAlertRule> enabledRules();
}

package ak.dev.irc.app.admin.analytics;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface MetricAlertRepository extends JpaRepository<MetricAlert, UUID> {

    /** All fired alerts, newest first. */
    @Query(value = "SELECT a FROM MetricAlert a ORDER BY a.createdAt DESC",
           countQuery = "SELECT COUNT(a) FROM MetricAlert a")
    Page<MetricAlert> browse(Pageable pageable);

    @Query("SELECT COUNT(a) FROM MetricAlert a WHERE a.day = :day AND a.metric = :metric")
    long countForDayAndMetric(@Param("day") String day, @Param("metric") String metric);
}

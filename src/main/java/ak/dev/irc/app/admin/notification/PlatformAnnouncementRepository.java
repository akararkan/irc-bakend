package ak.dev.irc.app.admin.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface PlatformAnnouncementRepository extends JpaRepository<PlatformAnnouncement, UUID> {

    /** All announcements, newest first. */
    @Query(value = "SELECT a FROM PlatformAnnouncement a ORDER BY a.createdAt DESC",
           countQuery = "SELECT COUNT(a) FROM PlatformAnnouncement a")
    Page<PlatformAnnouncement> browse(Pageable pageable);

    /** Scheduled announcements whose fire time has arrived (minute sweep). */
    @Query("""
        SELECT a FROM PlatformAnnouncement a
        WHERE a.status = ak.dev.irc.app.admin.notification.PlatformAnnouncement.Status.SCHEDULED
          AND a.scheduledAt <= :now
        ORDER BY a.scheduledAt ASC
        """)
    List<PlatformAnnouncement> dueScheduled(@Param("now") LocalDateTime now);
}

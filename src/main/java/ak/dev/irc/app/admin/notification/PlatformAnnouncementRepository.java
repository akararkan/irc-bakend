package ak.dev.irc.app.admin.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PlatformAnnouncementRepository extends JpaRepository<PlatformAnnouncement, UUID> {

    @org.springframework.data.jpa.repository.Query("""
        SELECT a FROM PlatformAnnouncement a
        ORDER BY a.createdAt DESC
        """)
    Page<PlatformAnnouncement> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Scheduled announcements whose fire time has arrived (minute sweep). */
    @org.springframework.data.jpa.repository.Query("""
        SELECT a FROM PlatformAnnouncement a
        WHERE a.status = ak.dev.irc.app.admin.notification.PlatformAnnouncement.Status.SCHEDULED
          AND a.scheduledAt <= :now
        ORDER BY a.scheduledAt ASC
        """)
    java.util.List<PlatformAnnouncement> dueScheduled(
            @org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now);
}

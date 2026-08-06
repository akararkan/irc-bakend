package ak.dev.irc.app.admin.logs;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SavedLogViewRepository extends JpaRepository<AdminSavedLogView, UUID> {

    @Query("SELECT v FROM AdminSavedLogView v WHERE v.adminId = :adminId ORDER BY v.createdAt")
    List<AdminSavedLogView> forAdmin(@Param("adminId") UUID adminId);
}

package ak.dev.irc.app.settings.data.repository;

import ak.dev.irc.app.settings.data.entity.ExportJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ExportJobRepository extends JpaRepository<ExportJob, UUID> {

    @Query("SELECT j FROM ExportJob j WHERE j.userId = :userId ORDER BY j.createdAt DESC")
    java.util.List<ExportJob> findByUserIdOrderByCreatedAtDesc(@Param("userId") UUID userId);
}

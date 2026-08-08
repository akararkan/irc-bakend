package ak.dev.irc.app.moderation.repository;

import ak.dev.irc.app.moderation.entity.ModerationModelVersion;
import ak.dev.irc.app.moderation.enums.ModelVersionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ModerationModelVersionRepository extends JpaRepository<ModerationModelVersion, UUID> {

    Optional<ModerationModelVersion> findByVersion(String version);

    Optional<ModerationModelVersion> findFirstByStatusOrderByPromotedAtDesc(ModelVersionStatus status);

    List<ModerationModelVersion> findByStatus(ModelVersionStatus status);

    /** In-flight jobs the poller must chase. */
    List<ModerationModelVersion> findByStatusIn(List<ModelVersionStatus> statuses);

    Page<ModerationModelVersion> findAllByOrderByTrainedAtDesc(Pageable pageable);

    boolean existsByStatusIn(List<ModelVersionStatus> statuses);
}

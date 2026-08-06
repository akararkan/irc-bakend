package ak.dev.irc.app.admin.moderation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ModerationEvidenceRepository extends JpaRepository<ModerationEvidence, UUID> {

    List<ModerationEvidence> findByTargetTypeAndTargetRefOrderByCapturedAtDesc(String targetType,
                                                                               String targetRef);
}

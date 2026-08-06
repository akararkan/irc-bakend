package ak.dev.irc.app.admin.activity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BreakGlassCaseRepository extends JpaRepository<BreakGlassCase, UUID> {

    List<BreakGlassCase> findByTargetUserIdAndStatus(UUID targetUserId, BreakGlassCase.Status status);

    Page<BreakGlassCase> findAllByOrderByOpenedAtDesc(Pageable pageable);
}

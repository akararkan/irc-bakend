package ak.dev.irc.app.admin.moderation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Write-side store for the moderation decision log (see {@link ModerationRecorder}). */
public interface ModerationDecisionRepository extends JpaRepository<ModerationDecision, UUID> {
}

package ak.dev.irc.app.admin.moderation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Write-side store for evidence snapshots (see {@link ModerationRecorder}). */
public interface ModerationEvidenceRepository extends JpaRepository<ModerationEvidence, UUID> {
}

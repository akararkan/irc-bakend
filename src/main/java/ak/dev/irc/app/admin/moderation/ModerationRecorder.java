package ak.dev.irc.app.admin.moderation;

import ak.dev.irc.app.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Shared writer for the decision log + evidence store. Best-effort for
 * evidence (a snapshot failure must not block a takedown that policy
 * requires) but decisions are written inline — a verdict without its log row
 * is a defect.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModerationRecorder {

    private final ModerationDecisionRepository decisionRepository;
    private final ModerationEvidenceRepository evidenceRepository;

    public void decision(String targetType, String targetRef, String action,
                         String reason, UUID reportId, String metadata) {
        decisionRepository.save(ModerationDecision.builder()
                .targetType(targetType)
                .targetRef(targetRef)
                .action(action)
                .reason(truncate(reason, 500))
                .reportId(reportId)
                .actorId(SecurityUtils.getCurrentUserId().orElse(null))
                .metadata(truncate(metadata, 500))
                .build());
    }

    public void snapshot(String targetType, String targetRef, UUID authorId,
                         String payload, String mediaRefs) {
        try {
            evidenceRepository.save(ModerationEvidence.builder()
                    .targetType(targetType)
                    .targetRef(targetRef)
                    .authorId(authorId)
                    .payload(payload)
                    .mediaRefs(mediaRefs)
                    .capturedBy(SecurityUtils.getCurrentUserId().orElse(null))
                    .build());
        } catch (Exception e) {
            log.warn("[MODERATION] evidence snapshot failed for {} {}: {}",
                    targetType, targetRef, e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}

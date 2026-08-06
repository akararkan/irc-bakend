package ak.dev.irc.app.settings.safety.dto;

import ak.dev.irc.app.settings.safety.entity.Report;
import ak.dev.irc.app.settings.safety.entity.UserStrike;
import ak.dev.irc.app.settings.safety.enums.ReportReason;
import ak.dev.irc.app.settings.safety.enums.ReportState;
import ak.dev.irc.app.settings.safety.enums.ReportTargetType;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Request/response DTOs for the Safety Center (spec §18). */
public final class SafetyDtos {

    private SafetyDtos() {}

    /**
     * {@code targetId} is optional only for MESSAGE targets, whose chat ids
     * are Snowflake longs carried in {@code targetRef} (the MESSAGE-target
     * defect fix); the service enforces that exactly one is present.
     */
    public record SubmitReportRequest(@NotNull ReportTargetType targetType,
                                      UUID targetId,
                                      String targetRef,
                                      @NotNull ReportReason reason,
                                      String details) {}

    /**
     * Reporter-facing view. Deliberately exposes only a COARSE outcome bucket —
     * never the concrete {@code Resolution} taken against the target.
     */
    public record ReportResponse(UUID id,
                                 String targetType,
                                 UUID targetId,
                                 String reason,
                                 String state,
                                 String coarseOutcome,
                                 LocalDateTime createdAt) {

        public static ReportResponse of(Report r) {
            return new ReportResponse(
                    r.getId(),
                    r.getTargetType().name(),
                    r.getTargetId(),
                    r.getReason().name(),
                    r.getState().name(),
                    coarseOutcome(r.getState()),
                    r.getCreatedAt());
        }

        private static String coarseOutcome(ReportState state) {
            return switch (state) {
                case SUBMITTED, TRIAGED -> "UNDER_REVIEW";
                case ACTIONED, UPHELD   -> "ACTION_TAKEN";
                case DISMISSED, REVERSED -> "NO_ACTION";
                case APPEALED           -> "APPEAL_UNDER_REVIEW";
            };
        }
    }

    public record StrikeResponse(UUID id, String reason,
                                 LocalDateTime issuedAt, LocalDateTime expiresAt) {
        public static StrikeResponse of(UserStrike s) {
            return new StrikeResponse(s.getId(), s.getReason(), s.getIssuedAt(), s.getExpiresAt());
        }
    }

    public record ScoreItem(String key, String label, boolean passed, String recommendation) {}

    public record SecurityScoreResponse(int score, String level, List<ScoreItem> items) {}
}

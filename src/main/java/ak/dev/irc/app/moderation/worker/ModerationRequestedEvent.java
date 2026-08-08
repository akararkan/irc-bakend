package ak.dev.irc.app.moderation.worker;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * "A content unit is held and needs a verdict" — the queue message of
 * MODERATION_ROADMAP.md §11.
 *
 * <p>Carries only the case id, never the text. The text already lives in
 * {@code moderation_case_fields}; putting user content on a broker queue would
 * duplicate it into a store with its own retention, which §15 asks us not to
 * do.</p>
 *
 * <p>Published after commit, so a message is never emitted for a case row whose
 * transaction rolled back (§5.2's transactional-outbox intent).</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModerationRequestedEvent implements Serializable {

    private UUID caseId;
    private String entityType;
    private String entityRef;

    @Builder.Default
    private LocalDateTime occurredAt = LocalDateTime.now();
}

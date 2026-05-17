package ak.dev.irc.app.post.realtime;

import ak.dev.irc.app.post.enums.StoryType;
import ak.dev.irc.app.post.enums.StoryVisibility;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Published to {@code irc:story-tray:{viewerId}} when someone the viewer
 * follows (or is close friends with) posts or removes a story.
 * The client uses this to update the story tray ring without polling.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StoryTrayEvent {

    private StoryTrayEventType eventType;

    // ── Author info ───────────────────────────────────────────────────────────
    private UUID   authorId;
    private String authorUsername;
    private String authorFullName;
    private String authorAvatarUrl;

    // ── Story info ────────────────────────────────────────────────────────────
    private UUID            storyId;
    private StoryType       storyType;
    private StoryVisibility visibility;

    /** Preview thumbnail or background color — shown as the tray ring cover. */
    private String thumbnailUrl;
    private String backgroundValue;

    private LocalDateTime expiresAt;

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
}

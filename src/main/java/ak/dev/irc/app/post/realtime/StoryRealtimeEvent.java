package ak.dev.irc.app.post.realtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StoryRealtimeEvent {

    private StoryRealtimeEventType eventType;
    private UUID   storyId;

    private UUID   actorId;
    private String actorUsername;
    private String actorAvatarUrl;

    private String reactionEmoji;
    private String replyText;

    private String pollChoice;
    private Integer pollVoteACount;
    private Integer pollVoteBCount;

    private Long viewCount;
    private Long reactionCount;
    private Long replyCount;

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
}

package ak.dev.irc.app.chat.dto.response;

import ak.dev.irc.app.post.cassandra.entity.StoryByAuthorEntity;

import java.util.List;
import java.util.UUID;

/** One ring of the channel-story tray: a subscribed channel with live stories. */
public record ChannelStoryTrayItem(
        UUID channelId,
        String handle,
        String title,
        String avatarUrl,
        boolean verified,
        List<StoryByAuthorEntity> stories
) {}

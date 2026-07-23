package ak.dev.irc.app.post.enums;

public enum StoryVisibility {
    PUBLIC,
    FOLLOWERS_ONLY,
    CLOSE_FRIENDS,
    ONLY_ME,
    /** Channel story — the "author" partition is the CHANNEL's conversation id;
     *  visible to the channel's audience (everyone for public channels, active
     *  subscribers for private ones). Posted/managed by channel admins. */
    CHANNEL
}

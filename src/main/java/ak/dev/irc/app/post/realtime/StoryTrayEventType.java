package ak.dev.irc.app.post.realtime;

public enum StoryTrayEventType {
    /** A followed user or close friend just posted a new story — show the ring. */
    NEW_STORY,

    /** The author deleted their story / all stories expired — remove/grey the ring. */
    STORY_REMOVED
}

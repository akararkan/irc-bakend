package ak.dev.irc.app.post.realtime;

public enum StoryTrayEventType {
    /** A followed user or close friend just posted a new story — show the ring. */
    NEW_STORY,

    /** The author deleted their story / all stories expired — remove/grey the ring. */
    STORY_REMOVED,

    /**
     * Someone voted on a poll attached to one of the recipient's stories.
     * Pushed to the <strong>story author only</strong> so the StoryEditor
     * shows live tallies without polling. Event carries {@code pollId},
     * {@code voteA}, {@code voteB}, {@code voteTotal}; other tray fields
     * are null for this kind.
     */
    POLL_VOTE_CAST
}

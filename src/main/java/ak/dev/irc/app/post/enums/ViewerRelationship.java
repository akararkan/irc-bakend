package ak.dev.irc.app.post.enums;

public enum ViewerRelationship {
    AUTHOR,        // the story author themselves
    CLOSE_FRIEND,  // in the author's close-friends list
    FOLLOWER,      // follows the author (not in close friends)
    PUBLIC         // no relationship with the author
}

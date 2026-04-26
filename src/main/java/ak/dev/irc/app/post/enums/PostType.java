package ak.dev.irc.app.post.enums;


public enum PostType {
    TEXT,
    EMBEDDED,       // text + media
    VOICE_POST,     // primary content is voice/audio
    REEL,           // short-form video
    REPOST          // shared/reposted content from another user
}
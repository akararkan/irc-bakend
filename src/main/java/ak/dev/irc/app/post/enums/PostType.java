package ak.dev.irc.app.post.enums;


public enum PostType {
    TEXT,
    EMBEDDED,       // text + media
    VOICE_POST,     // primary content is voice/audio
    STORY,          // 24-hour ephemeral
    REEL            // short-form video
}
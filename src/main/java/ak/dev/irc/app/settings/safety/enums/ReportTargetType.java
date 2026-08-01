package ak.dev.irc.app.settings.safety.enums;

/**
 * The kind of entity a report targets (spec §18). Kept independent of the other
 * subsystems' type enums so the safety module owns its own contract.
 */
public enum ReportTargetType {
    USER,
    POST,
    COMMENT,
    RESEARCH,
    QUESTION,
    ANSWER,
    MESSAGE,
    CHANNEL,
    STORY
}

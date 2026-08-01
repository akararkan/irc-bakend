package ak.dev.irc.app.settings.privacy.enums;

/**
 * Result of {@link ak.dev.irc.app.settings.privacy.service.VisibilityResolver}.
 * Binary and fail-closed: anything the resolver cannot positively allow is
 * {@link #DENY}.
 */
public enum VisibilityDecision {
    ALLOW,
    DENY;

    public boolean allowed() { return this == ALLOW; }
}

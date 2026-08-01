package ak.dev.irc.app.settings.privacy.enums;

/**
 * Kind of privacy list (spec §5). Close Friends is a system-created list that
 * already has a dedicated subsystem on this platform
 * ({@code CloseFriendsList}); custom lists are user-created named audiences the
 * {@link VisibilityLevel#CUSTOM} policy resolves against.
 */
public enum PrivacyListType {
    CLOSE_FRIENDS,
    CUSTOM
}

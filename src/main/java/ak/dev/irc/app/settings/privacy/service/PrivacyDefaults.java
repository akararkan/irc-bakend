package ak.dev.irc.app.settings.privacy.service;

import ak.dev.irc.app.settings.privacy.enums.FieldKey;
import ak.dev.irc.app.settings.privacy.enums.VisibilityLevel;

import java.util.EnumMap;
import java.util.Map;

/**
 * Platform default visibility per {@link FieldKey} (spec §5). Defaults live in
 * code so a missing key in a user's stored policy is never {@code null} — it
 * resolves to the platform default. This is an academic-community platform, so
 * profile basics are open while contact details and sensitive fields default to
 * a narrower audience.
 */
public final class PrivacyDefaults {

    private PrivacyDefaults() {}

    private static final Map<FieldKey, VisibilityLevel> DEFAULTS = new EnumMap<>(FieldKey.class);

    static {
        // Open by default — a research/knowledge platform is discovery-oriented.
        DEFAULTS.put(FieldKey.BIO,             VisibilityLevel.EVERYONE);
        DEFAULTS.put(FieldKey.PROFILE_PICTURE, VisibilityLevel.EVERYONE);
        DEFAULTS.put(FieldKey.COVER_IMAGE,     VisibilityLevel.EVERYONE);
        DEFAULTS.put(FieldKey.LOCATION,        VisibilityLevel.EVERYONE);
        DEFAULTS.put(FieldKey.WEBSITE,         VisibilityLevel.EVERYONE);
        DEFAULTS.put(FieldKey.OCCUPATION,      VisibilityLevel.EVERYONE);
        DEFAULTS.put(FieldKey.EDUCATION,       VisibilityLevel.EVERYONE);
        DEFAULTS.put(FieldKey.POSTS,           VisibilityLevel.EVERYONE);
        DEFAULTS.put(FieldKey.RESEARCH,        VisibilityLevel.EVERYONE);
        DEFAULTS.put(FieldKey.PHOTOS,          VisibilityLevel.EVERYONE);
        DEFAULTS.put(FieldKey.VIDEOS,          VisibilityLevel.EVERYONE);
        DEFAULTS.put(FieldKey.FOLLOWERS,       VisibilityLevel.EVERYONE);
        DEFAULTS.put(FieldKey.FOLLOWING,       VisibilityLevel.EVERYONE);

        // Narrower — social graph detail and ephemeral content.
        DEFAULTS.put(FieldKey.FRIENDS_LIST,    VisibilityLevel.FRIENDS);
        DEFAULTS.put(FieldKey.STORIES,         VisibilityLevel.FOLLOWERS);

        // Sensitive — never public by default.
        DEFAULTS.put(FieldKey.BIRTHDAY,        VisibilityLevel.FRIENDS);
        DEFAULTS.put(FieldKey.EMAIL_ADDRESS,   VisibilityLevel.ONLY_ME);
        DEFAULTS.put(FieldKey.PHONE_NUMBER,    VisibilityLevel.ONLY_ME);

        // Interaction permissions — who may reach the user.
        DEFAULTS.put(FieldKey.WHO_CAN_FOLLOW,          VisibilityLevel.EVERYONE);
        DEFAULTS.put(FieldKey.WHO_CAN_MESSAGE,         VisibilityLevel.EVERYONE);
        DEFAULTS.put(FieldKey.WHO_CAN_TAG,             VisibilityLevel.FOLLOWERS);
        DEFAULTS.put(FieldKey.WHO_CAN_MENTION,         VisibilityLevel.EVERYONE);
        DEFAULTS.put(FieldKey.WHO_CAN_FRIEND_REQUEST,  VisibilityLevel.EVERYONE);
    }

    /** Never returns null — unknown keys fall back to FRIENDS (conservative). */
    public static VisibilityLevel forField(FieldKey field) {
        return DEFAULTS.getOrDefault(field, VisibilityLevel.FRIENDS);
    }

    public static Map<FieldKey, VisibilityLevel> all() {
        return new EnumMap<>(DEFAULTS);
    }
}

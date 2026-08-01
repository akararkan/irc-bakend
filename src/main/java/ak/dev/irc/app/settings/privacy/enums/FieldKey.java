package ak.dev.irc.app.settings.privacy.enums;

import ak.dev.irc.app.settings.privacy.service.PrivacyDefaults;

/**
 * The set of profile fields and content classes whose visibility a user can
 * control (spec §5). New keys are added here and given a default in
 * {@link PrivacyDefaults}; because policies are stored as a JSONB map keyed by
 * this enum's {@code name()}, adding a key needs no schema migration.
 */
public enum FieldKey {

    // ── Profile fields ──
    BIO,
    BIRTHDAY,
    PROFILE_PICTURE,
    COVER_IMAGE,
    LOCATION,
    WEBSITE,
    OCCUPATION,
    EDUCATION,
    EMAIL_ADDRESS,
    PHONE_NUMBER,

    // ── Social graph ──
    FRIENDS_LIST,
    FOLLOWERS,
    FOLLOWING,

    // ── Content classes ──
    POSTS,
    STORIES,
    PHOTOS,
    VIDEOS,
    RESEARCH,

    // ── Interaction permissions ──
    WHO_CAN_FOLLOW,
    WHO_CAN_MESSAGE,
    WHO_CAN_TAG,
    WHO_CAN_MENTION,
    WHO_CAN_FRIEND_REQUEST;

    public static FieldKey parse(String raw) {
        if (raw == null) return null;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}

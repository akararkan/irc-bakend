package ak.dev.irc.app.user.enums;

public enum AccountType {

    REGULAR,

    VERIFIED_SCHOLAR,

    VERIFIED_RESEARCHER,

    /** The IRC platform account itself — protected by isSystemAccount = true. */
    PLATFORM_OFFICIAL,

    INSTITUTION,

    MEDIA
}

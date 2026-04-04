package ak.dev.irc.app.user.enums;

import lombok.Getter;

@Getter
public enum LinkPlatform {
    FACEBOOK("Facebook"),
    TWITTER("Twitter / X"),
    INSTAGRAM("Instagram"),
    LINKEDIN("LinkedIn"),
    YOUTUBE("YouTube"),
    GITHUB("GitHub"),
    ORCID("ORCID"),
    RESEARCHGATE("ResearchGate"),
    GOOGLE_SCHOLAR("Google Scholar"),
    TELEGRAM("Telegram"),
    PERSONAL_WEBSITE("Personal Website"),
    OTHER("Other");

    private final String displayName;

    LinkPlatform(String displayName) {
        this.displayName = displayName;
    }
}

package ak.dev.irc.app.user.enums;

import lombok.Getter;

@Getter
public enum ContactPlatform {
    TELEGRAM("Telegram"),
    WHATSAPP("WhatsApp"),
    EMAIL("Email"),
    PHONE("Phone Number"),
    VIBER("Viber"),
    SIGNAL("Signal"),
    SKYPE("Skype"),
    OTHER("Other");

    private final String displayName;

    ContactPlatform(String displayName) {
        this.displayName = displayName;
    }
}

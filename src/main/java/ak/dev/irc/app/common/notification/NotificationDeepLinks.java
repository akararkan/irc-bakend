package ak.dev.irc.app.common.notification;

import java.util.UUID;

/**
 * The ONE (resourceType, resourceId) → href mapping for notifications. Inbox
 * rows ({@code NotificationServiceImpl}) and push payloads
 * ({@code PushNotifier}) both resolve through here, so a tap on a push lands
 * exactly where a tap on the matching inbox row does — the client routes both
 * off the same grammar.
 */
public final class NotificationDeepLinks {

    private NotificationDeepLinks() {}

    /** In-app path for a notification's resource, or null when un-navigable. */
    public static String deepLinkOf(String resourceType, UUID resourceId) {
        if (resourceId == null || resourceType == null) return null;
        return switch (resourceType) {
            case "Post"     -> "/posts/"     + resourceId;
            case "Comment"  -> "/comments/"  + resourceId;
            case "Question" -> "/questions/" + resourceId;
            case "Answer"   -> "/answers/"   + resourceId;
            case "Research" -> "/researches/"+ resourceId;
            case "User"     -> "/users/"     + resourceId;
            // Chat: DMs/groups open the conversation, channels their own page,
            // live streams the watch page (routes match the ika frontend).
            case "Conversation" -> "/chat/"     + resourceId;
            case "Channel"      -> "/channels/" + resourceId;
            case "LiveStream"   -> "/live/"     + resourceId;
            default         -> null;
        };
    }
}

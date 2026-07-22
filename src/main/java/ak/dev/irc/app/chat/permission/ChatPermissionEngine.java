package ak.dev.irc.app.chat.permission;

import ak.dev.irc.app.chat.enums.SendDecision;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The send-permission engine for DIRECT messages — evaluated at send time,
 * <b>before</b> persistence. Pure decision logic over the four relationship
 * states; the outcome tells the caller whether to write normally, quarantine as
 * a request, deliver quietly to a restricted tray, or refuse.
 *
 * <p>Order matters and mirrors the design's truth table exactly:</p>
 * <ol>
 *   <li>Block wins over everything → {@link SendDecision#DENY} (no existence leak).</li>
 *   <li>Recipient restricted sender → {@link SendDecision#DELIVER_RESTRICTED} (quiet).</li>
 *   <li>Already an accepted thread → {@link SendDecision#ALLOW}.</li>
 *   <li>Connected (mutual follow) → {@link SendDecision#ALLOW}.</li>
 *   <li>Otherwise a stranger's first contact → {@link SendDecision#ROUTE_TO_REQUEST}.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class ChatPermissionEngine {

    private final ChatRelationshipService relationships;

    public SendDecision authorizeDirectSend(UUID senderId, UUID recipientId) {
        // 1. Hard block wins over everything.
        if (relationships.isBlockedEitherWay(senderId, recipientId)) {
            return SendDecision.DENY;
        }
        // 2. Restrict: accepted but quarantined into the recipient's muted tray.
        if (relationships.isRestrictedBy(recipientId, senderId)) {
            return SendDecision.DELIVER_RESTRICTED;
        }
        // 3. Already talking? Straight through.
        if (relationships.hasAcceptedThread(senderId, recipientId)) {
            return SendDecision.ALLOW;
        }
        // 4. Connected (mutual follow)? Straight through.
        if (relationships.isConnected(senderId, recipientId)) {
            return SendDecision.ALLOW;
        }
        // 5. Stranger's first contact → Message Request.
        return SendDecision.ROUTE_TO_REQUEST;
    }
}

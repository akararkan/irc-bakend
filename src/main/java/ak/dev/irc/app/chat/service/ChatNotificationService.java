package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.common.notification.NotificationKind;
import ak.dev.irc.app.post.cassandra.service.CassandraNotificationService;
import ak.dev.irc.app.post.cassandra.service.CassandraNotificationService.DeliverRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Bridges chat events into the platform's existing notification pipeline
 * ({@link CassandraNotificationService}), which already handles self-suppression,
 * block filtering, per-group aggregation, the unread bell counter, and the
 * offline/email path. Chat kinds are in-app only ({@code emailEligible=false}) so
 * an active conversation never floods a mailbox.
 *
 * <p>{@code NEW_MESSAGE} coalesces per conversation via the {@code groupKey}, so a
 * burst of messages surfaces as a single "@alice and 3 others messaged you" row.</p>
 */
@Service
@RequiredArgsConstructor
public class ChatNotificationService {

    private final CassandraNotificationService notifications;

    /** New message → the recipient's bell (used for offline / backgrounded recipients). */
    public void notifyNewMessage(UUID recipientId, UUID senderId, UUID conversationId,
                                 String senderLabel, String preview) {
        notifications.deliverAsync(new DeliverRequest(
                recipientId,
                NotificationKind.NEW_MESSAGE,
                "New message",
                truncate(senderLabel + ": " + (preview == null ? "" : preview), 160),
                senderId,
                "Conversation", conversationId,
                "NEW_MESSAGE:" + conversationId));
    }

    /** A stranger's first message landed as a request. */
    public void notifyMessageRequest(UUID recipientId, UUID requesterId, UUID conversationId,
                                     String requesterLabel) {
        notifications.deliverAsync(new DeliverRequest(
                recipientId,
                NotificationKind.MESSAGE_REQUEST,
                "Message request",
                requesterLabel + " wants to send you a message",
                requesterId,
                "Conversation", conversationId,
                "MESSAGE_REQUEST:" + conversationId));
    }

    /** Someone added you to a group. */
    public void notifyAddedToGroup(UUID recipientId, UUID actorId, UUID conversationId,
                                   String groupTitle, String actorLabel) {
        notifications.deliverAsync(new DeliverRequest(
                recipientId,
                NotificationKind.ADDED_TO_GROUP,
                "Added to a group",
                actorLabel + " added you to " + (groupTitle == null ? "a group" : "\"" + groupTitle + "\""),
                actorId,
                "Conversation", conversationId,
                "ADDED_TO_GROUP:" + conversationId + ":" + recipientId));
    }

    /** Someone asked to join a channel this admin can approve requests for. */
    public void notifyJoinRequest(UUID adminId, UUID requesterId, UUID channelId,
                                  String requesterLabel, String channelTitle) {
        notifications.deliverAsync(new DeliverRequest(
                adminId,
                NotificationKind.CHANNEL_JOIN_REQUEST,
                "Join request",
                requesterLabel + " requested to join "
                        + (channelTitle == null ? "your channel" : "\"" + channelTitle + "\""),
                requesterId,
                "Conversation", channelId,
                "CHANNEL_JOIN_REQUEST:" + channelId));
    }

    /** The user's join request was approved. */
    public void notifyJoinApproved(UUID userId, UUID channelId, String channelTitle) {
        notifications.deliverAsync(new DeliverRequest(
                userId,
                NotificationKind.CHANNEL_JOIN_APPROVED,
                "Request approved",
                "You joined " + (channelTitle == null ? "the channel" : "\"" + channelTitle + "\""),
                null,
                "Conversation", channelId,
                "CHANNEL_JOIN_APPROVED:" + channelId + ":" + userId));
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}

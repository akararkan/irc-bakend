package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.common.messages.ChatMessages;
import ak.dev.irc.app.common.notification.NotificationKind;
import ak.dev.irc.app.post.cassandra.service.CassandraNotificationService;
import ak.dev.irc.app.post.cassandra.service.CassandraNotificationService.DeliverRequest;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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
 *
 * <p><b>Resource types / deep links:</b> DM and group kinds carry
 * {@code resourceType="Conversation"} (client opens {@code /chat/{id}}); channel
 * kinds carry {@code "Channel"} ({@code /channels/{id}}). Live streams are handled
 * by {@code LiveStreamFanoutService} with {@code "LiveStream"} ({@code /live/{id}}).</p>
 */
@Service
@RequiredArgsConstructor
public class ChatNotificationService {

    private final CassandraNotificationService notifications;
    private final UserRepository               userRepository;

    /** New message → the recipient's bell (used for offline / backgrounded recipients). */
    public void notifyNewMessage(UUID recipientId, UUID senderId, UUID conversationId,
                                 String senderLabel, String preview) {
        notifications.deliverAsync(new DeliverRequest(
                recipientId,
                NotificationKind.NEW_MESSAGE,
                ChatMessages.NOTIF_NEW_MESSAGE_TITLE,
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
                ChatMessages.NOTIF_MESSAGE_REQUEST_TITLE,
                ChatMessages.NOTIF_MESSAGE_REQUEST_BODY.formatted(requesterLabel),
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
                ChatMessages.NOTIF_ADDED_TO_GROUP_TITLE,
                actorLabel + " added you to " + (groupTitle == null ? "a group" : "\"" + groupTitle + "\""),
                actorId,
                "Conversation", conversationId,
                "ADDED_TO_GROUP:" + conversationId + ":" + recipientId));
    }

    /**
     * You missed a call — it rang out, or the caller hung up while it was still
     * ringing. Aggregates per conversation ("3 missed calls"); the caller label
     * is resolved lazily on the executor thread (deferred enrichment), keeping
     * the ring-timeout sweep free of extra reads.
     */
    public void notifyMissedCall(UUID recipientId, UUID callerId, UUID conversationId, boolean video) {
        notifications.deliverAsync(() -> {
            String caller = userRepository.findById(callerId)
                    .map(User::getUsername)
                    .map(u -> "@" + u)
                    .orElse("Someone");
            return new DeliverRequest(
                    recipientId,
                    NotificationKind.CALL_MISSED,
                    ChatMessages.NOTIF_MISSED_CALL_TITLE,
                    "You missed a " + (video ? "video" : "voice") + " call from " + caller,
                    callerId,
                    "Conversation", conversationId,
                    "CALL_MISSED:" + conversationId);
        });
    }

    /**
     * You were {@code @}-mentioned in a chat message or channel post. Fired by
     * {@code MessageService.dispatch} for mentioned members only — it cuts
     * through mute, presence and group-size gates (a ping's whole purpose),
     * and the mentioned member is excluded from the generic
     * {@code NEW_MESSAGE}/{@code CHANNEL_NEW_POST} bell so one message never
     * yields two rows. Non-aggregated: every mention is its own row.
     */
    public void notifyMessageMention(UUID recipientId, UUID senderId, UUID conversationId,
                                     String senderLabel, String preview,
                                     boolean channel, String channelTitle) {
        String where = channel
                ? (StringUtils.hasText(channelTitle) ? "in \"" + channelTitle + "\"" : "in a channel")
                : "in a chat";
        String body = senderLabel + " mentioned you " + where
                + (StringUtils.hasText(preview) ? ": " + preview : "");
        notifications.deliverAsync(new DeliverRequest(
                recipientId,
                NotificationKind.MESSAGE_MENTION,
                ChatMessages.NOTIF_MENTIONED_TITLE,
                truncate(body, 160),
                senderId,
                channel ? "Channel" : "Conversation", conversationId,
                "MESSAGE_MENTION:" + conversationId + ":" + recipientId));
    }

    /**
     * Builds one subscriber's {@code CHANNEL_NEW_POST} row — called per
     * subscriber by {@code ChannelPostFanoutService}, which owns the paged
     * scan. Aggregates per channel so a posting burst is one "N new posts"
     * row, not N rows.
     */
    public void notifyChannelPost(UUID subscriberId, UUID posterId, UUID channelId,
                                  String channelTitle, String preview) {
        String label = StringUtils.hasText(channelTitle) ? channelTitle : "A channel you follow";
        String body  = StringUtils.hasText(preview)
                ? truncate(label + ": " + preview, 160)
                : label + " published a new post";
        notifications.deliverAsync(new DeliverRequest(
                subscriberId,
                NotificationKind.CHANNEL_NEW_POST,
                ChatMessages.NOTIF_CHANNEL_POST_TITLE,
                body,
                posterId,
                "Channel", channelId,
                "CHANNEL_NEW_POST:" + channelId));
    }

    /** Someone asked to join a channel this admin can approve requests for. */
    public void notifyJoinRequest(UUID adminId, UUID requesterId, UUID channelId,
                                  String requesterLabel, String channelTitle) {
        notifications.deliverAsync(new DeliverRequest(
                adminId,
                NotificationKind.CHANNEL_JOIN_REQUEST,
                ChatMessages.NOTIF_JOIN_REQUEST_TITLE,
                requesterLabel + " requested to join "
                        + (channelTitle == null ? "your channel" : "\"" + channelTitle + "\""),
                requesterId,
                "Channel", channelId,
                "CHANNEL_JOIN_REQUEST:" + channelId));
    }

    /** The user's join request was approved. */
    public void notifyJoinApproved(UUID userId, UUID channelId, String channelTitle) {
        notifications.deliverAsync(new DeliverRequest(
                userId,
                NotificationKind.CHANNEL_JOIN_APPROVED,
                ChatMessages.NOTIF_JOIN_APPROVED_TITLE,
                "You joined " + (channelTitle == null ? "the channel" : "\"" + channelTitle + "\""),
                null,
                "Channel", channelId,
                "CHANNEL_JOIN_APPROVED:" + channelId + ":" + userId));
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}

package ak.dev.irc.app.chat.service;

import ak.dev.irc.app.chat.entity.Conversation;
import ak.dev.irc.app.chat.repository.ConversationMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Telegram-style "new post in your channel" fan-out. When a post is published
 * in a CHANNEL conversation, every <b>active, non-muted</b> subscriber gets a
 * persisted {@code CHANNEL_NEW_POST} inbox row (the realtime {@code message.new}
 * SSE event is already broadcast by {@code MessageService.dispatch} — this
 * service only owns the bell).
 *
 * <p>Design notes, mirroring {@link LiveStreamFanoutService} (the
 * {@code STREAM_STARTED} fan-out):</p>
 * <ul>
 *   <li><b>Keyset scan</b> over subscriber ids in {@value #FANOUT_BATCH}-row
 *       pages ({@code findNotifiableMemberIdsAfter}), capped at
 *       {@value #MAX_FANOUT_SUBSCRIBERS} — constant-time per page however deep,
 *       so a huge channel can't quadratically slow the fan-out.</li>
 *   <li><b>Mute is honored at the query level</b>: members with
 *       {@code mutedUntil > now} are excluded server-side, not filtered in
 *       memory. Muting a channel silences its bell without touching unread.</li>
 *   <li><b>Aggregation</b>: the group key is {@code CHANNEL_NEW_POST:{channelId}},
 *       so a burst of posts within the 60-minute window coalesces into one
 *       "Channel X: latest preview" row with a bumped count — a busy channel
 *       occupies one inbox row, not one per post.</li>
 *   <li><b>Silent posts</b> ({@code silent=true} on send) never reach here —
 *       {@code MessageService.dispatch} skips the call, exactly like Telegram's
 *       "post without notification".</li>
 *   <li>It is a SEPARATE bean from {@code MessageService} so the {@code @Async}
 *       proxy applies and the sender's HTTP request returns immediately;
 *       self-suppression and block filtering happen downstream in the shared
 *       delivery engine.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelPostFanoutService {

    private static final int MAX_FANOUT_SUBSCRIBERS = 50_000;
    private static final int FANOUT_BATCH           = 500;

    private final ConversationMemberRepository memberRepo;
    private final ChatNotificationService      chatNotifications;

    /**
     * @param channel  the CHANNEL conversation the post landed in.
     * @param posterId the admin who posted (suppressed as a recipient downstream).
     * @param preview  redacted body preview from the send path — already safe
     *                 for surfaces outside the conversation.
     * @param exclude  subscribers already belled by a higher-signal row for
     *                 this same post (the {@code MESSAGE_MENTION} recipients) —
     *                 never null-checked by callers, may be empty.
     */
    @Async
    public void fanoutChannelPost(Conversation channel, UUID posterId, String preview,
                                  java.util.Set<UUID> exclude) {
        if (channel == null || posterId == null) return;
        final UUID channelId = channel.getId();
        final java.util.Set<UUID> skip = exclude == null ? java.util.Set.of() : exclude;

        UUID cursor = null;
        int  total  = 0;
        while (total < MAX_FANOUT_SUBSCRIBERS) {
            List<UUID> batch;
            try {
                batch = memberRepo.findNotifiableMemberIdsAfter(
                        channelId, cursor, PageRequest.of(0, FANOUT_BATCH));
            } catch (Exception e) {
                log.warn("[CHANNEL] subscriber lookup failed for {}: {}", channelId, e.getMessage());
                return;
            }
            if (batch.isEmpty()) break;

            for (UUID subscriberId : batch) {
                if (skip.contains(subscriberId)) continue;   // got a MESSAGE_MENTION instead
                try {
                    chatNotifications.notifyChannelPost(
                            subscriberId, posterId, channelId, channel.getTitle(), preview);
                } catch (Exception e) {
                    log.debug("[CHANNEL] notify subscriber {} skipped: {}", subscriberId, e.getMessage());
                }
            }

            total += batch.size();
            cursor = batch.get(batch.size() - 1);      // keyset advance
            if (batch.size() < FANOUT_BATCH) break;
        }
        log.info("[CHANNEL] post fan-out complete for {}: {} subscribers notified", channelId, total);
    }
}

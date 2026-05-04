package ak.dev.irc.app.common.service;

import ak.dev.irc.app.common.util.MentionExtractor;
import ak.dev.irc.app.rabbitmq.event.user.MentionSource;
import ak.dev.irc.app.rabbitmq.event.user.UserMentionedEvent;
import ak.dev.irc.app.rabbitmq.publisher.UserMentionEventPublisher;
import ak.dev.irc.app.user.entity.User;
import ak.dev.irc.app.user.repository.UserBlockRepository;
import ak.dev.irc.app.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Single entry point for "scan this text and notify anyone mentioned".
 *
 * <p>Call sites pass in:
 * <ul>
 *   <li>The free text just written ({@code post.textContent}, {@code answer.body}, …)</li>
 *   <li>What kind of source it was ({@link MentionSource})</li>
 *   <li>The id of the source row + an optional parent id (e.g. comment → its post)</li>
 *   <li>Whether the {@code @followers} token should be honoured (true on top-level
 *       creates only — disallowed on edits and on nested resources to avoid
 *       spam loops).</li>
 * </ul>
 *
 * <p>Resolution / filtering happens here so the consumer side stays a thin
 * fan-out worker:
 * <ol>
 *   <li>Extract usernames + followers token via {@link MentionExtractor}.</li>
 *   <li>Batch-resolve usernames → User rows in one DB call.</li>
 *   <li>Drop self-mentions and any user in a block-relationship with the mentioner.</li>
 *   <li>If anything is left to notify, publish one {@link UserMentionedEvent}
 *       (after-commit) — the consumer expands and writes notifications.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MentionService {

    private static final int SNIPPET_MAX_CHARS = 140;

    private final UserRepository userRepo;
    private final UserBlockRepository blockRepo;
    private final UserMentionEventPublisher publisher;

    /**
     * Scan the given text and, if anything is mentioned, publish a single
     * {@link UserMentionedEvent}. Safe to call from inside a {@code @Transactional}
     * service method — the actual broker publish is deferred to {@code afterCommit}.
     *
     * @param allowFollowersToken pass {@code true} only when the source is a
     *                            top-level user-authored creation (post,
     *                            research, question). Comments and edits should
     *                            pass {@code false}.
     */
    @Transactional(readOnly = true)
    public void scanAndPublish(String text,
                               MentionSource sourceType,
                               UUID sourceId,
                               UUID sourceParentId,
                               UUID mentionerId,
                               String mentionerUsername,
                               boolean allowFollowersToken) {

        if (text == null || text.isEmpty() || mentionerId == null || sourceType == null || sourceId == null) {
            return;
        }

        MentionExtractor.ParsedMentions parsed = MentionExtractor.extract(text);
        boolean wantsFollowersFanOut = allowFollowersToken && parsed.isHasFollowersToken();

        if (parsed.getUsernames().isEmpty() && !wantsFollowersFanOut) {
            return;
        }

        Set<UUID> directRecipients = resolveDirectRecipients(parsed.getUsernames(), mentionerId);

        if (directRecipients.isEmpty() && !wantsFollowersFanOut) {
            return;
        }

        UserMentionedEvent event = UserMentionedEvent.builder()
                .mentionerId(mentionerId)
                .mentionerUsername(mentionerUsername)
                .sourceType(sourceType)
                .sourceId(sourceId)
                .sourceParentId(sourceParentId)
                .mentionedUserIds(directRecipients)
                .notifyFollowers(wantsFollowersFanOut)
                .snippet(buildSnippet(text))
                .build();

        publisher.publish(event);
    }

    /**
     * Edit-time variant — only notifies users that were <em>newly</em> @-mentioned
     * by this edit. Mentions that were already in {@code oldText} are skipped so
     * editing a post does not re-notify everyone you tagged the first time.
     *
     * <p>{@code @followers} is never honoured here — edits do not fan out to
     * followers (would otherwise turn into a spam vector).</p>
     *
     * <p>Pass the saved-on-disk text as {@code oldText} (may be null for newly
     * created edits where the previous body is unknown — falls back to a full
     * scan in that case).</p>
     */
    @Transactional(readOnly = true)
    public void scanAndPublishDelta(String oldText, String newText,
                                    MentionSource sourceType,
                                    UUID sourceId,
                                    UUID sourceParentId,
                                    UUID mentionerId,
                                    String mentionerUsername) {

        if (newText == null || newText.isEmpty() || mentionerId == null
                || sourceType == null || sourceId == null) {
            return;
        }

        Set<String> oldNames = oldText == null
                ? Set.of()
                : MentionExtractor.extract(oldText).getUsernames();
        Set<String> newNames = MentionExtractor.extract(newText).getUsernames();
        if (newNames.isEmpty()) return;

        // Only ping handles that did not appear in the previous body.
        Set<String> delta = new LinkedHashSet<>(newNames);
        delta.removeAll(oldNames);
        if (delta.isEmpty()) return;

        Set<UUID> directRecipients = resolveDirectRecipients(delta, mentionerId);
        if (directRecipients.isEmpty()) return;

        UserMentionedEvent event = UserMentionedEvent.builder()
                .mentionerId(mentionerId)
                .mentionerUsername(mentionerUsername)
                .sourceType(sourceType)
                .sourceId(sourceId)
                .sourceParentId(sourceParentId)
                .mentionedUserIds(directRecipients)
                .notifyFollowers(false)
                .snippet(buildSnippet(newText))
                .build();

        publisher.publish(event);
    }

    private Set<UUID> resolveDirectRecipients(Set<String> usernames, UUID mentionerId) {
        if (usernames.isEmpty()) return new HashSet<>();

        List<User> resolved = userRepo.findActiveByUsernameIn(usernames);
        if (resolved.isEmpty()) return new HashSet<>();

        // Skip self-mentions.
        Set<UUID> ids = new LinkedHashSet<>();
        for (User u : resolved) {
            if (!u.getId().equals(mentionerId)) ids.add(u.getId());
        }
        if (ids.isEmpty()) return ids;

        // Drop anyone in any block-relationship with the mentioner — single DB call.
        Set<UUID> blocked = new HashSet<>(blockRepo.findBlockedAmong(mentionerId, ids));
        if (!blocked.isEmpty()) ids.removeAll(blocked);

        return ids;
    }

    private static String buildSnippet(String text) {
        String trimmed = text.strip();
        if (trimmed.length() <= SNIPPET_MAX_CHARS) return trimmed;
        return trimmed.substring(0, SNIPPET_MAX_CHARS - 1) + "…";
    }
}

package ak.dev.irc.app.post.cassandra.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.cassandra.core.cql.CqlOperations;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Cassandra counter columns can only be modified by
 * {@code UPDATE … SET col = col + N WHERE pk = ?} — Spring Data save() does
 * not work on counter tables, so this service issues raw CQL.
 *
 * All increment / decrement methods are idempotent at the API layer
 * (e.g. PostService verifies "did user already like?" before incrementing).
 * Cassandra counters themselves are not idempotent — never retry blindly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CounterService {

    private final CqlOperations cqlOperations;

    // ── post counters ────────────────────────────────────────────────────────

    public void incrementPostReactions(UUID postId)  { addPost(postId, "reaction_count",  1); }
    public void decrementPostReactions(UUID postId)  { addPost(postId, "reaction_count", -1); }
    public void incrementPostComments(UUID postId)   { addPost(postId, "comment_count",   1); }
    public void decrementPostComments(UUID postId)   { addPost(postId, "comment_count",  -1); }
    public void incrementPostShares(UUID postId)     { addPost(postId, "share_count",     1); }
    public void incrementPostViews(UUID postId)      { addPost(postId, "view_count",      1); }
    public void incrementPostSaves(UUID postId)      { addPost(postId, "save_count",      1); }
    public void decrementPostSaves(UUID postId)      { addPost(postId, "save_count",     -1); }

    // ── comment counters ─────────────────────────────────────────────────────

    public void incrementCommentReactions(UUID commentId) { addComment(commentId, "reaction_count",  1); }
    public void decrementCommentReactions(UUID commentId) { addComment(commentId, "reaction_count", -1); }
    public void incrementCommentReplies(UUID commentId)   { addComment(commentId, "reply_count",     1); }
    public void decrementCommentReplies(UUID commentId)   { addComment(commentId, "reply_count",    -1); }

    // ── sound counters ───────────────────────────────────────────────────────

    public void incrementSoundUse(UUID soundId) { addSound(soundId, "use_count",  1); }
    public void decrementSoundUse(UUID soundId) { addSound(soundId, "use_count", -1); }

    // ── hashtag counters ─────────────────────────────────────────────────────

    public void incrementHashtagPostCount(String hashtag) { addHashtag(hashtag,  1); }
    public void decrementHashtagPostCount(String hashtag) { addHashtag(hashtag, -1); }

    private void addHashtag(String hashtag, long delta) {
        cqlOperations.execute(
                "UPDATE hashtag_counters SET post_count = post_count + ? WHERE hashtag = ?",
                delta, hashtag);
    }

    // ── notification unread counter ─────────────────────────────────────────

    public void incrementUnread(UUID userId) { addUnread(userId,  1); }
    public void decrementUnread(UUID userId) { addUnread(userId, -1); }

    private void addUnread(UUID userId, long delta) {
        cqlOperations.execute(
                "UPDATE notification_unread_counter SET unread = unread + ? WHERE user_id = ?",
                delta, userId);
    }

    // ── poll counters ────────────────────────────────────────────────────────

    /**
     * Apply +/- 1 to the matching choice column. Choice is constrained to
     * 'A' or 'B' — anything else is a no-op rather than an exception, so a
     * bad client input can't corrupt the counter.
     */
    public void incrementPollVote(UUID pollId, String choice) { addPollVote(pollId, choice,  1); }
    public void decrementPollVote(UUID pollId, String choice) { addPollVote(pollId, choice, -1); }

    private void addPollVote(UUID pollId, String choice, long delta) {
        String column = switch (choice) {
            case "A" -> "vote_a";
            case "B" -> "vote_b";
            default  -> null;
        };
        if (column == null) return;
        cqlOperations.execute(
                "UPDATE poll_counters SET " + column + " = " + column + " + ? WHERE poll_id = ?",
                delta, pollId);
    }

    // ── primitives ───────────────────────────────────────────────────────────

    private void addPost(UUID postId, String column, long delta) {
        cqlOperations.execute(
                "UPDATE post_counters SET " + column + " = " + column + " + ? WHERE post_id = ?",
                delta, postId);
    }

    private void addComment(UUID commentId, String column, long delta) {
        cqlOperations.execute(
                "UPDATE comment_counters SET " + column + " = " + column + " + ? WHERE comment_id = ?",
                delta, commentId);
    }

    private void addSound(UUID soundId, String column, long delta) {
        cqlOperations.execute(
                "UPDATE sound_counters SET " + column + " = " + column + " + ? WHERE sound_id = ?",
                delta, soundId);
    }
}

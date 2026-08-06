package ak.dev.irc.app.post.cassandra.service;

import ak.dev.irc.app.common.messages.PostMessages;
import ak.dev.irc.app.post.cassandra.entity.PollVoteEntity;
import ak.dev.irc.app.post.cassandra.entity.PollVoterByChoiceEntity;
import ak.dev.irc.app.post.cassandra.entity.StoryPollEntity;
import ak.dev.irc.app.post.cassandra.repository.PollCounterRepository;
import ak.dev.irc.app.post.cassandra.repository.PollVoteRepository;
import ak.dev.irc.app.post.cassandra.repository.PollVoterByChoiceRepository;
import ak.dev.irc.app.post.cassandra.repository.StoryPollRepository;
import ak.dev.irc.app.post.cassandra.repository.StoryLookupRepository;
import ak.dev.irc.app.post.enums.StoryLifetime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Two-option polls attached to a story (Instagram-style).
 *
 * Behaviour:
 *   • One vote per user, per poll.
 *   • Changing your mind is allowed — moves the row, no double-counting.
 *   • Idempotent — re-submitting the same choice is a no-op.
 *
 * Schema fan-out per cast:
 *   • poll_votes_by_poll_user   ← "what did U vote?" / "has U voted?" lookup
 *   • poll_voters_by_choice     ← author-facing voter list per side
 *   • poll_counters             ← live A/B tally
 *
 * All four tables inherit 24h TTL from the parent story.
 *
 * Race notes:
 *   No LWT — at the volume real polls see, a stale double-vote is cheaper
 *   than a Paxos round-trip per write. The counter is the authoritative tally
 *   for the UI; the per-user vote row is what the user sees as "their pick".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CassandraStoryPollService {

    private final StoryPollRepository          pollRepo;
    private final PollVoteRepository           voteRepo;
    private final PollVoterByChoiceRepository  voterByChoiceRepo;
    private final PollCounterRepository        counterRepo;
    private final StoryLookupRepository        storyLookupRepo;
    private final CounterService               counterService;
    private final ak.dev.irc.app.post.cassandra.repository.PollByIdRepository pollByIdRepo;
    private final ak.dev.irc.app.post.realtime.StoryTrayRealtimePublisher trayPublisher;
    private final org.springframework.data.cassandra.core.cql.CqlOperations cqlOperations;
    /** Used for explicit per-row {@code USING TTL} on poll / vote writes so they
     *  die with the parent story (8h / 16h / 24h) instead of lingering for the
     *  table-default 24h. Counter tables (poll_counters) can't be TTL'd at write
     *  time — they keep the table default and are wiped by the explicit
     *  {@link #deletePollFor} when the story is hard-deleted. */
    private final CassandraOperations          cassandraOps;

    // ── Create ──────────────────────────────────────────────────────────────

    public StoryPollEntity createPoll(UUID storyId, UUID authorId,
                                      String question, String optionA, String optionB) {
        // Author check — only the story owner can attach a poll.
        var lookup = storyLookupRepo.findById(storyId).orElse(null);
        if (lookup == null || !authorId.equals(lookup.getAuthorId())) {
            throw new SecurityException(PostMessages.NOT_STORY_AUTHOR_MSG);
        }
        Instant storyExpiresAt = lookup.getExpiresAt();   // null on legacy rows

        UUID    pollId = UUID.randomUUID();
        Instant now    = Instant.now();
        int     ttl    = remainingTtlSeconds(storyExpiresAt, now);
        InsertOptions ttlOpts = InsertOptions.builder().ttl(ttl).build();

        StoryPollEntity poll = StoryPollEntity.builder()
                .storyId(storyId).pollId(pollId)
                .question(question).optionA(optionA).optionB(optionB)
                .authorId(authorId).createdAt(now)
                .build();
        cassandraOps.insert(poll, ttlOpts);

        // Reverse index so pollId → (storyId, authorId, expiresAt) is a single-row
        // lookup. {@code expiresAt} mirrors the parent story so castVote can TTL
        // vote rows without re-reading story_lookup.
        try {
            cassandraOps.insert(ak.dev.irc.app.post.cassandra.entity.PollByIdEntity.builder()
                    .pollId(pollId).storyId(storyId).authorId(authorId)
                    .expiresAt(storyExpiresAt)
                    .build(), ttlOpts);
        } catch (Exception e) {
            // Best-effort — the poll still works without the index; we just
            // lose the realtime push and fall back to authenticated-only on
            // the voter list. Logged at WARN so the gap is visible in ops.
            log.warn("[POLL] reverse-index write for pollId={} failed: {}",
                    pollId, e.getMessage());
        }
        return poll;
    }

    /** Look up the (storyId, authorId) tuple a poll belongs to. */
    public Optional<ak.dev.irc.app.post.cassandra.entity.PollByIdEntity> findOwnership(UUID pollId) {
        return pollByIdRepo.findById(pollId);
    }

    /**
     * Delete the poll attached to {@code storyId} (if any) and every
     * derived row across the five poll tables. Called from
     * {@code CassandraStoryService.deleteStory} so a story delete doesn't
     * leave orphan votes / counters / voter rows pointing at a story that
     * no longer exists.
     *
     * <p>No-op when the story has no poll. Each table delete is wrapped in
     * its own try/catch so a single failed wipe doesn't block the others —
     * the story-side delete has already happened and partial state (orphan
     * rows in 1 of 5 tables, eventually consistent) is preferable to a
     * fully-stuck delete.</p>
     */
    public void deletePollFor(UUID storyId) {
        Optional<StoryPollEntity> pollOpt = pollRepo.findById(storyId);
        if (pollOpt.isEmpty()) return;

        UUID pollId = pollOpt.get().getPollId();
        if (pollId == null) {
            // Malformed row (shouldn't happen — pollId is always set on
            // create). Drop the story-side row anyway so a future createPoll
            // for this story doesn't trip on a stale residue.
            try { pollRepo.deleteById(storyId); } catch (Exception ignored) {}
            return;
        }

        // Per-poll partition deletes — single tombstone per table since
        // every poll-derived table is keyed on poll_id at the partition level.
        tryRun(() -> cqlOperations.execute(
                "DELETE FROM poll_votes_by_poll_user WHERE poll_id = ?", pollId),
                "delete poll_votes_by_poll_user", pollId);
        tryRun(() -> cqlOperations.execute(
                "DELETE FROM poll_voters_by_choice WHERE poll_id = ?", pollId),
                "delete poll_voters_by_choice", pollId);
        tryRun(() -> counterRepo.deleteById(pollId),
                "delete poll_counters", pollId);
        tryRun(() -> pollByIdRepo.deleteById(pollId),
                "delete poll_by_id reverse index", pollId);
        tryRun(() -> pollRepo.deleteById(storyId),
                "delete story_polls", pollId);
    }

    private void tryRun(Runnable r, String what, UUID pollId) {
        try { r.run(); }
        catch (Exception e) {
            log.warn("[POLL] {} for pollId={} failed: {}", what, pollId, e.getMessage());
        }
    }

    public Optional<StoryPollEntity> getPollForStory(UUID storyId) {
        return pollRepo.findById(storyId);
    }

    // ── Vote ────────────────────────────────────────────────────────────────

    public record CastVoteResult(String choice, long voteA, long voteB) {}

    /**
     * Cast or change a user's vote. Choice must be exactly "A" or "B" — any
     * other value throws IllegalArgumentException.
     */
    public CastVoteResult castVote(UUID pollId, UUID voterId, String choice) {
        if (!"A".equals(choice) && !"B".equals(choice)) {
            throw new IllegalArgumentException(PostMessages.POLL_CHOICE_INVALID_MSG);
        }

        Optional<PollVoteEntity> existing = voteRepo.find(pollId, voterId);
        Instant now = Instant.now();

        if (existing.isPresent()) {
            PollVoteEntity prior = existing.get();
            if (choice.equals(prior.getChoice())) {
                // Same vote again → no-op, just return the latest counts.
                return readCounts(pollId, choice);
            }
            // Switching sides: remove the old voter row + decrement old counter,
            // then write the new ones.
            voterByChoiceRepo.delete(pollId, prior.getChoice(),
                                     prior.getVotedAt(), voterId);
            counterService.decrementPollVote(pollId, prior.getChoice());
        }

        // Single point-read serves two purposes: derives the per-row TTL for
        // the vote/voter writes so they die with the parent story, AND feeds
        // the post-write realtime push (no second lookup needed).
        Optional<ak.dev.irc.app.post.cassandra.entity.PollByIdEntity> owner =
                pollByIdRepo.findById(pollId);
        int ttl = remainingTtlSeconds(
                owner.map(o -> o.getExpiresAt()).orElse(null), now);
        InsertOptions ttlOpts = InsertOptions.builder().ttl(ttl).build();

        cassandraOps.insert(PollVoteEntity.builder()
                .pollId(pollId).voterId(voterId)
                .choice(choice).votedAt(now)
                .build(), ttlOpts);
        cassandraOps.insert(PollVoterByChoiceEntity.builder()
                .pollId(pollId).choice(choice)
                .votedAt(now).voterId(voterId)
                .build(), ttlOpts);
        // Counters are a Cassandra COUNTER table — TTL can't be set on
        // UPDATE statements, so the counter row keeps the table-default TTL.
        // A 16h-orphan counter row is unreachable (poll_by_id is gone) and
        // tombstones with the table TTL — acceptable cost vs. a Paxos round.
        counterService.incrementPollVote(pollId, choice);

        CastVoteResult result = readCounts(pollId, choice);
        if (owner.isPresent()) pushLiveTally(owner.get(), result);
        return result;
    }

    /**
     * Push a {@code POLL_VOTE_CAST} tray event to the poll's author so the
     * StoryEditor / story viewer pane updates without polling. Owner is
     * already resolved in {@link #castVote} (one lookup serves both the TTL
     * derivation and this push). Best-effort — the vote is already persisted.
     */
    private void pushLiveTally(ak.dev.irc.app.post.cassandra.entity.PollByIdEntity owner,
                               CastVoteResult result) {
        if (owner.getAuthorId() == null) return;
        try {
            ak.dev.irc.app.post.realtime.StoryTrayEvent event =
                    ak.dev.irc.app.post.realtime.StoryTrayEvent.builder()
                    .eventType(ak.dev.irc.app.post.realtime.StoryTrayEventType.POLL_VOTE_CAST)
                    .storyId(owner.getStoryId())
                    .pollId(owner.getPollId())
                    .voteA(result.voteA())
                    .voteB(result.voteB())
                    .voteTotal(result.voteA() + result.voteB())
                    .build();
            trayPublisher.publish(owner.getAuthorId(), event);
        } catch (Exception e) {
            log.debug("[POLL] live-tally push for pollId={} skipped: {}",
                    owner.getPollId(), e.getMessage());
        }
    }

    /**
     * Seconds left until {@code expiresAt}, clamped to ≥1 (Cassandra rejects
     * TTL=0). Returns {@link StoryLifetime#DEFAULT} when {@code expiresAt} is
     * null — old PollByIdEntity rows pre-date the {@code expires_at} column.
     */
    private static int remainingTtlSeconds(Instant expiresAt, Instant now) {
        if (expiresAt == null) return StoryLifetime.DEFAULT.ttlSeconds();
        long secs = expiresAt.getEpochSecond() - now.getEpochSecond();
        if (secs <= 0L) return 1;
        if (secs > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) secs;
    }

    public Optional<PollVoteEntity> userVote(UUID pollId, UUID voterId) {
        return voteRepo.find(pollId, voterId);
    }

    /** Author-only: list voters for a choice (caller is responsible for the auth check). */
    public List<PollVoterByChoiceEntity> votersFor(UUID pollId, String choice, int pageSize) {
        return voterByChoiceRepo.firstPage(pollId, choice, pageSize);
    }

    public CastVoteResult results(UUID pollId) {
        return readCounts(pollId, null);
    }

    private CastVoteResult readCounts(UUID pollId, String lastChoice) {
        return counterRepo.findByPollId(pollId)
                .map(c -> new CastVoteResult(lastChoice,
                        c.getVoteA() == null ? 0L : c.getVoteA(),
                        c.getVoteB() == null ? 0L : c.getVoteB()))
                .orElse(new CastVoteResult(lastChoice, 0L, 0L));
    }
}

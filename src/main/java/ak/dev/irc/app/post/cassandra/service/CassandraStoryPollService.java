package ak.dev.irc.app.post.cassandra.service;

import ak.dev.irc.app.post.cassandra.entity.PollVoteEntity;
import ak.dev.irc.app.post.cassandra.entity.PollVoterByChoiceEntity;
import ak.dev.irc.app.post.cassandra.entity.StoryPollEntity;
import ak.dev.irc.app.post.cassandra.repository.PollCounterRepository;
import ak.dev.irc.app.post.cassandra.repository.PollVoteRepository;
import ak.dev.irc.app.post.cassandra.repository.PollVoterByChoiceRepository;
import ak.dev.irc.app.post.cassandra.repository.StoryPollRepository;
import ak.dev.irc.app.post.cassandra.repository.StoryLookupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    // ── Create ──────────────────────────────────────────────────────────────

    public StoryPollEntity createPoll(UUID storyId, UUID authorId,
                                      String question, String optionA, String optionB) {
        // Author check — only the story owner can attach a poll.
        UUID actualAuthor = storyLookupRepo.findById(storyId)
                .map(s -> s.getAuthorId()).orElse(null);
        if (actualAuthor == null || !actualAuthor.equals(authorId)) {
            throw new SecurityException("Not the story author");
        }

        UUID    pollId = UUID.randomUUID();
        Instant now    = Instant.now();

        StoryPollEntity poll = StoryPollEntity.builder()
                .storyId(storyId).pollId(pollId)
                .question(question).optionA(optionA).optionB(optionB)
                .authorId(authorId).createdAt(now)
                .build();
        pollRepo.save(poll);
        return poll;
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
            throw new IllegalArgumentException("Choice must be A or B");
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

        voteRepo.save(PollVoteEntity.builder()
                .pollId(pollId).voterId(voterId)
                .choice(choice).votedAt(now)
                .build());
        voterByChoiceRepo.save(PollVoterByChoiceEntity.builder()
                .pollId(pollId).choice(choice)
                .votedAt(now).voterId(voterId)
                .build());
        counterService.incrementPollVote(pollId, choice);
        return readCounts(pollId, choice);
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

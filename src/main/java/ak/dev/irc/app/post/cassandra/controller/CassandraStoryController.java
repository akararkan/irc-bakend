package ak.dev.irc.app.post.cassandra.controller;

import ak.dev.irc.app.post.cassandra.entity.CloseFriendEntity;
import ak.dev.irc.app.post.cassandra.entity.PollVoteEntity;
import ak.dev.irc.app.post.cassandra.entity.PollVoterByChoiceEntity;
import ak.dev.irc.app.post.cassandra.entity.StoryByAuthorEntity;
import ak.dev.irc.app.post.cassandra.entity.StoryPollEntity;
import ak.dev.irc.app.post.cassandra.entity.StoryViewEntity;
import ak.dev.irc.app.post.cassandra.service.CassandraStoryPollService;
import ak.dev.irc.app.post.cassandra.service.CassandraStoryService;
import ak.dev.irc.app.post.cassandra.service.CloseFriendsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cassandra-backed stories + close-friends endpoints.
 *
 * Separate from {@link CassandraFeedController} because stories have their
 * own visibility model (PUBLIC / FOLLOWERS_ONLY / CLOSE_FRIENDS / ONLY_ME)
 * that doesn't apply to regular posts.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CassandraStoryController {

    private final CassandraStoryService     storyService;
    private final CloseFriendsService       closeFriendsService;
    private final CassandraStoryPollService pollService;

    // ── Stories ──────────────────────────────────────────────────────────────

    public record CreateStoryRequest(UUID authorId, String storyType, String visibility,
                                     String mediaUrl, String thumbnailUrl, String textContent) {}

    @PostMapping("/stories")
    public StoryByAuthorEntity create(@RequestBody CreateStoryRequest req) {
        return storyService.createStory(
                req.authorId(), req.storyType(), req.visibility(),
                req.mediaUrl(), req.thumbnailUrl(), req.textContent());
    }

    /**
     * Active stories for an author, filtered by what the viewer can see.
     * {@code viewerId} omitted = anonymous (PUBLIC only).
     */
    @GetMapping("/stories/by-author/{authorId}")
    public List<StoryByAuthorEntity> active(@PathVariable UUID authorId,
                                            @RequestParam(required = false) UUID viewerId) {
        return storyService.activeStoriesFor(authorId, viewerId);
    }

    /** Hard-delete a story before its 24h TTL — author only. */
    @DeleteMapping("/stories/{storyId}")
    public ResponseEntity<Void> delete(@PathVariable UUID storyId,
                                       @RequestParam UUID actorId) {
        storyService.deleteStory(storyId, actorId);
        return ResponseEntity.noContent().build();
    }

    /** Record a story view. Visibility is enforced server-side. */
    @PostMapping("/stories/{storyId}/views")
    public ResponseEntity<Void> recordView(@PathVariable UUID storyId,
                                           @RequestParam UUID viewerId) {
        storyService.recordView(storyId, viewerId);
        return ResponseEntity.accepted().build();
    }

    /** Viewer log for a story — newest first. Author would typically call this. */
    @GetMapping("/stories/{storyId}/views")
    public List<StoryViewEntity> viewers(@PathVariable UUID storyId,
                                         @RequestParam(defaultValue = "50") int pageSize) {
        return storyService.viewersFor(storyId, pageSize);
    }

    // ── Close friends ────────────────────────────────────────────────────────

    @GetMapping("/close-friends")
    public List<CloseFriendEntity> list(@RequestParam UUID ownerId) {
        return closeFriendsService.listFor(ownerId);
    }

    @PostMapping("/close-friends")
    public ResponseEntity<Void> add(@RequestParam UUID ownerId,
                                    @RequestParam UUID friendId) {
        closeFriendsService.add(ownerId, friendId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/close-friends")
    public ResponseEntity<Void> remove(@RequestParam UUID ownerId,
                                       @RequestParam UUID friendId) {
        closeFriendsService.remove(ownerId, friendId);
        return ResponseEntity.noContent().build();
    }

    /** Lightweight predicate — exposed so the frontend can colour-code UI. */
    @GetMapping("/close-friends/is-member")
    public boolean isCloseFriend(@RequestParam UUID ownerId,
                                 @RequestParam UUID candidateId) {
        return closeFriendsService.isCloseFriend(ownerId, candidateId);
    }

    // ── Story polls ──────────────────────────────────────────────────────────

    public record CreatePollRequest(UUID authorId, String question,
                                    String optionA, String optionB) {}

    /** Attach a two-option poll to a story. Author only. */
    @PostMapping("/stories/{storyId}/poll")
    public StoryPollEntity createPoll(@PathVariable UUID storyId,
                                      @RequestBody CreatePollRequest req) {
        return pollService.createPoll(storyId, req.authorId(),
                                      req.question(), req.optionA(), req.optionB());
    }

    /** Get the poll attached to a story (if any). */
    @GetMapping("/stories/{storyId}/poll")
    public ResponseEntity<StoryPollEntity> getPoll(@PathVariable UUID storyId) {
        return pollService.getPollForStory(storyId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Cast or change a vote. Choice must be "A" or "B". Returns live tallies. */
    @PostMapping("/polls/{pollId}/vote")
    public CassandraStoryPollService.CastVoteResult vote(@PathVariable UUID pollId,
                                                         @RequestParam UUID voterId,
                                                         @RequestParam String choice) {
        return pollService.castVote(pollId, voterId, choice);
    }

    /** "What did I vote?" — used by the poll UI to show the user's pick. */
    @GetMapping("/polls/{pollId}/vote/me")
    public Map<String, Object> myVote(@PathVariable UUID pollId,
                                      @RequestParam UUID voterId) {
        return pollService.userVote(pollId, voterId)
                .map(v -> Map.<String, Object>of("pollId", pollId, "voterId", voterId,
                                                  "choice", v.getChoice(),
                                                  "votedAt", v.getVotedAt()))
                .orElseGet(() -> Map.<String, Object>of("pollId", pollId,
                                                        "voterId", voterId,
                                                        "choice", (Object) null));
    }

    /** Live tallies — no auth needed, mirrors what the poll UI shows. */
    @GetMapping("/polls/{pollId}/results")
    public CassandraStoryPollService.CastVoteResult results(@PathVariable UUID pollId) {
        return pollService.results(pollId);
    }

    /**
     * Author-only voter list per side. Caller MUST enforce that the requester
     * is the story author — this endpoint does not check yet.
     */
    @GetMapping("/polls/{pollId}/voters/{choice}")
    public List<PollVoterByChoiceEntity> voters(@PathVariable UUID pollId,
                                                @PathVariable String choice,
                                                @RequestParam(defaultValue = "50") int pageSize) {
        return pollService.votersFor(pollId, choice, pageSize);
    }
}

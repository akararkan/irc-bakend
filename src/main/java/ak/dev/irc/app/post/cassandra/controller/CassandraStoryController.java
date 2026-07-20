package ak.dev.irc.app.post.cassandra.controller;

import ak.dev.irc.app.post.cassandra.entity.CloseFriendEntity;
import ak.dev.irc.app.post.cassandra.entity.PollVoteEntity;
import ak.dev.irc.app.post.cassandra.entity.PollVoterByChoiceEntity;
import ak.dev.irc.app.post.cassandra.entity.StoryByAuthorEntity;
import ak.dev.irc.app.post.cassandra.entity.StoryPollEntity;
import ak.dev.irc.app.post.cassandra.entity.StoryViewEntity;
import ak.dev.irc.app.common.cache.RateLimiter;
import ak.dev.irc.app.post.cassandra.service.CassandraStoryPollService;
import ak.dev.irc.app.post.cassandra.service.CassandraStoryService;
import ak.dev.irc.app.post.cassandra.service.CloseFriendsService;
import ak.dev.irc.app.post.enums.StoryLifetime;
import ak.dev.irc.app.research.service.S3StorageService;
import ak.dev.irc.app.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import ak.dev.irc.app.common.exception.UnauthorizedException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
    private final S3StorageService          storageService;
    private final ak.dev.irc.app.post.realtime.StoryRealtimeService storyRealtimeService;
    private final ak.dev.irc.app.security.jwt.JwtTokenProvider      jwtTokenProvider;
    /** Per-user write throttling; fail-open if Redis is down. */
    private final RateLimiter               rateLimiter;

    // ── Stories ──────────────────────────────────────────────────────────────

    /**
     * @param lifetimeHours optional — 8, 16, or 24. Anything else (or null)
     *                      falls back to 24h. Determines when the story
     *                      auto-deletes; same TTL applied to the lookup row.
     */
    public record CreateStoryRequest(String storyType, String visibility,
                                     String mediaUrl, String thumbnailUrl,
                                     String textContent, Integer lifetimeHours) {}

    /** Create a story (JSON). authorId is derived from the JWT — body must not pass it. */
    @PostMapping(value = "/stories", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StoryByAuthorEntity> create(@RequestBody CreateStoryRequest req,
                                                      @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        rateLimiter.checkSocial(user.getId());
        return ResponseEntity.ok(storyService.createStory(
                user.getId(), req.storyType(), req.visibility(),
                req.mediaUrl(), req.thumbnailUrl(), req.textContent(),
                StoryLifetime.fromHours(req.lifetimeHours())));
    }

    /**
     * Multipart create — used by the frontend when uploading a photo or
     * video story directly. The {@code media} file is uploaded to R2 and
     * its public URL stored on the story row.
     */
    @PostMapping(value = "/stories", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StoryByAuthorEntity> createMultipart(
            @RequestParam(defaultValue = "PHOTO") String storyType,
            @RequestParam(defaultValue = "PUBLIC") String visibility,
            @RequestParam(required = false) String textContent,
            @RequestParam(required = false) Integer lifetimeHours,
            @RequestPart(value = "media", required = false) MultipartFile media,
            @RequestPart(value = "thumbnail", required = false) MultipartFile thumbnail,
            @AuthenticationPrincipal User user) {

        if (user == null) throw new UnauthorizedException("Authentication required");
        // Throttle BEFORE uploading to R2 so a banned user can't burn storage bandwidth.
        rateLimiter.checkSocial(user.getId());

        String mediaUrl = null;
        if (media != null && !media.isEmpty()) {
            mediaUrl = storageService.getPublicUrl(storageService.upload(media, "stories/media"));
        }
        String thumbnailUrl = null;
        if (thumbnail != null && !thumbnail.isEmpty()) {
            thumbnailUrl = storageService.getPublicUrl(storageService.upload(thumbnail, "stories/thumb"));
        }

        return ResponseEntity.ok(storyService.createStory(
                user.getId(), storyType, visibility,
                mediaUrl, thumbnailUrl, textContent,
                StoryLifetime.fromHours(lifetimeHours)));
    }

    /**
     * Active stories for an author, filtered by what the viewer can see.
     * Viewer derived from JWT; anonymous callers see PUBLIC only.
     */
    @GetMapping("/stories/by-author/{authorId}")
    public List<StoryByAuthorEntity> active(@PathVariable UUID authorId,
                                            @AuthenticationPrincipal User user) {
        return storyService.activeStoriesFor(authorId, user != null ? user.getId() : null);
    }

    /** Hard-delete a story before its 24h TTL — author only. */
    @DeleteMapping("/stories/{storyId}")
    public ResponseEntity<Void> delete(@PathVariable UUID storyId,
                                       @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        storyService.deleteStory(storyId, user.getId());
        return ResponseEntity.noContent().build();
    }

    /** Record a story view. Visibility is enforced server-side. */
    @PostMapping("/stories/{storyId}/views")
    public ResponseEntity<Void> recordView(@PathVariable UUID storyId,
                                           @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        storyService.recordView(storyId, user.getId());
        return ResponseEntity.accepted().build();
    }

    /** Viewer log for a story — newest first. Author only (403 otherwise). */
    @GetMapping("/stories/{storyId}/views")
    public List<StoryViewEntity> viewers(@PathVariable UUID storyId,
                                         @RequestParam(defaultValue = "50") int pageSize,
                                         @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        return storyService.viewersFor(storyId, user.getId(), pageSize);
    }

    /**
     * Per-story SSE stream — live views, reactions, poll votes and lifecycle
     * events while a viewer has the story open. The fan-out infrastructure
     * ({@code StoryRealtimeService} + Redis {@code irc:stories:{storyId}})
     * existed but had no HTTP binding, so every published story event fanned
     * out to zero subscribers. Event names are the lowercased
     * {@code StoryRealtimeEventType} values plus {@code connected} /
     * {@code heartbeat} (every 25s).
     *
     * <p>Auth: bearer header, or {@code ?token=<accessToken>} for browser
     * {@code EventSource} (same convention as the tray stream).</p>
     */
    @GetMapping(value = "/stories/{storyId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("permitAll()")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stream(
            @PathVariable UUID storyId,
            @RequestParam(value = "token", required = false) String token,
            jakarta.servlet.http.HttpServletResponse response) {

        UUID viewerId = ak.dev.irc.app.security.SecurityUtils.getCurrentUserId().orElse(null);
        if (viewerId == null && token != null && !token.isBlank()) {
            try {
                if (jwtTokenProvider.validateToken(token)
                        && "ACCESS".equals(jwtTokenProvider.getTokenType(token))) {
                    viewerId = jwtTokenProvider.getUserIdFromToken(token);
                }
            } catch (Exception ignored) { /* treated as anonymous below */ }
        }
        if (viewerId == null) throw new UnauthorizedException(
                "Authentication required. Pass access token as ?token=<jwt>.");

        // Disable proxy buffering so events stream immediately.
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        return storyRealtimeService.subscribe(storyId, viewerId);
    }

    // ── Close friends ────────────────────────────────────────────────────────

    /** Owner = the authenticated user. The close-friends list is always "mine". */
    @GetMapping("/close-friends")
    public ResponseEntity<List<CloseFriendEntity>> list(@AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        return ResponseEntity.ok(closeFriendsService.listFor(user.getId()));
    }

    @PostMapping("/close-friends")
    public ResponseEntity<Void> add(@RequestParam UUID friendId,
                                    @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        closeFriendsService.add(user.getId(), friendId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/close-friends")
    public ResponseEntity<Void> remove(@RequestParam UUID friendId,
                                       @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        closeFriendsService.remove(user.getId(), friendId);
        return ResponseEntity.noContent().build();
    }

    /** Lightweight predicate — exposed so the frontend can colour-code UI. */
    @GetMapping("/close-friends/is-member")
    public ResponseEntity<Boolean> isCloseFriend(@RequestParam UUID candidateId,
                                                 @AuthenticationPrincipal User user) {
        if (user == null) return ResponseEntity.ok(false);
        return ResponseEntity.ok(closeFriendsService.isCloseFriend(user.getId(), candidateId));
    }

    // ── Story polls ──────────────────────────────────────────────────────────

    public record CreatePollRequest(String question, String optionA, String optionB) {}

    /** Attach a two-option poll to a story. Author only — JWT-derived. */
    @PostMapping("/stories/{storyId}/poll")
    public ResponseEntity<StoryPollEntity> createPoll(@PathVariable UUID storyId,
                                                      @RequestBody CreatePollRequest req,
                                                      @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        return ResponseEntity.ok(pollService.createPoll(storyId, user.getId(),
                req.question(), req.optionA(), req.optionB()));
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
    public ResponseEntity<CassandraStoryPollService.CastVoteResult> vote(@PathVariable UUID pollId,
                                                                         @RequestParam String choice,
                                                                         @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        return ResponseEntity.ok(pollService.castVote(pollId, user.getId(), choice));
    }

    /** "What did I vote?" — used by the poll UI to show the user's pick. */
    @GetMapping("/polls/{pollId}/vote/me")
    public Map<String, Object> myVote(@PathVariable UUID pollId,
                                      @AuthenticationPrincipal User user) {
        if (user == null) return Map.of("pollId", pollId);
        UUID voterId = user.getId();
        return pollService.userVote(pollId, voterId)
                .map(v -> Map.<String, Object>of("pollId", pollId, "voterId", voterId,
                                                  "choice", v.getChoice(),
                                                  "votedAt", v.getVotedAt()))
                // HashMap, not Map.of — Map.of rejects null values, so the
                // "hasn't voted yet" branch used to 500 with an NPE.
                .orElseGet(() -> {
                    Map<String, Object> none = new java.util.HashMap<>();
                    none.put("pollId", pollId);
                    none.put("voterId", voterId);
                    none.put("choice", null);
                    return none;
                });
    }

    /** Live tallies — no auth needed, mirrors what the poll UI shows. */
    @GetMapping("/polls/{pollId}/results")
    public CassandraStoryPollService.CastVoteResult results(@PathVariable UUID pollId) {
        return pollService.results(pollId);
    }

    /**
     * Voter list per side — <strong>author-only</strong>. Resolved via the
     * {@code poll_by_id} reverse index so the path only needs {@code pollId}.
     * Polls created before the reverse index existed return {@code 403} via
     * the {@link java.util.Optional#empty()} fallback (treat as not-owner);
     * they remain accessible by re-creating the poll if needed.
     */
    @GetMapping("/polls/{pollId}/voters/{choice}")
    @PreAuthorize("isAuthenticated()")
    public List<PollVoterByChoiceEntity> voters(@PathVariable UUID pollId,
                                                @PathVariable String choice,
                                                @RequestParam(defaultValue = "50") int pageSize,
                                                @AuthenticationPrincipal User user) {
        if (user == null) throw new UnauthorizedException("Authentication required");
        UUID expectedAuthor = pollService.findOwnership(pollId)
                .map(p -> p.getAuthorId()).orElse(null);
        if (expectedAuthor == null || !expectedAuthor.equals(user.getId())) {
            throw new ak.dev.irc.app.common.exception.ForbiddenException(
                    "Only the poll's author can list voters");
        }
        return pollService.votersFor(pollId, choice, pageSize);
    }
}

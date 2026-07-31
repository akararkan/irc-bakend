package ak.dev.irc.app.common.search.controller;

import ak.dev.irc.app.chat.search.service.ChannelSearchService;
import ak.dev.irc.app.common.search.dto.ReindexSummary;
import ak.dev.irc.app.post.search.service.PostSearchService;
import ak.dev.irc.app.post.search.service.SoundSearchService;
import ak.dev.irc.app.qna.search.service.AnswerSearchService;
import ak.dev.irc.app.qna.search.service.QnaSearchService;
import ak.dev.irc.app.research.search.service.ResearchSearchService;
import ak.dev.irc.app.research.search.service.ResearchSearchService.ReindexResult;
import ak.dev.irc.app.user.search.service.UserSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Admin-only one-shot search operations: full reindex hooks for every
 * ES index whose canonical store can rebuild it (research, users,
 * channels, answers, sounds). Use after a mapping change (a new
 * {@code @Field} on a search document) so existing documents pick up the
 * new field, or to refresh drifted score counters
 * (followerCount / subscriberCount / useCount / reactionCount).
 *
 * <p>The posts and chat-message indices have no reindex hook here on
 * purpose: their canonical stores (Cassandra partitions keyed for feeds)
 * have no efficient full-scan path, and both indices self-heal by being
 * re-written on every entity mutation.</p>
 *
 * <p>Gated by {@code ROLE_ADMIN}. All runs are synchronous — the response
 * body carries the final counts so the caller knows when it's done.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/search")
@RequiredArgsConstructor
public class SearchAdminController {

    private final ResearchSearchService researchSearchService;
    private final PostSearchService     postSearchService;
    private final QnaSearchService      qnaSearchService;
    private final UserSearchService     userSearchService;
    private final ChannelSearchService  channelSearchService;
    private final AnswerSearchService   answerSearchService;
    private final SoundSearchService    soundSearchService;

    /**
     * Reindex every PUBLISHED research record from Postgres into the
     * {@code irc-research} ES index.
     *
     * @param drop when {@code true} (default), the existing index is
     *             deleted first so Spring Data ES recreates it from the
     *             current entity mapping — landing any new
     *             {@code @Field} additions. Pass {@code drop=false} to
     *             refresh score fields without touching the mapping.
     */
    @PostMapping("/research/reindex")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ReindexResult> reindexResearch(
            @RequestParam(name = "drop", defaultValue = "true") boolean drop) {
        return ResponseEntity.ok(researchSearchService.reindexAllPublished(drop));
    }

    /**
     * Reindex every post from Cassandra into {@code irc-posts}. With
     * {@code drop=true} this is also the repair path for a legacy
     * dynamically-created mapping (text lifecycle fields break the
     * visibility/status/postType term filters — recreating from the entity
     * mapping fixes them).
     */
    @PostMapping("/posts/reindex")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ReindexSummary> reindexPosts(
            @RequestParam(name = "drop", defaultValue = "true") boolean drop) {
        return ResponseEntity.ok(postSearchService.reindexAll(drop));
    }

    /** Reindex every live question into {@code irc-qna} (same mapping-repair semantics as posts). */
    @PostMapping("/questions/reindex")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ReindexSummary> reindexQuestions(
            @RequestParam(name = "drop", defaultValue = "true") boolean drop) {
        return ResponseEntity.ok(qnaSearchService.reindexAll(drop));
    }

    /** Reindex every active account into {@code irc-users}. */
    @PostMapping("/users/reindex")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ReindexSummary> reindexUsers(
            @RequestParam(name = "drop", defaultValue = "true") boolean drop) {
        return ResponseEntity.ok(userSearchService.reindexAll(drop));
    }

    /** Reindex every live public channel into {@code irc-channels}. */
    @PostMapping("/channels/reindex")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ReindexSummary> reindexChannels(
            @RequestParam(name = "drop", defaultValue = "true") boolean drop) {
        return ResponseEntity.ok(channelSearchService.reindexAll(drop));
    }

    /** Reindex every live answer (reanswers included) into {@code irc-answers}. */
    @PostMapping("/answers/reindex")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ReindexSummary> reindexAnswers(
            @RequestParam(name = "drop", defaultValue = "true") boolean drop) {
        return ResponseEntity.ok(answerSearchService.reindexAll(drop));
    }

    /** Reindex the whole sound library into {@code irc-sounds}. */
    @PostMapping("/sounds/reindex")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ReindexSummary> reindexSounds(
            @RequestParam(name = "drop", defaultValue = "true") boolean drop) {
        return ResponseEntity.ok(soundSearchService.reindexAll(drop));
    }
}

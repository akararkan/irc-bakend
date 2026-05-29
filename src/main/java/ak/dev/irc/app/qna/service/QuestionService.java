package ak.dev.irc.app.qna.service;

import ak.dev.irc.app.post.dto.CursorPage;
import ak.dev.irc.app.qna.dto.request.*;
import ak.dev.irc.app.qna.dto.response.*;
import ak.dev.irc.app.share.ShareLinkInfo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface QuestionService {

    QuestionResponse createQuestion(CreateQuestionRequest request, UUID authorId);

    QuestionResponse editQuestion(UUID questionId, EditQuestionRequest request, UUID requesterId);

    QuestionResponse getQuestion(UUID questionId);

    /** Block-aware fetch — returns 404 to a blocked viewer. */
    QuestionResponse getQuestion(UUID questionId, UUID viewerId);

    /**
     * Block-aware fetch that also records a (deduped) view and broadcasts a
     * fresh {@code VIEW_COUNT_UPDATED} event on the question's realtime channel.
     * {@code viewerKey} is the dedupe identity — user id for authenticated
     * viewers, client fingerprint (e.g. IP) for anonymous ones.
     */
    QuestionResponse getQuestion(UUID questionId, UUID viewerId, String viewerKey);

    /**
     * Bump the question's view count in its own write transaction and
     * broadcast the fresh number. Idempotent within the dedupe window.
     * Called via the Spring proxy from the read path so {@code REQUIRES_NEW}
     * actually applies.
     */
    void recordView(UUID questionId, UUID viewerId, String viewerKey);

    Page<QuestionResponse> getQuestionFeed(Pageable pageable);

    Page<QuestionResponse> getFeed(Pageable pageable);

    /** Block-aware feed: drops questions from authors the viewer is in a block edge with. */
    Page<QuestionResponse> getFeed(UUID viewerId, Pageable pageable);

    /**
     * Cursor-paginated question feed. Pass {@code cursor=null} for the first
     * page; subsequent pages pass the {@code nextCursor} from the previous
     * response. Doesn't degrade with deep paging.
     */
    CursorPage<QuestionResponse> getFeedCursor(LocalDateTime cursor, int limit);

    /** Block-aware cursor variant. */
    CursorPage<QuestionResponse> getFeedCursor(UUID viewerId, LocalDateTime cursor, int limit);

    Page<QuestionResponse> getFollowingFeed(UUID userId, Pageable pageable);

    Page<QuestionResponse> getMyQuestions(UUID authorId, Pageable pageable);

    // ── Answers ──────────────────────────────────────────────────────────────

    QuestionAnswerResponse addAnswer(UUID questionId, CreateAnswerRequest request, UUID authorId);

    /**
     * Comment-style multipart create: upload one media file (image or video)
     * and one optional voice note in the same request, then delegate to
     * {@link #addAnswer}. Mirrors {@code PostCommentService.addCommentWithMedia}
     * so the answer endpoint feels exactly like the post-comment endpoint —
     * answers just keep the richer set of attachments + sources as defaults.
     */
    QuestionAnswerResponse addAnswerWithMedia(UUID questionId, CreateAnswerRequest request, UUID authorId,
                                              org.springframework.web.multipart.MultipartFile media,
                                              org.springframework.web.multipart.MultipartFile voice);

    QuestionAnswerResponse editAnswer(UUID questionId, UUID answerId, EditAnswerRequest request, UUID requesterId);

    Page<QuestionAnswerResponse> getAnswers(UUID questionId, Pageable pageable);

    /**
     * Restriction-aware top-level answer listing — answers from users the
     * question author has restricted are hidden from everyone except the
     * question author and the answer author themselves.
     */
    Page<QuestionAnswerResponse> getAnswers(UUID questionId, UUID requesterId, Pageable pageable);

    Page<QuestionAnswerResponse> getReanswers(UUID questionId, UUID answerId, Pageable pageable);

    Page<QuestionAnswerResponse> getReanswers(UUID questionId, UUID answerId, UUID requesterId, Pageable pageable);

    void deleteQuestion(UUID questionId, UUID requesterId);

    void deleteAnswer(UUID questionId, UUID answerId, UUID requesterId);

    // ── Answer controls ──────────────────────────────────────────────────────

    QuestionResponse lockAnswers(UUID questionId, UUID requesterId);

    QuestionResponse unlockAnswers(UUID questionId, UUID requesterId);

    QuestionResponse setAnswerLimit(UUID questionId, Integer maxAnswers, UUID requesterId);

    // ── Accept / unaccept (question-author flag, kept for back-compat) ──────

    QuestionAnswerResponse acceptAnswer(UUID questionId, UUID answerId, UUID requesterId);

    QuestionAnswerResponse unacceptAnswer(UUID questionId, UUID answerId, UUID requesterId);

    // ── Multi-scholar best-answer voting ────────────────────────────────────

    /**
     * Mark an answer as a best answer. Any account with the SCHOLAR role
     * (plus admins) may vote. Multiple scholars may independently vote for
     * the same answer; multiple answers per question may all be marked best.
     * Reanswers (replies) are not eligible.
     */
    QuestionAnswerResponse markBestAnswer(UUID questionId, UUID answerId, UUID requesterId);

    /** Remove the caller's own best-answer vote. */
    QuestionAnswerResponse unmarkBestAnswer(UUID questionId, UUID answerId, UUID requesterId);

    // ── Attachments (upload files to answer) ─────────────────────────────────

    AnswerAttachmentResponse uploadAttachment(UUID questionId, UUID answerId, MultipartFile file,
                                               String caption, Integer displayOrder, UUID requesterId);

    List<AnswerAttachmentResponse> getAttachments(UUID questionId, UUID answerId);

    void deleteAttachment(UUID questionId, UUID answerId, UUID attachmentId, UUID requesterId);

    // ── Sources / references ─────────────────────────────────────────────────

    AnswerSourceResponse addSource(UUID questionId, UUID answerId, CreateAnswerSourceRequest request, UUID requesterId);

    /** Attach (or replace) the binary file for a MEDIA_FILE source. Sets sourceType = MEDIA_FILE. */
    AnswerSourceResponse uploadSourceFile(UUID questionId, UUID answerId, UUID sourceId,
                                          MultipartFile file, UUID requesterId);

    AnswerSourceResponse updateSource(UUID questionId, UUID answerId, UUID sourceId,
                                      UpdateAnswerSourceRequest request, UUID requesterId);

    List<AnswerSourceResponse> getSources(UUID questionId, UUID answerId);

    void deleteSource(UUID questionId, UUID answerId, UUID sourceId, UUID requesterId);

    // ── Attachments update ──────────────────────────────────────────────────

    AnswerAttachmentResponse updateAttachment(UUID questionId, UUID answerId, UUID attachmentId,
                                              UpdateAnswerAttachmentRequest request, UUID requesterId);

    // ── Reactions (apply to top-level answers AND reanswers) ────────────────

    QuestionAnswerResponse reactToAnswer(UUID questionId, UUID answerId,
                                         ReactToAnswerRequest request, UUID requesterId);

    /**
     * Remove the viewer's reaction on an answer and return the updated answer
     * with {@code reactionCount} decremented and {@code myReaction=null}, so a
     * front-end gets the new state in one round-trip — no follow-up GET, no
     * reliance on the SSE echo.
     */
    QuestionAnswerResponse removeAnswerReaction(UUID questionId, UUID answerId, UUID requesterId);

    // ══════════════════════════════════════════════════════════════════════════
    //  SAVES / BOOKMARKS  — mirrors PostService.savePost / ResearchService.saveResearch
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Save (bookmark) a question into a collection. Idempotent — saving a
     * question already in the user's bookmarks is a no-op at the DB layer but
     * still returns the updated payload (useful for SSE-driven UIs that need
     * to reconcile against the authoritative count).
     *
     * @param collectionName optional collection (defaults to {@code "Default"})
     */
    QuestionResponse saveQuestion(UUID questionId, UUID userId, String collectionName);

    /**
     * Remove the viewer's bookmark on a question. Idempotent — unsaving a
     * question that is not in the user's bookmarks is a no-op. Returns the
     * updated payload with {@code isSaved=false} and decremented {@code saveCount}.
     */
    QuestionResponse unsaveQuestion(UUID questionId, UUID userId);

    /** Page of the viewer's saved questions, newest first. */
    Page<QuestionResponse> getSavedQuestions(UUID userId, Pageable pageable);

    /** Page of saved questions filtered by collection name. */
    Page<QuestionResponse> getSavedQuestionsByCollection(UUID userId, String collectionName, Pageable pageable);

    /** Distinct collection names the viewer has used. */
    List<String> getSavedQuestionCollections(UUID userId);

    /** Rename a collection across every save row owned by the viewer. */
    void renameSavedQuestionCollection(UUID userId, String oldName, String newName);

    // ══════════════════════════════════════════════════════════════════════════
    //  SHARE  — mirrors PostService.copyShareLink / ResearchService.getShareLink
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Build the share URL info for a question without bumping the counter.
     * Used by the inline UI before the user actually copies/sends the link.
     */
    ShareLinkInfo previewShareLink(UUID questionId, String baseUrl);

    /**
     * Bump the share counter and return the share URL info. Called when the
     * user actually performs the copy / share action.
     */
    ShareLinkInfo recordShare(UUID questionId, UUID requesterId, String baseUrl);
}

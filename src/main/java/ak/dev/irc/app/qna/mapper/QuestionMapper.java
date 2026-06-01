package ak.dev.irc.app.qna.mapper;

import ak.dev.irc.app.common.cache.CounterCache;
import ak.dev.irc.app.common.util.TimeDisplayUtil;
import ak.dev.irc.app.qna.dto.response.*;
import ak.dev.irc.app.qna.entity.*;
import ak.dev.irc.app.qna.enums.AnswerReactionType;
import ak.dev.irc.app.qna.enums.QuestionStatus;
import ak.dev.irc.app.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class QuestionMapper {

    private final CounterCache counterCache;

    private static long nz(Long v) { return v == null ? 0L : v; }

    public QuestionResponse toQuestionResponse(Question question) {
        return toQuestionResponse(question, false, null);
    }

    /**
     * Block-aware overload that lets the service pass a pre-resolved
     * {@code isSaved} flag so listing endpoints can do one batched lookup
     * instead of N+1 round trips.
     */
    public QuestionResponse toQuestionResponse(Question question, boolean isSaved) {
        return toQuestionResponse(question, isSaved, null);
    }

    /**
     * Saved-list overload: also carries the bookmark timestamp (from the
     * {@code QuestionSave} row's {@code createdAt}) onto the response so the
     * frontend can render "Saved &lt;date&gt;" without an extra fetch.
     */
    public QuestionResponse toQuestionResponse(Question question, boolean isSaved,
                                               java.time.LocalDateTime savedAt) {
        return toQuestionResponse(question, isSaved, savedAt, safeTags(question));
    }

    /** Overload with pre-loaded tags — feed/list batch-load (D1) to avoid the lazy N+1. */
    public QuestionResponse toQuestionResponse(Question question, boolean isSaved,
                                               java.time.LocalDateTime savedAt, List<String> tags) {
        User author = question.getAuthor();
        long answers = counterCache.getOr(CounterCache.Kind.QUESTION, question.getId(),
                CounterCache.F_ANSWERS, () -> nz(question.getAnswerCount()));
        boolean acceptsNewAnswers = question.getStatus() == QuestionStatus.OPEN
                && !question.isAnswersLocked()
                && (question.getMaxAnswers() == null || answers < question.getMaxAnswers());
        return new QuestionResponse(
                question.getId(),
                author.getId(),
                author.getUsername(),
                author.getFullName(),
                author.getProfileImage(),
                question.getTitle(),
                question.getBody(),
                question.getStatus(),
                answers,
                counterCache.getOr(CounterCache.Kind.QUESTION, question.getId(),
                        CounterCache.F_VIEWS, () -> nz(question.getViewCount())),
                counterCache.getOr(CounterCache.Kind.QUESTION, question.getId(),
                        CounterCache.F_SAVES, () -> nz(question.getSaveCount())),
                question.isAnswersLocked(),
                question.getMaxAnswers(),
                acceptsNewAnswers,
                nz(question.getAcceptedAnswerCount()) > 0,
                nz(question.getAcceptedAnswerCount()),
                isSaved,
                question.getCreatedAt(),
                question.getUpdatedAt(),
                TimeDisplayUtil.timeAgo(question.getCreatedAt()),
                TimeDisplayUtil.formattedDate(question.getCreatedAt()),
                savedAt,
                tags == null ? List.of() : tags,
                question.getKeywords()
        );
    }

    /**
     * Tags are a LAZY @ElementCollection. On single-question reads (within the
     * service transaction) this returns the real set; on list/detached paths it
     * degrades to an empty list rather than throwing LazyInitializationException.
     */
    private static List<String> safeTags(Question question) {
        try {
            return question.getTags() == null ? List.of() : new ArrayList<>(question.getTags());
        } catch (RuntimeException lazyOrOther) {
            return List.of();
        }
    }

    public QuestionAnswerResponse toAnswerResponse(QuestionAnswer answer) {
        return toAnswerResponse(answer, null, null);
    }

    /**
     * Block-aware overload that accepts pre-resolved {@code myReaction} and
     * {@code replyCount} so listing endpoints can avoid touching lazy
     * collections (which would each trigger a SELECT).
     */
    public QuestionAnswerResponse toAnswerResponse(QuestionAnswer answer,
                                                   AnswerReactionType myReaction,
                                                   Long replyCountOverride) {
        User author = answer.getAuthor();
        boolean deleted = answer.isDeleted();

        List<AnswerAttachmentResponse> attachments = deleted ? Collections.emptyList()
                : mapAttachments(answer.getAttachments());

        List<AnswerSourceResponse> sources = deleted ? Collections.emptyList()
                : mapSources(answer.getSources());

        UUID parentAnswerId = answer.getParentAnswer() != null
                ? answer.getParentAnswer().getId()
                : null;
        long replyCount;
        if (replyCountOverride != null) {
            replyCount = replyCountOverride;
        } else if (answer.getReplies() != null) {
            replyCount = answer.getReplies().stream().filter(r -> !r.isDeleted()).count();
        } else {
            replyCount = 0L;
        }

        return new QuestionAnswerResponse(
                answer.getId(),
                answer.getQuestion().getId(),
                author.getId(),
                author.getUsername(),
                author.getFullName(),
                author.getProfileImage(),
                deleted ? null : answer.getBody(),
                parentAnswerId,
                answer.getReplyToAnswerId(),
                answer.getReplyToUserId(),
                replyCount,
                deleted ? null : answer.getMediaUrl(),
                deleted ? null : answer.getMediaType(),
                deleted ? null : answer.getMediaThumbnailUrl(),
                deleted ? null : answer.getVoiceUrl(),
                deleted ? null : answer.getVoiceDurationSeconds(),
                deleted ? null : answer.getLinks(),
                attachments,
                sources,
                answer.isAccepted(),
                answer.isEdited(),
                answer.getEditedAt(),
                answer.isDeleted(),
                answer.getDeletedAt(),
                counterCache.getOr(CounterCache.Kind.ANSWER, answer.getId(),
                        CounterCache.F_REACTIONS, () -> nz(answer.getReactionCount())),
                myReaction,
                answer.getCreatedAt(),
                answer.getUpdatedAt(),
                TimeDisplayUtil.timeAgo(answer.getCreatedAt()),
                TimeDisplayUtil.formattedDate(answer.getCreatedAt())
        );
    }

    public AnswerAttachmentResponse toAttachmentResponse(AnswerAttachment attachment) {
        return new AnswerAttachmentResponse(
                attachment.getId(),
                attachment.getAnswer().getId(),
                attachment.getFileUrl(),
                attachment.getOriginalFileName(),
                attachment.getMimeType(),
                attachment.getMediaType(),
                attachment.getFileSize(),
                attachment.getDisplayOrder(),
                attachment.getCaption(),
                attachment.getDurationSeconds(),
                attachment.getThumbnailUrl(),
                attachment.getCreatedAt()
        );
    }

    public AnswerSourceResponse toSourceResponse(AnswerSource source) {
        return new AnswerSourceResponse(
                source.getId(),
                source.getAnswer().getId(),
                source.getSourceType(),
                source.getTitle(),
                source.getCitationText(),
                source.getUrl(),
                source.getIsbn(),
                source.getFileUrl(),
                source.getOriginalFileName(),
                source.getDisplayOrder(),
                source.getCreatedAt()
        );
    }

    private List<AnswerAttachmentResponse> mapAttachments(List<AnswerAttachment> attachments) {
        if (attachments == null) return Collections.emptyList();
        return attachments.stream()
                .map(this::toAttachmentResponse)
                .toList();
    }

    private List<AnswerSourceResponse> mapSources(List<AnswerSource> sources) {
        if (sources == null) return Collections.emptyList();
        return sources.stream()
                .map(this::toSourceResponse)
                .toList();
    }
}

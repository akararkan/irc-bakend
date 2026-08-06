package ak.dev.irc.app.settings.safety.service;

import ak.dev.irc.app.settings.safety.entity.Report;
import ak.dev.irc.app.settings.safety.entity.ReportEvidence;
import ak.dev.irc.app.settings.safety.repository.ReportEvidenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Capture-at-submit snapshots per target type (safety-reports.md §3.3).
 * Strictly best-effort — evidence capture must never fail the report intake —
 * and one snapshot per group (the first report wins).
 * <p>
 * MESSAGE targets are the one sanctioned crossing of the chat-content
 * boundary: a snapshot of the SINGLE reported message, reachable only through
 * the report-scoped evidence panel, never as a browse capability. Live-stream
 * chat is never persisted, so those reports carry an explicit
 * "no retroactive evidence" marker instead of an empty panel.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportEvidenceService {

    private static final int MAX_TEXT = 4000;

    private final ReportEvidenceRepository evidenceRepository;
    private final ak.dev.irc.app.post.cassandra.repository.PostByIdRepository postByIdRepo;
    private final ak.dev.irc.app.post.cassandra.repository.CommentLookupRepository commentLookupRepo;
    private final ak.dev.irc.app.post.cassandra.repository.CommentByPostRepository commentByPostRepo;
    private final ak.dev.irc.app.post.cassandra.repository.StoryLookupRepository storyLookupRepo;
    private final ak.dev.irc.app.research.repository.ResearchRepository researchRepository;
    private final ak.dev.irc.app.qna.repository.QuestionRepository questionRepository;
    private final ak.dev.irc.app.qna.repository.QuestionAnswerRepository answerRepository;
    private final ak.dev.irc.app.user.repository.UserRepository userRepository;
    private final ak.dev.irc.app.chat.repository.ConversationRepository conversationRepository;
    private final ak.dev.irc.app.chat.cassandra.repository.MessageByIdRepository messageByIdRepository;

    public void snapshotIfAbsent(Report report) {
        try {
            if (evidenceRepository.existsByGroupKey(report.getGroupKey())) {
                return;
            }
            ReportEvidence.ReportEvidenceBuilder b = ReportEvidence.builder()
                    .reportId(report.getId())
                    .groupKey(report.getGroupKey())
                    .targetType(report.getTargetType().name())
                    .targetRef(report.targetRefOrId());
            capture(report, b);
            evidenceRepository.save(b.build());
        } catch (Exception e) {
            log.warn("[REPORT-EVIDENCE] snapshot failed for group {}: {}",
                    report.getGroupKey(), e.getMessage());
        }
    }

    private void capture(Report report, ReportEvidence.ReportEvidenceBuilder b) {
        UUID id = report.getTargetId();
        switch (report.getTargetType()) {
            case POST -> postByIdRepo.findById(id).ifPresentOrElse(p -> b
                            .authorId(p.getAuthorId())
                            .contentText(truncate(p.getTextContent()))
                            .mediaRefs(join(p.getMediaUrls()))
                            .entityState(p.getStatus()),
                    () -> b.entityState("target missing at capture"));
            case COMMENT -> commentLookupRepo.findById(id).ifPresentOrElse(meta -> {
                b.authorId(meta.getAuthorId());
                String text = null;
                try {
                    if (!Boolean.TRUE.equals(meta.getReply())) {
                        text = commentByPostRepo
                                .findRow(meta.getPostId(), meta.getCreatedAt(), id)
                                .map(ak.dev.irc.app.post.cassandra.entity.CommentByPostEntity::getTextContent)
                                .orElse(null);
                    }
                } catch (Exception ignored) {
                }
                b.contentText(truncate(text)).entityState("live");
            }, () -> b.entityState("target missing at capture"));
            case STORY -> storyLookupRepo.findById(id).ifPresentOrElse(meta -> b
                            .authorId(meta.getAuthorId())
                            .contentText("visibility=" + meta.getVisibility()
                                    + ", expiresAt=" + meta.getExpiresAt())
                            .entityState("live (TTL)"),
                    () -> b.entityState("expired or missing at capture"));
            case RESEARCH -> researchRepository.findById(id).ifPresentOrElse(r -> b
                            .authorId(r.getResearcher() != null ? r.getResearcher().getId() : null)
                            .contentText(truncate(r.getTitle() + "\n\n" + r.getAbstractText()))
                            .entityState(r.getStatus() != null ? r.getStatus().name() : null),
                    () -> b.entityState("target missing at capture"));
            case QUESTION -> questionRepository.findById(id).ifPresentOrElse(q -> b
                            .authorId(q.getAuthor() != null ? q.getAuthor().getId() : null)
                            .contentText(truncate(q.getTitle() + "\n\n" + q.getBody()))
                            .entityState(q.getStatus() != null ? q.getStatus().name() : null),
                    () -> b.entityState("target missing at capture"));
            case ANSWER -> answerRepository.findById(id).ifPresentOrElse(a -> b
                            .authorId(a.getAuthor() != null ? a.getAuthor().getId() : null)
                            .contentText(truncate(a.getBody()))
                            .entityState(a.isDeleted() ? "soft-deleted" : "live"),
                    () -> b.entityState("target missing at capture"));
            case USER -> userRepository.findById(id).ifPresentOrElse(u -> b
                            .authorId(u.getId())
                            .contentText(truncate("@" + u.getUsername() + " — " + u.getFullName()))
                            .entityState(u.getDeletedAt() != null ? "deleted" : "active"),
                    () -> b.entityState("target missing at capture"));
            case CHANNEL -> conversationRepository.findById(id).ifPresentOrElse(c -> b
                            .authorId(c.getOwnerId())
                            .contentText(truncate(c.getTitle() + " (@" + c.getHandle() + ")\n"
                                    + c.getDescription()))
                            .entityState(c.getDeletedAt() != null ? "deleted" : "live"),
                    () -> b.entityState("target missing at capture"));
            case MESSAGE -> captureMessage(report, b);
        }
    }

    private void captureMessage(Report report, ReportEvidence.ReportEvidenceBuilder b) {
        String ref = report.targetRefOrId();
        Long messageId = null;
        try {
            messageId = Long.parseLong(ref);
        } catch (Exception ignored) {
        }
        if (messageId == null) {
            b.entityState("no retroactive evidence (unparseable message ref)");
            return;
        }
        try {
            messageByIdRepository.findById(messageId).ifPresentOrElse(m -> b
                            .authorId(m.getSenderId())
                            .contentText(truncate(m.getBody()))
                            .entityState("live"),
                    () -> b.entityState("no retroactive evidence (message gone or never persisted)"));
        } catch (Exception e) {
            b.entityState("no retroactive evidence (capture failed)");
        }
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT);
    }

    private static String join(List<String> urls) {
        return urls == null || urls.isEmpty() ? null : String.join(",", urls);
    }
}

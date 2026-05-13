package ak.dev.irc.app.activity.entity;

import ak.dev.irc.app.activity.enums.UserActivityType;
import ak.dev.irc.app.common.BaseAuditEntity;
import ak.dev.irc.app.post.entity.Post;
import ak.dev.irc.app.post.entity.PostComment;
import ak.dev.irc.app.post.enums.PostReactionType;
import ak.dev.irc.app.qna.entity.Question;
import ak.dev.irc.app.qna.entity.QuestionAnswer;
import ak.dev.irc.app.qna.enums.AnswerReactionType;
import ak.dev.irc.app.research.entity.Research;
import ak.dev.irc.app.research.entity.ResearchComment;
import ak.dev.irc.app.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(
        name = "user_activities",
        indexes = {
                @Index(name = "idx_uact_user_created", columnList = "user_id, created_at DESC"),
                @Index(name = "idx_uact_user_type",    columnList = "user_id, activity_type"),
                @Index(name = "idx_uact_post",         columnList = "post_id"),
                @Index(name = "idx_uact_comment",      columnList = "comment_id"),
                @Index(name = "idx_uact_target_user",  columnList = "target_user_id"),
                @Index(name = "idx_uact_user_query",   columnList = "user_id, query"),
                @Index(name = "idx_uact_question",     columnList = "question_id"),
                @Index(name = "idx_uact_answer",       columnList = "answer_id"),
                @Index(name = "idx_uact_research",     columnList = "research_id"),
                @Index(name = "idx_uact_rcomment",     columnList = "research_comment_id")
        }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserActivity extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_uact_user"))
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "activity_type", nullable = false, length = 40)
    private UserActivityType activityType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id",
            foreignKey = @ForeignKey(name = "fk_uact_post"))
    private Post post;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "comment_id",
            foreignKey = @ForeignKey(name = "fk_uact_comment"))
    private PostComment comment;

    @Enumerated(EnumType.STRING)
    @Column(name = "reaction_type", length = 30)
    private PostReactionType reactionType;

    @Column(name = "watched_seconds")
    private Integer watchedSeconds;

    /** Search / mention query text — used by GLOBAL_SEARCH, HASHTAG_SEARCH, MENTION_LOOKUP. */
    @Column(name = "query", length = 200)
    private String query;

    /**
     * Comma-separated requested {@code SearchType}s for GLOBAL_SEARCH (e.g. "POST,USER").
     * Null means "all corpora".
     */
    @Column(name = "search_scope", length = 120)
    private String searchScope;

    /** Number of hits the search / mention lookup returned. */
    @Column(name = "hit_count")
    private Integer hitCount;

    /** Target user for MENTION_LOOKUP (the user that was clicked) and PROFILE_VIEW. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_user_id",
            foreignKey = @ForeignKey(name = "fk_uact_target_user"))
    private User targetUser;

    /** Question for QNA_* activity types. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id",
            foreignKey = @ForeignKey(name = "fk_uact_question"))
    private Question question;

    /** Answer / reanswer for QNA_ANSWER_* / QNA_REANSWER_* / QNA_BEST_ANSWER_VOTE / QNA_ANSWER_REACTION. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "answer_id",
            foreignKey = @ForeignKey(name = "fk_uact_answer"))
    private QuestionAnswer answer;

    /** Reaction emoji for QNA_ANSWER_REACTION. */
    @Enumerated(EnumType.STRING)
    @Column(name = "qna_reaction_type", length = 30)
    private AnswerReactionType qnaReactionType;

    /** Research for RESEARCH_REACTION / RESEARCH_COMMENT / RESEARCH_COMMENT_REACTION. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "research_id",
            foreignKey = @ForeignKey(name = "fk_uact_research"))
    private Research research;

    /** Comment on a research for RESEARCH_COMMENT / RESEARCH_COMMENT_REACTION. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "research_comment_id",
            foreignKey = @ForeignKey(name = "fk_uact_rcomment"))
    private ResearchComment researchComment;
}

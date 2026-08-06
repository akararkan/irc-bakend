package ak.dev.irc.app.admin.feed;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The single runtime override row for the feed ranking knobs
 * (search-feed-trending.md §2.3 runtime tuning). PK is the fixed literal
 * {@code "default"} — one live config, versioned by audit trail, staged by
 * {@code rolloutPercent} (0 = nobody, 100 = everyone; users bucket by id
 * hash so a partial rollout is sticky per user, not random per request).
 *
 * <p>Nullable knob columns mean "inherit the compile-time default" — a PATCH
 * only pins what it names.</p>
 */
@Entity
@Table(name = "feed_ranking_config")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedRankingConfig {

    public static final String SINGLETON_ID = "default";

    @Id
    @Column(name = "id", length = 16)
    @Builder.Default
    private String id = SINGLETON_ID;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = false;

    /** 0–100; users whose id-hash bucket is below this get the override. */
    @Column(name = "rollout_percent", nullable = false)
    @Builder.Default
    private int rolloutPercent = 0;

    @Column(name = "w_like")     private Double wLike;
    @Column(name = "w_comment")  private Double wComment;
    @Column(name = "w_share")    private Double wShare;
    @Column(name = "w_save")     private Double wSave;
    @Column(name = "w_view")     private Double wView;
    @Column(name = "w_affinity") private Double wAffinity;

    @Column(name = "half_life_post")     private Double halfLifePost;
    @Column(name = "half_life_reel")     private Double halfLifeReel;
    @Column(name = "half_life_channel")  private Double halfLifeChannel;
    @Column(name = "half_life_research") private Double halfLifeResearch;
    @Column(name = "half_life_question") private Double halfLifeQuestion;

    @Column(name = "boost_mutual") private Double boostMutual;
    @Column(name = "boost_self")   private Double boostSelf;
    @Column(name = "damp_channel") private Double dampChannel;
    @Column(name = "damp_explore") private Double dampExplore;

    @Column(name = "max_author_run") private Integer maxAuthorRun;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;
}
